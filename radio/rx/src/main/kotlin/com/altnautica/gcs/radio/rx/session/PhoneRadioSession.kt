package com.altnautica.gcs.radio.rx.session

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.WfbReceiver
import com.altnautica.gcs.radio.rx.auxmux.AuxChannel
import com.altnautica.gcs.radio.rx.auxmux.AuxDecodeResult
import com.altnautica.gcs.radio.rx.auxmux.AuxMuxDecoder
import com.altnautica.gcs.radio.rx.hop.HopFollower
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.invite.StoredInvite
import com.altnautica.gcs.radio.rx.tx.PhoneGroundId
import com.altnautica.gcs.radio.rx.tx.UplinkRun
import com.altnautica.gcs.radio.rx.tx.UplinkState
import com.altnautica.gcs.radio.rx.usb.DongleStatus
import com.altnautica.gcs.radio.rx.usb.FrameRecord
import com.altnautica.gcs.radio.rx.usb.FrameRecordReader
import com.altnautica.gcs.radio.rx.usb.UsbDongleMonitor
import com.altnautica.gcs.radio.rx.usb.UsbRadioConnection
import com.altnautica.gcs.radio.usb.IUsbRadio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Whether the phone radio is receiving. */
sealed interface PhoneRadioState {
    data object Stopped : PhoneRadioState
    data object Starting : PhoneRadioState
    data class Receiving(val channel: Int) : PhoneRadioState
    data class Failed(val reason: PhoneRadioFailure, val detail: String? = null) : PhoneRadioState
}

/**
 * Why the receiver stopped. [KeysUnreadable]: the credential vault could not be read just now; starting again retries.
 * [DongleRemoved]: the radio this session was using was unplugged.
 */
enum class PhoneRadioFailure { NoInvite, KeysUnreadable, NoDongle, DongleRemoved, NoPermission, OpenFailed, ServiceLost, RadioStopped }

/** One drone of the invite as the receiver hears it. */
data class DroneLinkStatus(
    val deviceId: DeviceId,
    val slot: Int,
    /** `elapsedRealtime` of the last datagram recovered from this drone, null when none yet. */
    val lastPacketMs: Long?,
)

/** One second of receive statistics. */
data class PhoneRadioStats(
    val channel: Int,
    val bandwidthMhz: Int,
    val rssiDbm: Int?,
    val snrDb: Int?,
    val videoPacketsPerSec: Long,
    val telemetryPacketsPerSec: Long,
    val fecRecoveredPerSec: Long,
    val lostPerSec: Long,
    val decryptErrorsPerSec: Long,
    val usbFramesPerSec: Long,
    val usbDroppedPerSec: Long,
    val hopsFollowed: Int,
)

/**
 * The phone-radio session: drives the USB radio service, recovers each
 * drone's WFB downlink with the invite's keys, follows authenticated channel
 * hops, and fans the planes out: video RTP per drone ([video]) and MAVLink
 * from the aux lane per drone ([mavlink]).
 *
 * Receive only, unless the phone holds the uplink. Today only a debug build's
 * bench radio holds it ([AlternateRadio.holdsUplink]); the over-the-air bind
 * and the handover from a box are the other two ways in, not built yet. While
 * it holds the uplink the session runs the box's two ground transmitters,
 * echoes every verified hop announcement as the HopAck, sends the presence
 * beacon, and judges the uplink ([uplink]).
 */
