package com.altnautica.gcs.radio.rx.session

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.WfbReceiver
import com.altnautica.gcs.radio.rx.aux.AuxLanes
import com.altnautica.gcs.radio.rx.aux.LinkFeedback
import com.altnautica.gcs.radio.rx.hop.HopFollower
import com.altnautica.gcs.radio.rx.hop.PresenceBeacon
import com.altnautica.gcs.radio.rx.link.LinkParams
import com.altnautica.gcs.radio.rx.tx.UplinkRun
import com.altnautica.gcs.radio.rx.tx.UplinkState
import com.altnautica.gcs.radio.rx.usb.FrameRecord
import com.altnautica.gcs.radio.rx.usb.FrameRecordReader
import com.altnautica.gcs.radio.usb.IUsbRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What a [RadioRun] reports to the session that owns it. */
internal interface RunHost {
    fun retuned(channel: Int)
    fun links(links: List<DroneLinkStatus>)
    fun stats(stats: PhoneRadioStats)
    fun uplink(state: UplinkState?)
    fun video(deviceId: String, rtp: ByteArray)
    fun groundBeacon(beacon: GroundBeacon)

    /** A drone of the fleet this phone owns beaconed; it is enrolled. */
    fun droneBeacon(deviceId: String)

    /** The fleet key this phone owns decoded a drone's traffic for the first time. */
    fun keyProven()
    fun readerEnded()
}

/**
 * One run of the phone radio, from radio open to close: a receiver per
 * drone slot plus one on the ground link for other grounds' beacons, the hop
 * follower, the aux lanes, the per-second statistics and link feedback, and,
 * while the phone holds the uplink, the ground transmitters ([UplinkRun]).
 */
