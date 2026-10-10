package com.altnautica.gcs.core.agent

import com.altnautica.gcs.core.agent.groundstation.GroundStationApi
import com.altnautica.gcs.core.agent.groundstation.RecordingStartRequest
import com.altnautica.gcs.core.agent.groundstation.RecordingStartResponse
import com.altnautica.gcs.core.agent.groundstation.RecordingStopResponse
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentJson
import com.altnautica.gcs.core.agent.http.AgentResponse
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.http.flatMap
import com.altnautica.gcs.core.agent.model.AgentVersion
import com.altnautica.gcs.core.agent.model.BatteryHealth
import com.altnautica.gcs.core.agent.model.CommandRequest
import com.altnautica.gcs.core.agent.model.CommandResponse
import com.altnautica.gcs.core.agent.model.ConfigWrite
import com.altnautica.gcs.core.agent.model.PinStatus
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.agent.model.WsTicket
import com.altnautica.gcs.core.agent.pairing.ClaimRequest
import com.altnautica.gcs.core.agent.pairing.ClaimResponse
import com.altnautica.gcs.core.agent.pairing.PairingInfo
import com.altnautica.gcs.core.agent.pairing.UnpairResponse
import com.altnautica.gcs.core.agent.plugins.PluginApi
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * REST client for one agent [endpoint]. Obtain instances from
 * [AgentClientFactory]; a client is cheap and holds no connection state.
 *
 * Every call returns an [AgentResult]; nothing throws for an agent-side
 * refusal or an unreachable node.
 */
