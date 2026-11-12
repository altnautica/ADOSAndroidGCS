package com.altnautica.gcs.core.link.transport

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator
import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.messages.CommandInt
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import kotlin.math.roundToInt
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory link to an [ArduCopterSimulator]: writes are parsed and handled, its frames are delivered. */
class DemoTransport(
    context: CoroutineContext,
    options: DemoOptions = DemoOptions(),
) : ChannelTransport(context) {

    private val parser = MavlinkFrameParser()
    private val lock = Mutex()

    /** The simulated vehicle (exposed for tests and diagnostics). */
    val simulator = ArduCopterSimulator(scope, options) { deliver(it) }

    /** Frames from the simulated onboard computer, as a companion on the vehicle's own bus. */
    private val onboard = MavlinkFrameWriter(sysId = ArduCopterSimulator.SYS_ID, compId = COMPANION_COMP_ID)

    /** The onboard computer's commands, delivered in the order it sent them. */
    private val onboardQueue = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        mutableState.value = TransportState.Open
        simulator.start()
        scope.launch { for (bytes in onboardQueue) write(bytes) }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        if (state.value != TransportState.Open) return WriteResult.Failed("closed")
        lock.withLock { parser.feed(bytes) { simulator.handle(it) } }
        return WriteResult.Sent
    }

    /**
     * Sends COMMAND_LONG [command] to the simulated flight controller from the onboard
     * computer (component 191), the way the vehicle's own pilot commands it. Its
     * COMMAND_ACK reaches this link like any other frame.
     */
    fun companionCommand(command: Int, p1: Float = 0f, p2: Float = 0f, p7: Float = 0f) {
        val bytes = onboard.encode(
            CommandLong(
                targetSystem = ArduCopterSimulator.SYS_ID,
                targetComponent = ArduCopterSimulator.COMP_ID,
                command = command,
                param1 = p1,
                param2 = p2,
                param7 = p7,
            ),
        )
        onboardQueue.trySend(bytes)
    }

    /**
     * Sends COMMAND_INT [command] with a global position ([latDeg], [lonDeg], [altM] above
     * home) from the onboard computer, queued after its earlier commands.
     */
    fun companionCommandInt(command: Int, latDeg: Double, lonDeg: Double, altM: Float, p1: Float = 0f, p2: Float = 0f, p4: Float = 0f) {
        val bytes = onboard.encode(
            CommandInt(
                targetSystem = ArduCopterSimulator.SYS_ID,
                targetComponent = ArduCopterSimulator.COMP_ID,
                frame = MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                command = command,
                param1 = p1,
                param2 = p2,
                param4 = p4,
                x = (latDeg * 1e7).roundToInt(),
                y = (lonDeg * 1e7).roundToInt(),
                z = altM,
            ),
        )
        onboardQueue.trySend(bytes)
    }

    override fun release() = Unit

    private companion object {
        /** MAV_COMP_ID_ONBOARD_COMPUTER. */
        const val COMPANION_COMP_ID = 191
    }
}
