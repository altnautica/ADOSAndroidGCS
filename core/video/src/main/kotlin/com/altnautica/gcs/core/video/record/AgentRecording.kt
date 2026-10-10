package com.altnautica.gcs.core.video.record

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.video.RecordingState
import com.altnautica.gcs.core.video.VideoRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Builds agent-side recording for WHEP sources. */
@Singleton
class AgentRecordingProvider @Inject constructor(
    private val clients: AgentClientFactory,
) {
    fun forSource(source: VideoSource.AgentWhep): VideoRecording =
        AgentRecording(source.node, { clients.forNode(it) })
}

/**
 * Recording happens on the agent that serves the WHEP stream: a ground
 * station records under the `/api/v1/ground-station/recording` routes, any other node
 * under the `/api/video/record` routes. The ground-station route is tried first; a
 * profile mismatch means the node is not a ground station.
 */
class AgentRecording(
    private val node: DeviceId,
    private val client: suspend (DeviceId) -> AgentClient?,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : VideoRecording {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state
    private var groundStation: Boolean? = null

    override suspend fun start(): Result<Unit> {
        val c = client(node) ?: return fail("Node is not paired")
        var result = if (groundStation != false) c.groundStation.startRecording() else null
        if (result is AgentResult.Err && result.error.isWrongProfile()) {
            groundStation = false
            result = null
        } else if (result is AgentResult.Ok) {
            groundStation = true
        }
        val r = result ?: c.startVideoRecording()
        return when (r) {
            is AgentResult.Ok -> {
                val started = runCatching { Instant.parse(r.value.startedAt).toEpochMilli() }.getOrElse { wallClock() }
                _state.value = RecordingState.Recording(started, r.value.filename)
                Result.success(Unit)
            }
            is AgentResult.Err -> fail(r.error.toString())
        }
    }

    override suspend fun stop(): Result<Unit> {
        val c = client(node) ?: return fail("Node is not paired")
        val r = if (groundStation == true) c.groundStation.stopRecording() else c.stopVideoRecording()
        return when (r) {
            is AgentResult.Ok -> {
                _state.value = RecordingState.Idle
                Result.success(Unit)
            }
            is AgentResult.Err -> fail(r.error.toString())
        }
    }

    private fun fail(reason: String): Result<Unit> {
        _state.value = RecordingState.Failed(reason)
        return Result.failure(IllegalStateException(reason))
    }

    private fun AgentError.isWrongProfile() = this == AgentError.ProfileMismatch || this == AgentError.FeatureAbsent
}
