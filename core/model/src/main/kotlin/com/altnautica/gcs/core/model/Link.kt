package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Raw MAVLink byte pipe for one link. Framing happens above this layer. */
interface MavlinkByteTransport {
    val state: StateFlow<TransportState>
    val incoming: Flow<ByteArray>
    suspend fun write(bytes: ByteArray): WriteResult
    suspend fun close()
}

sealed interface TransportState {
    data object Idle : TransportState
    data object Connecting : TransportState
    data object Open : TransportState
    data class Closed(val reason: String?) : TransportState
    data class Failed(val message: String) : TransportState
}

sealed interface WriteResult {
    data object Sent : WriteResult
    data object ReceiveOnly : WriteResult
    data object NotAuthorized : WriteResult
    data class Failed(val message: String) : WriteResult
}

/** Opens a transport for the reaches it supports; bound into a Hilt set. */
interface TransportProvider {
    fun supports(reach: Reach): Boolean
    suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport
}