internal class RadioRun(
    private val plan: RunPlan,
    private val radio: IUsbRadio,
    private val frames: ParcelFileDescriptor,
    private val scope: CoroutineScope,
    private val groundId: String,
    private val lanes: AuxLanes,
    private val host: RunHost,
) {
    @Volatile var stopping = false
    private val bundle = plan.bundle
    private val ports = bundle.radioPorts
    private val hopKey = bundle.hopKey
    private val groundLink = LinkParams.linkId(bundle.fleetId, LinkParams.SLOT_GROUND)
    private val slotOfLink = plan.slots.associateBy { LinkParams.linkId(bundle.fleetId, it) }
    private val deviceBySlot = ConcurrentHashMap(plan.knownDevices)
    private val lastPacket = ConcurrentHashMap<String, Long>()
    private val keyProven = AtomicBoolean(plan.keyProven)
    @Volatile private var channel = bundle.channel
    @Volatile private var lastRetuneMs = SystemClock.elapsedRealtime()
    private val startedMs = SystemClock.elapsedRealtime()
    private val signal = SignalMeter()
    private val videoBytes = ConcurrentHashMap<String, Long>()
    private val telemetryPackets = AtomicInteger()

    private val follower = HopFollower(hopKey, bundle.channel, scope) { target -> retune(target) }
    @Volatile private var uplinkRun: UplinkRun? = null
    private var hopsAtLastTick = 0
    private var reader: Thread? = null
    private var ticker: Job? = null
    private var lastUsb: LongArray? = null
    private val receivers = HashMap<Int, WfbReceiver>()

    fun begin() {
        for ((link, slot) in slotOfLink) {
            receivers[link] = WfbReceiver(bundle.rxKey, link, ports.asArray()) { port, payload -> onDatagram(slot, port, payload) }
        }
        receivers[groundLink] = WfbReceiver(bundle.rxKey, groundLink, intArrayOf(ports.control)) { port, payload ->
            if (port == ports.control) onGroundControl(payload)
        }
        reader = Thread({ readLoop() }, "phone-radio-rx").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
        if (plan.ownsFleet) holdUplink()
    }

    /** Starts the ground transmitters on this run; true while transmitting. Idempotent. */
    @Synchronized
    fun holdUplink(): Boolean {
        if (uplinkRun != null) return true
        val run = UplinkRun(radio, bundle, groundId, scope, { channel }) { host.uplink(it) }
        if (!run.begin()) return false
        uplinkRun = run
        follower.ack = run::ack
        return true
    }

    /** Closes the ground transmitters; the uplink reads null afterwards. Idempotent. */
    @Synchronized
    fun dropUplink() {
        follower.ack = null
        uplinkRun?.close()
        uplinkRun = null
        host.uplink(null)
    }

    /** Frames the radio has put on the air so far, or null when it cannot say. */
    fun framesSent(): Long? = runCatching { radio.txStats()[1] }.getOrNull()

    fun sendAux(frame: ByteArray): Boolean = uplinkRun?.sendAux(frame) ?: false

    fun hears(deviceId: String): Boolean = lastPacket.containsKey(deviceId)

    private fun readLoop() {
        try {
            FileInputStream(frames.fileDescriptor).use { input ->
                val records = FrameRecordReader(input)
                while (true) {
                    val len = records.next()
                    if (len < 0) break
                    val body = records.buffer
                    val link = FrameRecord.linkId(body, len) ?: continue
                    FrameRecord.bestRssi(body)?.let { rssi -> signal.add(link, rssi, FrameRecord.bestSnr(body) ?: 0) }
                    receivers[link]?.feed(body, len)
                }
            }
        } catch (e: IOException) {
            if (!stopping) Log.w(TAG, "frame pipe ended", e)
        } finally {
            receivers.values.forEach { it.close() }
            if (!stopping) host.readerEnded()
        }
    }

    private fun onDatagram(slot: Int, port: Int, payload: ByteArray) {
        if (plan.ownsFleet && keyProven.compareAndSet(false, true)) host.keyProven()
        if (port == ports.control) {
            follower.onControlPacket(payload)
            if (plan.ownsFleet) learnDrone(slot, payload)
        }
        val deviceId = deviceBySlot[slot] ?: return
        lastPacket[deviceId] = SystemClock.elapsedRealtime()
        when (port) {
            ports.video -> {
                videoBytes.merge(deviceId, payload.size.toLong(), Long::plus)
                host.video(deviceId, payload)
            }
            ports.auxDown -> if (lanes.onAuxDown(deviceId, payload)) telemetryPackets.incrementAndGet()
        }
    }

    /** A drone's beacon names the drone transmitting on [slot]; the fleet this phone owns enrols it. */
    private fun learnDrone(slot: Int, payload: ByteArray) {
        val beacon = PresenceBeacon.parse(payload, hopKey) ?: return
        if (beacon.role != PresenceBeacon.ROLE_DRONE) return
        if (deviceBySlot.put(slot, beacon.deviceId) != beacon.deviceId) host.droneBeacon(beacon.deviceId)
    }

    private fun onGroundControl(payload: ByteArray) {
        val beacon = PresenceBeacon.parse(payload, hopKey) ?: return
        if (beacon.role == PresenceBeacon.ROLE_GROUND && beacon.deviceId != groundId) {
            host.groundBeacon(GroundBeacon(beacon.deviceId, beacon.channel))
        }
    }

    private suspend fun retune(target: Int): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching { radio.setChannel(target, bundle.bandwidthMhz) }
            .onFailure { Log.w(TAG, "retune to $target failed", it) }
            .isSuccess
        if (ok) {
            channel = target
            lastRetuneMs = SystemClock.elapsedRealtime()
            if (!stopping) host.retuned(target)
        }
        ok
    }

    private suspend fun tick() {
        val now = SystemClock.elapsedRealtime()
        host.links(deviceBySlot.entries.sortedBy { it.key }.map { (slot, id) -> DroneLinkStatus(DeviceId(id), slot, lastPacket[id]) })

        // A drone that hopped while the phone missed the announcement is
        // found again on the rendezvous channel, then back on the invite's.
        val lastHeard = lastPacket.values.maxOrNull() ?: startedMs
        if (now - lastHeard > SILENCE_MS && now - lastRetuneMs > SILENCE_MS) {
            val other = if (channel == bundle.channel) bundle.rendezvousChannel else bundle.channel
            if (other != channel) {
                if (retune(other)) follower.onRetunedElsewhere(other) else lastRetuneMs = now
            }
        }

        // A hop this phone acknowledged that the drone carried out (its
        // downlink came back after the retune) is the drone acting on the
        // phone's own transmission.
        val hops = follower.hopsFollowed
        if (hops > hopsAtLastTick && lastHeard > lastRetuneMs) {
            hopsAtLastTick = hops
            uplinkRun?.evidence()
        }
        report()
    }

    private fun report() {
        var video = 0L
        var recovered = 0L
        var lost = 0L
        var decryptErrors = 0L
        val feedback = ArrayList<LinkFeedback>()
        for ((link, receiver) in receivers) {
            val slot = slotOfLink[link] ?: continue
            var delivered = 0L
            var videoLost = 0L
            for (p in receiver.stats().ports) {
                if (p.port == ports.video) {
                    video += p.delivered
                    delivered = p.dataPackets
                    videoLost = p.lost
                }
                recovered += p.fecRecovered
                lost += p.lost
                decryptErrors += p.decryptErrors
            }
            val deviceId = deviceBySlot[slot] ?: continue
            val (rssi, snr) = signal.take(link)
            feedback += LinkFeedback.of(delivered, videoLost, rssi, snr, videoBytes.remove(deviceId) ?: 0L, slot)
        }
        if (uplinkRun != null) feedback.forEach { lanes.sendFeedback(it) }
        val usb = runCatching { radio.stats() }.getOrNull()
        val prev = lastUsb
        lastUsb = usb
        val (rssi, snr) = signal.takeAll()
        host.stats(
            PhoneRadioStats(
                channel = channel,
                bandwidthMhz = bundle.bandwidthMhz,
                rssiDbm = rssi,
                snrDb = snr,
                videoPacketsPerSec = video,
                telemetryPacketsPerSec = telemetryPackets.getAndSet(0).toLong(),
                fecRecoveredPerSec = recovered,
                lostPerSec = lost,
                decryptErrorsPerSec = decryptErrors,
                usbFramesPerSec = if (usb != null && prev != null) usb[2] - prev[2] else 0L,
                usbDroppedPerSec = if (usb != null && prev != null) usb[3] - prev[3] else 0L,
                hopsFollowed = follower.hopsFollowed,
            ),
        )
    }

    suspend fun end() {
        stopping = true
        dropUplink()
        follower.cancel()
        ticker?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            // Closing the radio closes the pipe's write end; the reader
            // then sees end of stream and releases the receivers.
            runCatching { radio.close() }
            reader?.join(READER_JOIN_MS)
            runCatching { frames.close() }
        }
    }

    private companion object {
        const val TAG = "AdosPhoneRadio"
        const val TICK_MS = 1_000L
        const val SILENCE_MS = 25_000L
        const val READER_JOIN_MS = 2_000L
    }
}

/** Signal strength per link and overall, averaged over each second. */
private class SignalMeter {
    private class Sum {
        var rssi = 0L
        var snr = 0L
        var n = 0L
    }

    private val perLink = HashMap<Int, Sum>()
    private val all = Sum()

    @Synchronized
    fun add(link: Int, rssi: Int, snr: Int) {
        for (s in listOf(perLink.getOrPut(link) { Sum() }, all)) {
            s.rssi += rssi
            s.snr += snr
            s.n++
        }
    }

    @Synchronized
    fun take(link: Int): Pair<Int?, Int?> = perLink.remove(link).avg()

    @Synchronized
    fun takeAll(): Pair<Int?, Int?> = all.avg().also {
        all.rssi = 0
        all.snr = 0
        all.n = 0
        perLink.clear()
    }

    private fun Sum?.avg(): Pair<Int?, Int?> =
        if (this == null || n == 0L) null to null else (rssi / n).toInt() to (snr / n).toInt()
}
