package com.altnautica.gcs.core.link.transport

import com.altnautica.gcs.core.link.demo.ArduCopterSimulator
import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlin.coroutines.CoroutineContext
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

    init {
        mutableState.value = TransportState.Open
        simulator.start()
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        if (state.value != TransportState.Open) return WriteResult.Failed("closed")
        lock.withLock { parser.feed(bytes) { simulator.handle(it) } }
        return WriteResult.Sent
    }

    override fun release() = Unit
}