class AgentClient internal constructor(
    val endpoint: AgentEndpoint,
    internal val http: AgentHttp,
    private val versionCache: VersionCache,
) {

    val deviceId: DeviceId? get() = endpoint.deviceId

    /** Ground-station routes (`/api/v1/ground-station/...`). */
    val groundStation: GroundStationApi = GroundStationApi(this)

    /** Extension routes (`/api/plugins...`). */
    val plugins: PluginApi = PluginApi(this)

    // ---- identity and status -------------------------------------------------

    /** `GET /api/version`, cached for five minutes per endpoint. */
    suspend fun version(): AgentResult<AgentVersion> {
        versionCache.get(endpoint)?.let { return AgentResult.Ok(it) }
        return get("api/version", AgentVersion.serializer()).also { result ->
            (result as? AgentResult.Ok)?.let { versionCache.put(endpoint, it.value) }
        }
    }

    suspend fun status(): AgentResult<JsonObject> = get("api/status", JsonObject.serializer())

    suspend fun statusFull(): AgentResult<StatusFull> = get("api/status/full", StatusFull.serializer())

    suspend fun telemetry(): AgentResult<TelemetrySnapshot> = get("api/telemetry", TelemetrySnapshot.serializer())

    suspend fun system(): AgentResult<JsonObject> = get("api/system", JsonObject.serializer())

    suspend fun services(): AgentResult<JsonElement> = get("api/services", JsonElement.serializer())

    suspend fun restartService(name: String, force: Boolean = false): AgentResult<JsonElement> =
        sendJson("POST", "api/services/${segment(name)}/restart", forceBody(force), JsonElement.serializer())

    suspend fun restartSupervisor(force: Boolean = false): AgentResult<JsonElement> =
        sendJson("POST", "api/v1/system/restart-supervisor", forceBody(force), JsonElement.serializer())

    suspend fun battery(): AgentResult<BatteryHealth> = get("api/v1/battery", BatteryHealth.serializer())

    suspend fun logs(query: Map<String, String?> = emptyMap()): AgentResult<JsonElement> =
        get("api/logs", JsonElement.serializer(), query)

    suspend fun wfb(): AgentResult<JsonObject> = get("api/wfb", JsonObject.serializer())

    // ---- configuration -------------------------------------------------------

    suspend fun config(): AgentResult<JsonObject> = get("api/config", JsonObject.serializer())

    suspend fun configSchema(): AgentResult<JsonObject> = get("api/config/schema", JsonObject.serializer())

    /** `PUT /api/config {key, value}`; [force] overrides the armed interlock. */
    suspend fun putConfig(key: String, value: JsonElement, force: Boolean = false): AgentResult<JsonElement> =
        send("PUT", "api/config", ConfigWrite.serializer(), ConfigWrite(key, value, force.takeIf { it }), JsonElement.serializer())

    // ---- commands ------------------------------------------------------------

    /** `POST /api/command {cmd, args}`. A 2xx with `status:"error"` is still Ok; read [CommandResponse.accepted]. */
    suspend fun command(cmd: String, args: List<JsonElement> = emptyList()): AgentResult<CommandResponse> =
        send("POST", "api/command", CommandRequest.serializer(), CommandRequest(cmd, args), CommandResponse.serializer())

    // ---- video ---------------------------------------------------------------

    /** `GET /api/video/roster`: reconciled camera rows, raw so a write can round-trip every field. */
    suspend fun cameraRoster(): AgentResult<List<JsonObject>> =
        get("api/video/roster", RosterBody.serializer()).let { r ->
            when (r) {
                is AgentResult.Ok -> AgentResult.Ok(r.value.cameras)
                is AgentResult.Err -> r
            }
        }

    /** `PUT /api/video/roster {cameras}`: the agent restarts its pipeline (~3 s). */
    suspend fun putCameraRoster(cameras: List<JsonObject>): AgentResult<JsonElement> =
        send("PUT", "api/video/roster", RosterBody.serializer(), RosterBody(cameras), JsonElement.serializer(), slow = true)

    suspend fun videoConfig(): AgentResult<JsonObject> = get("api/video/config", JsonObject.serializer())

    suspend fun startVideoRecording(filenameHint: String? = null): AgentResult<RecordingStartResponse> =
        send(
            "POST",
            "api/video/record/start",
            RecordingStartRequest.serializer(),
            RecordingStartRequest(filenameHint),
            RecordingStartResponse.serializer(),
        )

    suspend fun stopVideoRecording(): AgentResult<RecordingStopResponse> =
        sendJson("POST", "api/video/record/stop", null, RecordingStopResponse.serializer())

    // ---- pairing and access --------------------------------------------------

    suspend fun pairingInfo(): AgentResult<PairingInfo> = get("api/pairing/info", PairingInfo.serializer())

    suspend fun claim(userId: String): AgentResult<ClaimResponse> =
        send("POST", "api/pairing/claim", ClaimRequest.serializer(), ClaimRequest(userId), ClaimResponse.serializer())

    suspend fun unpair(): AgentResult<UnpairResponse> =
        sendJson("POST", "api/pairing/unpair", null, UnpairResponse.serializer())

    suspend fun pinStatus(): AgentResult<PinStatus> = get("api/dashboard/pin/status", PinStatus.serializer())

    /** Mint a one-shot WebSocket ticket for [scope] (e.g. `vision.detections`). */
    suspend fun wsTicket(scope: String): AgentResult<WsTicket> =
        sendJson("POST", "api/_ws/ticket", buildJsonObject { put("scope", scope) }, WsTicket.serializer())

    // ---- generic -------------------------------------------------------------

    /**
     * Any JSON route not modelled above (agent settings sections, mesh, network,
     * fleet). [path] is relative to the agent root, e.g. `api/v1/ground-station/mesh`.
     */
    suspend fun requestJson(
        method: String,
        path: String,
        body: JsonElement? = null,
        query: Map<String, String?> = emptyMap(),
    ): AgentResult<JsonElement> = sendJson(method, path, body, JsonElement.serializer(), query)

    /** Raw bytes of a GET (extension bundle assets). */
    suspend fun bytes(path: String): AgentResult<AgentResponse> = http.execute(endpoint, "GET", path)

    // ---- plumbing ------------------------------------------------------------

    internal suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
    ): AgentResult<T> = http.execute(endpoint, "GET", path, query).flatMap { decode(it, deserializer) }

    internal suspend fun <B, T> send(
        method: String,
        path: String,
        bodySerializer: SerializationStrategy<B>,
        body: B,
        deserializer: DeserializationStrategy<T>,
        slow: Boolean = false,
    ): AgentResult<T> = http.execute(
        endpoint,
        method,
        path,
        jsonBody = AgentJson.json.encodeToString(bodySerializer, body),
        slowCall = slow,
    ).flatMap { decode(it, deserializer) }

    internal suspend fun <T> sendJson(
        method: String,
        path: String,
        body: JsonElement?,
        deserializer: DeserializationStrategy<T>,
        query: Map<String, String?> = emptyMap(),
        slow: Boolean = false,
    ): AgentResult<T> = http.execute(
        endpoint,
        method,
        path,
        query,
        jsonBody = body?.let { AgentJson.json.encodeToString(JsonElement.serializer(), it) },
        slowCall = slow,
    ).flatMap { decode(it, deserializer) }

    internal fun <T> decode(response: AgentResponse, deserializer: DeserializationStrategy<T>): AgentResult<T> {
        val text = response.text.ifBlank { "{}" }
        return try {
            AgentResult.Ok(AgentJson.json.decodeFromString(deserializer, text))
        } catch (e: IllegalArgumentException) {
            AgentResult.Err(AgentError.Server(response.code, "unreadable agent response: ${e.message}"))
        }
    }

    @Serializable
    private data class RosterBody(val cameras: List<JsonObject> = emptyList())

    internal companion object {
        fun forceBody(force: Boolean): JsonElement? =
            if (force) buildJsonObject { put("force", JsonPrimitive(true)) } else null

        fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
