package com.altnautica.gcs.radio.rx.session

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.link.fleet.FleetGroundStore
import com.altnautica.gcs.core.link.fleet.SlotReconciler
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.aux.AuxLanes
import com.altnautica.gcs.radio.rx.invite.InviteStore
import com.altnautica.gcs.radio.rx.tx.PhoneGroundId
import com.altnautica.gcs.radio.rx.tx.UplinkState
import com.altnautica.gcs.radio.rx.usb.DongleStatus
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
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

/** Another ground's presence beacon, heard on this fleet's ground link. */
data class GroundBeacon(val deviceId: String, val channel: Int)

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
 * drone's WFB downlink, follows authenticated channel hops, and fans the
 * planes out: video RTP per drone ([video]) and the aux lane per drone
 * ([lanes]: MAVLink, the pilot, relayed calls, the config channel).
 *
 * A run listens with a box's invite, or, when the phone bound a fleet over
 * the air, as that fleet's own ground ([FleetGroundStore]): then it holds the
 * uplink from the start and enrols every drone whose beacon it hears.
 * Otherwise it is receive only until it holds the uplink: on a debug build's
 * bench radio ([AlternateRadio.holdsUplink]) or through [holdUplink]. While it
 * holds the uplink it runs the box's two ground transmitters, echoes hop
 * announcements as the HopAck, sends the presence beacon, reports link
 * feedback once a second, and judges the uplink ([uplink]).
 */
