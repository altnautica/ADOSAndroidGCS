package com.altnautica.gcs.core.agent.plugins

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResponse
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.PluginConfigWrite
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Extension routes. Manifest-shaped payloads stay raw JSON: the extensions
 * layer parses the `gcs` contribution block itself.
 */
class PluginApi internal constructor(private val client: AgentClient) {

    /** `GET /api/plugins`: installed extensions. */
    suspend fun list(): AgentResult<JsonElement> = client.get("api/plugins", JsonElement.serializer())

    /** `GET /api/plugins/{id}`: detail incl. manifest, `gcs` block and `granted_capabilities`. */
    suspend fun detail(pluginId: String): AgentResult<JsonObject> =
        client.get("api/plugins/${seg(pluginId)}", JsonObject.serializer())

    /** `GET /api/plugins/{id}/gcs/{path}`: a bundle asset as bytes. */
    suspend fun gcsAsset(pluginId: String, assetPath: String): AgentResult<AgentResponse> {
        val encoded = assetPath.trimStart('/').split('/').joinToString("/") { AgentClient.segment(it) }
        return client.bytes("api/plugins/${seg(pluginId)}/gcs/$encoded")
    }

    suspend fun config(pluginId: String): AgentResult<JsonObject> =
        client.get("api/plugins/${seg(pluginId)}/config", JsonObject.serializer())

    /** `PUT /api/plugins/{id}/config {key, value}`; the agent validates the value. */
    suspend fun putConfig(pluginId: String, key: String, value: JsonElement): AgentResult<JsonElement> =
        client.send(
            "PUT",
            "api/plugins/${seg(pluginId)}/config",
            PluginConfigWrite.serializer(),
            PluginConfigWrite(key, value),
            JsonElement.serializer(),
        )

    suspend fun state(pluginId: String): AgentResult<JsonObject> =
        client.get("api/plugins/${seg(pluginId)}/state", JsonObject.serializer())

    suspend fun enable(pluginId: String): AgentResult<JsonElement> = post("api/plugins/${seg(pluginId)}/enable")

    suspend fun disable(pluginId: String): AgentResult<JsonElement> = post("api/plugins/${seg(pluginId)}/disable")

    suspend fun grant(pluginId: String, permissionId: String): AgentResult<JsonElement> =
        client.sendJson(
            "POST",
            "api/plugins/${seg(pluginId)}/grant",
            buildJsonObject { put("permission_id", permissionId) },
            JsonElement.serializer(),
        )

    suspend fun revoke(pluginId: String, permissionId: String): AgentResult<JsonElement> =
        client.sendJson("DELETE", "api/plugins/${seg(pluginId)}/perms/${seg(permissionId)}", null, JsonElement.serializer())

    suspend fun uninstall(pluginId: String): AgentResult<JsonElement> =
        client.sendJson("DELETE", "api/plugins/${seg(pluginId)}", null, JsonElement.serializer(), slow = true)

    /** `GET /api/v1/plugins/catalog`. */
    suspend fun catalog(): AgentResult<JsonElement> = client.get("api/v1/plugins/catalog", JsonElement.serializer())

    /** `POST /api/plugins/install_builtin {plugin_id}`. */
    suspend fun installBuiltin(pluginId: String): AgentResult<JsonObject> =
        client.sendJson(
            "POST",
            "api/plugins/install_builtin",
            buildJsonObject { put("plugin_id", pluginId) },
            JsonObject.serializer(),
            slow = true,
        )

    private suspend fun post(path: String): AgentResult<JsonElement> =
        client.sendJson("POST", path, null, JsonElement.serializer())

    private fun seg(value: String) = AgentClient.segment(value)

    companion object {
        /** WebSocket path of an install job's progress stream (open with [com.altnautica.gcs.core.agent.ws.AgentSockets]). */
        fun installJobPath(jobId: String): String = "api/plugins/jobs/${AgentClient.segment(jobId)}"

        /** WebSocket path of the vision detection stream (needs a `vision.detections` ticket). */
        const val DETECTIONS_WS_PATH = "api/vision/detections/ws"
        const val DETECTIONS_TICKET_SCOPE = "vision.detections"
    }
}
