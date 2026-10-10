package com.altnautica.gcs.core.link.transport

import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.coroutines.CoroutineContext

/**
 * Shared plumbing for byte transports: a state flow, a single-consumer
 * incoming queue (oldest chunks dropped if the reader stalls), and an owned
 * scope cancelled on close.
 */
abstract class ChannelTransport(context: CoroutineContext) : MavlinkByteTransport {

    protected val scope = CoroutineScope(context + SupervisorJob(context[kotlinx.coroutines.Job]))

    private val inbound = Channel<ByteArray>(INBOUND_CAPACITY, BufferOverflow.DROP_OLDEST)
    final override val incoming: Flow<ByteArray> = inbound.receiveAsFlow()

    protected val mutableState = MutableStateFlow<TransportState>(TransportState.Idle)
    final override val state: StateFlow<TransportState> = mutableState.asStateFlow()

    protected fun deliver(bytes: ByteArray) {
        inbound.trySend(bytes)
    }

    /** Marks the transport ended and releases it; idempotent. */
    protected fun finish(terminal: TransportState) {
        val s = mutableState.value
        if (s is TransportState.Closed || s is TransportState.Failed) return
        mutableState.value = terminal
        release()
        scope.cancel()
    }

    /** Releases sockets, ports and threads. */
    protected abstract fun release()

    override suspend fun close() = finish(TransportState.Closed(null))

    private companion object {
        const val INBOUND_CAPACITY = 1024
    }
}