@Singleton
class PhoneRadioSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: InviteStore,
    private val dongles: UsbDongleMonitor,
    private val alternate: Optional<AlternateRadio>,
    private val groundId: PhoneGroundId,
    private val fleetGround: FleetGroundStore,
    /** Provided lazily: the reconciler calls back into this session's Request lane. */
    private val reconciler: Provider<SlotReconciler>,
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

    /** True while the phone holds the uplink and it is not down: writes go out. */
    val transmitting: StateFlow<Boolean> = _uplink.map { it != null && it != UplinkState.Down }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _groundBeacons = MutableSharedFlow<GroundBeacon>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Presence beacons of other grounds on this fleet's ground link, while a run is up. */
    val groundBeacons: Flow<GroundBeacon> = _groundBeacons.asSharedFlow()

    /** Whether a start would run on an alternate radio (a debug build's bench relay) rather than the dongle. */
    val alternateRadioAvailable: Boolean get() = alternate.orElse(null)?.available == true

    /** The aux lane's writers and readers; writes go out only while [transmitting]. */
    val lanes = AuxLanes { frame -> transmitting.value && sendAux(frame) }

    private val videoFlows = ConcurrentHashMap<String, MutableSharedFlow<ByteArray>>()

    private var active: Held? = null
    private var startJob: Job? = null

    /** MAVLink bytes received from [node] (whole aux payloads, frames back to back). */
    fun mavlink(node: DeviceId): Flow<ByteArray> = lanes.mavlink(node.value)

    /** RTP packets of [node]'s video plane. */
    fun video(node: DeviceId): Flow<ByteArray> = videoFor(node.value).asSharedFlow()

    /** Whether [node] was heard on this run. */
    fun hears(node: DeviceId): Boolean = active?.run?.hears(node.value) == true

    /**
     * Sends one aux-uplink datagram (an aux-mux frame, radio port 3 under the
     * ground link id) while the phone holds the uplink; false while receive
     * only, when the frame is larger than one WFB packet, or with no run.
     */
    fun sendAux(payload: ByteArray): Boolean = active?.run?.sendAux(payload) ?: false

    /**
     * Starts the ground transmitters on the running session exactly as a
     * bench run does; true once transmitting, false with no run or when the
     * transmitters did not open. Idempotent.
     */
    suspend fun holdUplink(): Boolean = control.withLock { active?.run?.holdUplink() ?: false }

    /**
     * Closes the ground transmitters ([uplink] reads null), then answers true
     * once the radio's sent-frame count has stayed flat for a second, false
     * when it did not within five. True with nothing held.
     */
    suspend fun releaseUplink(): Boolean {
        val run = control.withLock { active?.run?.also { it.dropUplink() } } ?: return true
        val deadline = SystemClock.elapsedRealtime() + RELEASE_GIVE_UP_MS
        var last = run.framesSent() ?: return true
        var flatSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(RELEASE_POLL_MS)
            val now = SystemClock.elapsedRealtime()
            val sent = run.framesSent() ?: return true
            if (sent != last) {
                last = sent
                flatSince = now
            } else if (now - flatSince >= RELEASE_QUIET_MS) {
                return true
            }
        }
        return false
    }

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
        val plan = try {
            store.load()?.let(RunPlan::of) ?: fleetGround.load()?.let(RunPlan::of)
        } catch (_: VaultUnreadableException) {
            return fail(PhoneRadioFailure.KeysUnreadable)
        } ?: return fail(PhoneRadioFailure.NoInvite)
        val bench = alternate.orElse(null)?.takeIf { it.available }
        if (bench != null) return startOn(plan, bench)
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
                radio.setChannel(plan.bundle.channel, plan.bundle.bandwidthMhz)
                frames = radio.frames()
            }
            begin(plan, radio, requireNotNull(frames), UsbHold(binding, usbConnection, device.deviceName), holdsUplink = false)
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
    private suspend fun startOn(plan: RunPlan, source: AlternateRadio) {
        var frames: ParcelFileDescriptor? = null
        var radio: IUsbRadio? = null
        try {
            withContext(Dispatchers.IO) {
                val r = source.open(plan.bundle)
                radio = r
                r.open(null, 0, 0)
                r.setChannel(plan.bundle.channel, plan.bundle.bandwidthMhz)
                frames = r.frames()
            }
            begin(plan, requireNotNull(radio), requireNotNull(frames), usb = null, holdsUplink = source.holdsUplink)
        } catch (e: Exception) {
            Log.w(TAG, "phone radio did not start on its alternate radio", e)
            frames?.close()
            runCatching { radio?.close() }
            fail(PhoneRadioFailure.OpenFailed, e.message)
        }
    }

    private fun begin(plan: RunPlan, radio: IUsbRadio, frames: ParcelFileDescriptor, usb: UsbHold?, holdsUplink: Boolean) {
        val run = RadioRun(plan, radio, frames, scope, groundId.value, lanes, Host())
        val held = Held(run, usb)
        active = held
        run.begin()
        if (holdsUplink) run.holdUplink()
        // The fleet's own ground keeps every drone's slot and relay secret in step.
        if (plan.ownsFleet) held.reconcile = scope.launch { reconciler.get().run() }
        _state.value = PhoneRadioState.Receiving(plan.bundle.channel)
        // Unplugging the radio closes nothing on its own; the run ends here,
        // outside this watch, so its cancellation does not wait on itself. A
        // radio already gone when the watch starts ends it too.
        val deviceName = usb?.deviceName ?: return
        held.unplugWatch = scope.launch {
            dongles.unplugged(deviceName).first()
            scope.launch { onDongleRemoved(held) }
        }
    }

    private inner class Host : RunHost {
        override fun retuned(channel: Int) {
            _state.value = PhoneRadioState.Receiving(channel)
        }

        override fun links(links: List<DroneLinkStatus>) {
            _links.value = links
        }

        override fun stats(stats: PhoneRadioStats) {
            _stats.value = stats
        }

        override fun uplink(state: UplinkState?) {
            _uplink.value = state
        }

        override fun video(deviceId: String, rtp: ByteArray) {
            videoFlows[deviceId]?.tryEmit(rtp)
        }

        override fun groundBeacon(beacon: GroundBeacon) {
            _groundBeacons.tryEmit(beacon)
        }

        override fun droneBeacon(deviceId: String) {
            scope.launch { fleetGround.enrol(deviceId) }
        }

        override fun keyProven() {
            scope.launch { fleetGround.markKeyProven() }
        }

        override fun readerEnded() {
            scope.launch { onReaderEnded() }
        }
    }

    private fun fail(reason: PhoneRadioFailure, detail: String? = null) {
        _state.value = PhoneRadioState.Failed(reason, detail)
    }

    private suspend fun onServiceLost() = control.withLock {
        if (active != null) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.ServiceLost))
    }

    private suspend fun onReaderEnded() = control.withLock {
        val held = active ?: return@withLock
        if (!held.run.stopping) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.RadioStopped))
    }

    private suspend fun onDongleRemoved(held: Held) = control.withLock {
        if (active === held) stopLocked(PhoneRadioState.Failed(PhoneRadioFailure.DongleRemoved))
    }

    private suspend fun stopLocked(next: PhoneRadioState) {
        val held = active
        active = null
        if (held != null) {
            held.reconcile?.cancelAndJoin()
            held.run.end()
            held.unplugWatch?.cancelAndJoin()
            withContext(Dispatchers.IO) {
                held.usb?.binding?.disconnect()
                held.usb?.usbConnection?.close()
            }
        }
        _uplink.value = null
        _stats.value = null
        _links.value = emptyList()
        _state.value = next
    }

    private fun videoFor(id: String) =
        videoFlows.getOrPut(id) { MutableSharedFlow(extraBufferCapacity = VIDEO_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    /** What a run on the USB dongle holds besides the radio: the service binding, the device and its name. */
    private class UsbHold(
        val binding: UsbRadioConnection,
        val usbConnection: UsbDeviceConnection,
        /** The USB device name of this run's radio, matched against detaches. */
        val deviceName: String,
    )

    /** The running radio and what it holds of the USB side. */
    private class Held(val run: RadioRun, val usb: UsbHold?) {
        var unplugWatch: Job? = null
        var reconcile: Job? = null
    }

    private companion object {
        const val TAG = "AdosPhoneRadio"
        const val VIDEO_BUFFER = 512
        const val RELEASE_POLL_MS = 200L
        const val RELEASE_QUIET_MS = 1_000L
        const val RELEASE_GIVE_UP_MS = 5_000L
    }
}
