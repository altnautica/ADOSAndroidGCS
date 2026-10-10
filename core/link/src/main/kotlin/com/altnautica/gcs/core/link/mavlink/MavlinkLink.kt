package com.altnautica.gcs.core.link.mavlink

import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.MavlinkSigning
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavState
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.mavlink.modes.FlightModeTables
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The autopilot this link talks to, fixed by its first qualifying HEARTBEAT. */
data class VehicleTarget(
    val sysId: Int,
    val compId: Int,
    val autopilot: Int,
    val vehicleType: Int,
) {
    val family: FirmwareFamily get() = FlightModeTables.family(autopilot, vehicleType)
}

/** A frame from the target autopilot, decoded. */
class TargetMessage(val frame: MavlinkFrame, val message: MavMessage)

/**
 * MAVLink conversation with one vehicle, independent of which transport
 * carries it. Protocol services ([com.altnautica.gcs.core.link.mission.MissionService]
 * and friends) are written against this interface.
 */
interface MavlinkLink {
    /** Every valid frame received, from any system. */
    val frames: SharedFlow<MavlinkFrame>

    /** The autopilot, or null until its first HEARTBEAT. */
    val target: StateFlow<VehicleTarget?>

    /** Encodes and writes [message] from this ground station. */
    suspend fun send(message: MavMessage): WriteResult
}

/** Decoded messages from the target autopilot only; other systems and components are ignored. */
fun MavlinkLink.targetMessages(): Flow<TargetMessage> = frames.mapNotNull { frame ->
    val t = target.value ?: return@mapNotNull null
    if (frame.sysId != t.sysId || frame.compId != t.compId) return@mapNotNull null
    frame.decode()?.let { TargetMessage(frame, it) }
}

/** Decoded messages of type [T] from the target autopilot. */
inline fun <reified T : MavMessage> MavlinkLink.messagesOf(): Flow<T> =
    targetMessages().mapNotNull { it.message as? T }

/**
 * [MavlinkLink] over a swappable [MavlinkByteTransport]: one parser and one
 * writer per attached transport, vehicle identity from the first HEARTBEAT of
 * a component that is neither a GCS nor `MAV_AUTOPILOT_INVALID`, and a 1 Hz
 * GCS heartbeat while a transport is attached.
 */
class TransportMavlinkLink(
    private val scope: CoroutineScope,
    private val clock: ElapsedClock,
) : MavlinkLink {

    private val _frames = MutableSharedFlow<MavlinkFrame>(extraBufferCapacity = 512)
    override val frames: SharedFlow<MavlinkFrame> = _frames.asSharedFlow()

    private val _target = MutableStateFlow<VehicleTarget?>(null)
    override val target: StateFlow<VehicleTarget?> = _target.asStateFlow()

    private val _transportState = MutableStateFlow<TransportState>(TransportState.Idle)

    /** State of the attached transport ([TransportState.Idle] when none). */
    val transportState: StateFlow<TransportState> = _transportState.asStateFlow()

    private val _lastFrameAtMs = MutableStateFlow<Long?>(null)

    /** Receive time of the newest frame from the target, on [clock]. */
    val lastTargetFrameAtMs: StateFlow<Long?> = _lastFrameAtMs.asStateFlow()

    private val writeLock = Mutex()
    private var transport: MavlinkByteTransport? = null
    private var writer = MavlinkFrameWriter()
    private var jobs: List<Job> = emptyList()

    /** Signing applied to every outgoing frame on the current and future transports. */
    @Volatile
    var signing: MavlinkSigning? = null
        set(value) {
            field = value
            writer.signing = value
        }

    /** Replaces the current transport. Identity is kept: the same vehicle on another path. */
    suspend fun attach(next: MavlinkByteTransport) {
        detach()
        writeLock.withLock {
            transport = next
            writer = MavlinkFrameWriter(signing = signing)
        }
        val parser = MavlinkFrameParser()
        jobs = listOf(
            scope.launch { next.state.collect { _transportState.value = it } },
            scope.launch {
                val batch = ArrayList<MavlinkFrame>(4)
                next.incoming.collect { chunk ->
                    parser.feed(chunk) { batch += it }
                    for (frame in batch) onFrame(frame)
                    batch.clear()
                }
            },
            scope.launch {
                while (isActive) {
                    if (next.state.value == TransportState.Open) send(gcsHeartbeat)
                    delay(HEARTBEAT_PERIOD_MS)
                }
            },
        )
    }

    /** Closes and forgets the current transport. */
    suspend fun detach() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        val old = writeLock.withLock { transport.also { transport = null } }
        old?.close()
        _transportState.value = TransportState.Idle
    }

    private suspend fun onFrame(frame: MavlinkFrame) {
        if (_target.value == null && frame.msgId == Heartbeat.ID) {
            val hb = Heartbeat.decode(frame.payload)
            if (hb.type != MavType.MAV_TYPE_GCS && hb.autopilot != MavAutopilot.MAV_AUTOPILOT_INVALID) {
                _target.value = VehicleTarget(frame.sysId, frame.compId, hb.autopilot, hb.type)
            }
        }
        val t = _target.value
        if (t != null && frame.sysId == t.sysId && frame.compId == t.compId) {
            _lastFrameAtMs.value = clock.nowMs()
        }
        _frames.emit(frame)
    }

    override suspend fun send(message: MavMessage): WriteResult = writeLock.withLock {
        val t = transport ?: return WriteResult.Failed("No open link")
        t.write(writer.encode(message))
    }

    /** Forgets the identified vehicle so the next HEARTBEAT re-identifies it. */
    fun resetTarget() {
        _target.value = null
    }

    companion object {
        const val HEARTBEAT_PERIOD_MS = 1000L

        private val gcsHeartbeat = Heartbeat(
            type = MavType.MAV_TYPE_GCS,
            autopilot = MavAutopilot.MAV_AUTOPILOT_INVALID,
            baseMode = 0,
            customMode = 0,
            systemStatus = MavState.MAV_STATE_ACTIVE,
            mavlinkVersion = 3,
        )
    }
}

/** Monotonic milliseconds (`SystemClock.elapsedRealtime()` in production). */
fun interface ElapsedClock {
    fun nowMs(): Long
}