@Singleton
class PhoneRadioSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: InviteStore,
    private val dongles: UsbDongleMonitor,
    private val alternate: Optional<AlternateRadio>,
    private val groundId: PhoneGroundId,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Mutex()

    private val _state = MutableStateFlow<PhoneRadioState>(PhoneRadioState.Stopped)
    val state: StateFlow<PhoneRadioState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<PhoneRadioStats?>(null)
    val stats: StateFlow<PhoneRadioStats?> = _stats.asStateFlow()

    private val _links = MutableStateFlow<List<DroneLinkStatus>>(emptyList())
    val links: StateFlow<List<DroneLinkStatus>> = _links.asStateFlow()

    private val _uplink = MutableStateFlow<UplinkState?>(null)

    /** The uplink while the phone holds it; null while it is receive only. */
    val uplink: StateFlow<UplinkState?> = _uplink.asStateFlow()

    /** Whether a start would run on an alternate radio (a debug build's bench relay) rather than the dongle. */
    val alternateRadioAvailable: Boolean get() = alternate.orElse(null)?.available == true

    private val mavlinkFlows = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()
    private val videoFlows = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()

    private var active: Running? = null
    private var startJob: Job? = null

    /** MAVLink bytes received from [node] (whole aux payloads, frames back to back). */
    fun mavlink(node: DeviceId): Flow<ByteArray> = flowFor(mavlinkFlows, node, MAVLINK_BUFFER).asSharedFlow()

    /** RTP packets of [node]'s video plane. */
    fun video(node: DeviceId): Flow<ByteArray> = flowFor(videoFlows, node, VIDEO_BUFFER).asSharedFlow()

    /**
     * Sends one aux-uplink datagram (an aux-mux frame, radio port 3 under the
     * ground link id) while the phone holds the uplink; false while receive
     * only, when the frame is larger than one WFB packet, or with no run.
     */
    fun sendAux(payload: ByteArray): Boolean = active?.sendAux(payload) ?: false

    fun start() {
        if (startJob?.isActive == true || active != null) return
        startJob = scope.launch { control.withLock { startLocked() } }
    }

    suspend fun stop() {
        startJob?.cancelAndJoin()
        control.withLock { stopLocked(PhoneRadioState.Stopped) }
    }

    private suspend fun startLocked() {
        if (active != null) return
        _state.value = PhoneRadioState.Starting
        val invite = try {
            store.load()
        } catch (_: VaultUnreadableException) {
            return fail(PhoneRadioFailure.KeysUnreadable)
        } ?: return fail(PhoneRadioFailure.NoInvite)
        val bench = alternate.orElse(null)?.takeIf { it.available }
        if (bench != null) return startOn(invite, bench)
        val device = when (val d = dongles.current()) {
            is DongleStatus.Ready -> d.device
            is DongleStatus.NeedsPermission -> return fail(PhoneRadioFailure.NoPermission)
            else -> return fail(PhoneRadioFailure.NoDongle)
        }
        val usbConnection = dongles.open(device)
            ?: return fail(
                // The radio went between the read above and the open.
                if (dongles.current() is DongleStatus.None) PhoneRadioFailure.DongleRemoved else PhoneRadioFailure.NoPermission,
            )
        val binding = UsbRadioConnection(context) { scope.launch { onServiceLost() } }
        var frames: ParcelFileDescriptor? = null
        try {
            val radio = binding.connect()
            withContext(Dispatchers.IO) {
                ParcelFileDescriptor.fromFd(usbConnection.fileDescriptor).use { pfd ->
                    radio.open(pfd, device.vendorId, device.productId)
                }
                radio.setChannel(invite.bundle.channel, invite.bundle.bandwidthMhz)
                frames = radio.frames()
            }
            val usb = UsbHold(binding, usbConnection, device.deviceName)
            val running = Running(invite, radio, requireNotNull(frames), usb, holdsUplink = false)
            active = running
            running.begin()
            _state.value = PhoneRadioState.Receiving(invite.bundle.channel)
        } catch (e: Exception) {
            Log.w(TAG, "phone radio did not start", e)
            frames?.close()
            binding.disconnect()
            usbConnection.close()
            // An unplug during the bring-up surfaces as a driver error.
            if (dongles.current() is DongleStatus.None) fail(PhoneRadioFailure.DongleRemoved) else fail(PhoneRadioFailure.OpenFailed, e.message)
        }
    }

    /** Starts on [source]'s radio instead of the USB dongle. */
    private suspend fun startOn(invite: StoredInvite, source: AlternateRadio) {
        var frames: ParcelFileDescriptor? = null
        var radio: IUsbRadio? = null
        try {
            withContext(Dispatchers.IO) {
                val r = source.open(invite.bundle)
                radio = r
                r.open(null, 0, 0)
                r.setChannel(invite.bundle.channel, invite.bundle.bandwidthMhz)
                frames = r.frames()
            }
            val running = Running(invite, requireNotNull(radio), requireNotNull(frames), usb = null, holdsUplink = source.holdsUplink)
            active = running
            running.begin()
            _state.value = PhoneRadioState.Receiving(invite.bundle.channel)
        } catch (e: Exception) {
            Log.w(TAG, "phone radio did not start on its alternate radio", e)
            frames?.close()
            runCatching { radio?.close() }
            fail(PhoneRadioFailure.OpenFailed, e.message)
        }
    }

    private fun fail(reason: PhoneRadioFailure, detail: String? = null) {
        _state.value = PhoneRadioState.Failed(reason, detail)
    }

    private suspend fun onServiceLost() = control.withLock {
        if (active != null) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.ServiceLost))
    }

    private suspend fun onReaderEnded() = control.withLock {
        val running = active ?: return@withLock
        if (!running.stopping) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.RadioStopped))
    }

    private suspend fun onDongleRemoved(running: Running) = control.withLock {
        if (active === running) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.DongleRemoved))
    }

    private suspend fun stopLocked(next: PhoneRadioState) {
        val running = active
        active = null
        running?.end()
        _stats.value = null
        _links.value = emptyList()
        _state.value = next
    }

    private fun flowFor(map: ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>, node: DeviceId, buffer: Int) =
        map.getOrPut(node.value) { MutableSharedFlow(extraBufferCapacity = buffer, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    /** What a run on the USB dongle holds besides the radio: the service binding, the device and its name. */
    private class UsbHold(
        val binding: UsbRadioConnection,
        val usbConnection: UsbDeviceConnection,
        /** The USB device name of this run's radio, matched against detaches. */
        val deviceName: String,
    )

    /** One run, from radio open to close. */
    private inner class Running(
        val invite: StoredInvite,
        val radio: IUsbRadio,
        val frames: ParcelFileDescriptor,
        /** Null on an alternate radio. */
        val usb: UsbHold?,
        /** Whether the phone holds the uplink on this run, and so transmits. */
        val holdsUplink: Boolean,
    ) {
        @Volatile var stopping = false
        private val bundle = invite.bundle
        private val ports = bundle.radioPorts
        private val links = bundle.links()
        private val lastPacket = ConcurrentHashMap<String, Long>()
        @Volatile private var channel = bundle.channel
        @Volatile private var lastRetuneMs = SystemClock.elapsedRealtime()
        private val startedMs = SystemClock.elapsedRealtime()

        private val rssiSum = AtomicLong()
        private val snrSum = AtomicLong()
        private val signalCount = AtomicLong()
        private val telemetryPackets = AtomicInteger()

        private val follower = HopFollower(bundle.hopKey, bundle.channel, scope) { target -> retune(target) }
        private var uplinkRun: UplinkRun? = null
        private var hopsAtLastTick = 0
        private var reader: Thread? = null
        private var ticker: Job? = null
        private var unplugWatch: Job? = null
        private var lastUsb: LongArray? = null

        fun begin() {
            val receivers = links.associate { spec ->
                spec.linkId to WfbReceiver(bundle.rxKey, spec.linkId, ports.asArray()) { port, payload ->
                    onDatagram(spec.deviceId, port, payload)
                }
            }
            reader = Thread({ readLoop(receivers) }, "phone-radio-rx").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            ticker = scope.launch {
                while (isActive) {
                    delay(STATS_MS)
                    tick(receivers.values)
                }
            }
            if (holdsUplink) {
                val run = UplinkRun(radio, bundle, groundId.value, scope, { channel }) { _uplink.value = it }
                if (run.begin()) {
                    uplinkRun = run
                    follower.ack = run::ack
                }
            }
            // Unplugging the radio closes nothing on its own; the run ends
            // here, outside this watch, so its cancellation does not wait on
            // itself. A radio already gone when the watch starts ends it too.
            val deviceName = usb?.deviceName ?: return
            unplugWatch = scope.launch {
                dongles.unplugged(deviceName).first()
                scope.launch { onDongleRemoved(this@Running) }
            }
        }

        private fun readLoop(receivers: Map<Int, WfbReceiver>) {
            try {
                FileInputStream(frames.fileDescriptor).use { input ->
                    val records = FrameRecordReader(input)
                    while (true) {
                        val len = records.next()
                        if (len < 0) break
                        val body = records.buffer
                        FrameRecord.bestRssi(body)?.let { rssi ->
                            rssiSum.addAndGet(rssi.toLong())
                            snrSum.addAndGet((FrameRecord.bestSnr(body) ?: 0).toLong())
                            signalCount.incrementAndGet()
                        }
                        val link = FrameRecord.linkId(body, len) ?: continue
                        receivers[link]?.feed(body, len)
                    }
                }
            } catch (e: IOException) {
                if (!stopping) Log.w(TAG, "frame pipe ended", e)
            } finally {
                receivers.values.forEach { it.close() }
                if (!stopping) scope.launch { onReaderEnded() }
            }
        }

        private fun onDatagram(deviceId: String, port: Int, payload: ByteArray) {
            lastPacket[deviceId] = SystemClock.elapsedRealtime()
            when (port) {
                ports.video -> videoFlows[deviceId]?.tryEmit(payload)
                ports.control -> follower.onControlPacket(payload)
                ports.auxDown -> {
                    val frame = AuxMuxDecoder.decode(payload)
                    if (frame is AuxDecodeResult.Frame && frame.channel == AuxChannel.MAVLINK) {
                        telemetryPackets.incrementAndGet()
                        mavlinkFlows[deviceId]?.tryEmit(frame.payload)
                    }
                }
            }
        }

        fun sendAux(payload: ByteArray): Boolean = uplinkRun?.sendAux(payload) ?: false

        private suspend fun retune(target: Int): Boolean = withContext(Dispatchers.IO) {
            val ok = runCatching { radio.setChannel(target, bundle.bandwidthMhz) }
                .onFailure { Log.w(TAG, "retune to $target failed", it) }
                .isSuccess
            if (ok) {
                channel = target
                lastRetuneMs = SystemClock.elapsedRealtime()
                if (!stopping) _state.value = PhoneRadioState.Receiving(target)
            }
            ok
        }

        private suspend fun tick(receivers: Collection<WfbReceiver>) {
            val now = SystemClock.elapsedRealtime()
            _links.value = links.map { DroneLinkStatus(DeviceId(it.deviceId), it.slot, lastPacket[it.deviceId]) }

            // A drone that hopped while the phone missed the announcement is
            // found again on the rendezvous channel, then back on the invite's.
            val lastHeard = lastPacket.values.maxOrNull() ?: startedMs
            if (now - lastHeard > SILENCE_MS && now - lastRetuneMs > SILENCE_MS) {
                val other = if (channel == bundle.channel) bundle.rendezvousChannel else bundle.channel
                if (retune(other)) follower.onRetunedElsewhere(other) else lastRetuneMs = now
            }

            // A hop this phone acknowledged that the drone carried out (its
            // downlink came back after the retune) is the drone acting on the
            // phone's own transmission.
            val hops = follower.hopsFollowed
            if (hops > hopsAtLastTick && lastHeard > lastRetuneMs) {
                hopsAtLastTick = hops
                uplinkRun?.evidence()
            }

            var video = 0L
            var recovered = 0L
            var lost = 0L
            var decryptErrors = 0L
            for (r in receivers) {
                for (p in r.stats().ports) {
                    if (p.port == ports.video) video += p.delivered
                    recovered += p.fecRecovered
                    lost += p.lost
                    decryptErrors += p.decryptErrors
                }
            }
            val usb = runCatching { radio.stats() }.getOrNull()
            val prev = lastUsb
            lastUsb = usb
            val usbFrames = if (usb != null && prev != null) usb[2] - prev[2] else 0L
            val usbDropped = if (usb != null && prev != null) usb[3] - prev[3] else 0L
            val n = signalCount.getAndSet(0)
            val rssi = rssiSum.getAndSet(0)
            val snr = snrSum.getAndSet(0)
            _stats.value = PhoneRadioStats(
                channel = channel,
                bandwidthMhz = bundle.bandwidthMhz,
                rssiDbm = if (n > 0) (rssi / n).toInt() else null,
                snrDb = if (n > 0) (snr / n).toInt() else null,
                videoPacketsPerSec = video,
                telemetryPacketsPerSec = telemetryPackets.getAndSet(0).toLong(),
                fecRecoveredPerSec = recovered,
                lostPerSec = lost,
                decryptErrorsPerSec = decryptErrors,
                usbFramesPerSec = usbFrames,
                usbDroppedPerSec = usbDropped,
                hopsFollowed = follower.hopsFollowed,
            )
        }

        suspend fun end() {
            stopping = true
            follower.ack = null
            follower.cancel()
            uplinkRun?.close()
            uplinkRun = null
            _uplink.value = null
            ticker?.cancelAndJoin()
            unplugWatch?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                // Closing the radio closes the pipe's write end; the reader
                // then sees end of stream and releases the receivers.
                runCatching { radio.close() }
                reader?.join(READER_JOIN_MS)
                runCatching { frames.close() }
                usb?.binding?.disconnect()
                usb?.usbConnection?.close()
            }
        }
    }

    private companion object {
        const val TAG = "AdosPhoneRadio"
        const val STATS_MS = 1_000L
        const val SILENCE_MS = 25_000L
        const val READER_JOIN_MS = 2_000L
        const val MAVLINK_BUFFER = 256
        const val VIDEO_BUFFER = 512
    }
}
