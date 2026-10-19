package com.altnautica.gcs.core.agent.capture

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonElement

/**
 * Capture session routes served by the World Engine extension. An interface so
 * screens can be driven by the agent or by demo data alike.
 */
interface CaptureApi {
    suspend fun state(): AgentResult<CaptureState>
    suspend fun start(request: CaptureStartRequest): AgentResult<CaptureStartResponse>
    suspend fun stop(): AgentResult<JsonElement>
    suspend fun pause(): AgentResult<JsonElement>
    suspend fun resume(): AgentResult<JsonElement>
    suspend fun mark(room: String, floor: Int): AgentResult<CaptureMarkResponse>
    suspend fun exposure(exposure: CaptureExposure): AgentResult<CaptureExposure>
    suspend fun sessions(): AgentResult<CaptureSessions>
    suspend fun upload(sessionId: String): AgentResult<JsonElement>

    /** Deletes a session; the agent refuses one not yet uploaded (`E_NOT_UPLOADED`) unless [force]. */
    suspend fun delete(sessionId: String, force: Boolean): AgentResult<JsonElement>
    suspend fun reconstruct(sessionId: String, preset: String): AgentResult<ReconstructResponse>
}

/** [CaptureApi] over the extension's plugin route mount (`/api/plugins/{id}/x/...`). */
class AgentCaptureApi internal constructor(private val client: AgentClient) : CaptureApi {

    override suspend fun state() = client.get(route("atlas/capture/state"), CaptureState.serializer())

    override suspend fun start(request: CaptureStartRequest) = client.send(
        "POST",
        route("atlas/capture/start"),
        CaptureStartRequest.serializer(),
        request,
        CaptureStartResponse.serializer(),
    )

    override suspend fun stop() = post("atlas/capture/stop")

    override suspend fun pause() = post("atlas/capture/pause")

    override suspend fun resume() = post("atlas/capture/resume")

    override suspend fun mark(room: String, floor: Int) = client.send(
        "POST",
        route("atlas/capture/mark"),
        CaptureMarkRequest.serializer(),
        CaptureMarkRequest(room, floor),
        CaptureMarkResponse.serializer(),
    )

    override suspend fun exposure(exposure: CaptureExposure) = client.send(
        "PUT",
        route("atlas/capture/exposure"),
        CaptureExposure.serializer(),
        exposure,
        CaptureExposure.serializer(),
    )

    override suspend fun sessions() = client.get(route("atlas/sessions"), CaptureSessions.serializer())

    override suspend fun upload(sessionId: String) = post("atlas/sessions/${seg(sessionId)}/upload")

    override suspend fun delete(sessionId: String, force: Boolean) = client.sendJson(
        "DELETE",
        route("atlas/sessions/${seg(sessionId)}"),
        null,
        JsonElement.serializer(),
        query = if (force) mapOf("force" to "true") else emptyMap(),
    )

    override suspend fun reconstruct(sessionId: String, preset: String) = client.send(
        "POST",
        route("atlas/sessions/${seg(sessionId)}/reconstruct"),
        ReconstructRequest.serializer(),
        ReconstructRequest(preset),
        ReconstructResponse.serializer(),
    )

    private suspend fun post(path: String) = client.sendJson("POST", route(path), null, JsonElement.serializer())

    private fun seg(value: String) = AgentClient.segment(value)

    companion object {
        const val PLUGIN_ID = "com.altnautica.world-engine"

        /** The extension route [path] under the agent's plugin mount. */
        fun route(path: String): String = "api/plugins/${AgentClient.segment(PLUGIN_ID)}/x/$path"
    }
}
