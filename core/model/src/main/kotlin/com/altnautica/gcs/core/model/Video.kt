package com.altnautica.gcs.core.model

sealed interface VideoSource {
    data class AgentWhep(val node: DeviceId, val baseUrl: String, val streamId: String?) : VideoSource
    data class CloudP2p(val node: DeviceId) : VideoSource
    data class CloudRelay(val node: DeviceId) : VideoSource
    data class PhoneRadio(val node: DeviceId) : VideoSource
}

sealed interface VideoState {
    data object Idle : VideoState
    data object Connecting : VideoState
    data class Live(val latencyMs: Int?) : VideoState
    data class Frozen(val sinceMs: Long) : VideoState
    data class Failed(val reason: String) : VideoState
    data object NoSource : VideoState
}

/** Concurrent legs stream at once; switchable legs share one encoder. */
enum class StreamSwitchKind { Concurrent, Switchable }

data class StreamDescriptor(
    val id: String,
    val role: String,
    val codec: String,
    val live: Boolean,
    val switchKind: StreamSwitchKind,
)
