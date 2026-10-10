package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.json.JsonElement
import com.altnautica.gcs.core.agent.plugins.PluginApi
import com.altnautica.gcs.core.agent.ws.AgentSockets
import com.altnautica.gcs.core.agent.ws.WsFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** A failed extension call, reduced to what the UI shows. */
sealed interface ExtensionFailure {
    data object Unauthorized : ExtensionFailure
    data object PinRequired : ExtensionFailure
    data object FeatureAbsent : ExtensionFailure
    data class Network(val message: String?) : ExtensionFailure
    data class Refused(val message: String?) : ExtensionFailure

    companion object {
        fun from(e: AgentError): ExtensionFailure = when (e) {
            AgentError.Unauthorized -> Unauthorized
            is AgentError.PinRequired -> PinRequired
            AgentError.FeatureAbsent, AgentError.ProfileMismatch -> FeatureAbsent
            is AgentError.Network -> Network(e.cause.message)
            is AgentError.PeerApi -> Network(e.message ?: e.code)
            is AgentError.RelayTicket -> Refused(e.reason)
            is AgentError.Armed -> Refused("vehicle is armed")
            is AgentError.Server -> Refused(e.message ?: "HTTP ${e.code}")
        }
    }
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val failure: ExtensionFailure) : ApiResult<Nothing>
}

fun <T> AgentResult<T>.toApi(): ApiResult<T> = when (this) {
    is AgentResult.Ok -> ApiResult.Ok(value)
    is AgentResult.Err -> ApiResult.Err(ExtensionFailure.from(error))
}

data class PerceptionInfo(val tier: String?, val npuTops: Double?)

/** One camera of the node's video roster, as a camera parameter offers it. */
data class RosterCamera(val id: String, val name: String?, val purposes: List<String>, val enabled: Boolean)

/** The extension routes of one node's agent (direct or through a ground-station relay). */
interface ExtensionApi {
    suspend fun list(): ApiResult<JsonElement>
    suspend fun detail(pluginId: String): ApiResult<JsonObject>
    suspend fun gcsAsset(pluginId: String, path: String): ApiResult<ByteArray>
    suspend fun config(pluginId: String): ApiResult<JsonObject>
    suspend fun putConfig(pluginId: String, key: String, value: JsonElement): ApiResult<JsonElement>
    suspend fun state(pluginId: String): ApiResult<JsonObject>
    suspend fun setEnabled(pluginId: String, enabled: Boolean): ApiResult<JsonElement>
    suspend fun grant(pluginId: String, permissionId: String): ApiResult<JsonElement>
    suspend fun revoke(pluginId: String, permissionId: String): ApiResult<JsonElement>
    suspend fun uninstall(pluginId: String): ApiResult<JsonElement>
    suspend fun catalog(): ApiResult<JsonElement>
    suspend fun installFromCatalog(url: String, sha256: String?, jobId: String): ApiResult<JsonElement>
    suspend fun designate(body: JsonObject): ApiResult<JsonElement>
    suspend fun startRecording(): ApiResult<JsonElement>
    suspend fun stopRecording(): ApiResult<JsonElement>

    /** Perception tier and NPU throughput from `/api/status/full`; null fields when unreported. */
    suspend fun perceptionInfo(): ApiResult<PerceptionInfo>

    /** Text frames of the vision detection stream until it closes. */
    fun detections(): Flow<String>

    /** Stage records of an install job until a terminal stage. */
    fun installJob(jobId: String): Flow<String>

    /** `GET /api/video/roster`. */
    suspend fun cameras(): ApiResult<List<RosterCamera>>
}

/** [ExtensionApi] over the agent REST client. */
class AgentExtensionApi(private val client: AgentClient, private val sockets: AgentSockets) : ExtensionApi {
    private val p get() = client.plugins

    override suspend fun list() = p.list().toApi()
    override suspend fun detail(pluginId: String) = p.detail(pluginId).toApi()
    override suspend fun gcsAsset(pluginId: String, path: String): ApiResult<ByteArray> =
        when (val r = p.gcsAsset(pluginId, path)) {
            is AgentResult.Ok -> if (r.value.isSuccessful) {
                ApiResult.Ok(r.value.bytes)
            } else {
                ApiResult.Err(ExtensionFailure.Refused("HTTP ${r.value.code}"))
            }
            is AgentResult.Err -> ApiResult.Err(ExtensionFailure.from(r.error))
        }

    override suspend fun config(pluginId: String) = p.config(pluginId).toApi()
    override suspend fun putConfig(pluginId: String, key: String, value: JsonElement) =
        p.putConfig(pluginId, key, value).toApi()

    override suspend fun state(pluginId: String) = p.state(pluginId).toApi()
    override suspend fun setEnabled(pluginId: String, enabled: Boolean) =
        (if (enabled) p.enable(pluginId) else p.disable(pluginId)).toApi()

    override suspend fun grant(pluginId: String, permissionId: String) = p.grant(pluginId, permissionId).toApi()
    override suspend fun revoke(pluginId: String, permissionId: String) = p.revoke(pluginId, permissionId).toApi()
    override suspend fun uninstall(pluginId: String) = p.uninstall(pluginId).toApi()
    override suspend fun catalog() = p.catalog().toApi()

    override suspend fun installFromCatalog(url: String, sha256: String?, jobId: String) =
        p.installFromCatalog(url, sha256, jobId).toApi()

    override suspend fun designate(body: JsonObject) =
        client.requestJson("POST", "api/vision/designate", body).toApi()

    override suspend fun startRecording(): ApiResult<JsonElement> = client.startVideoRecording().toApi().map()
    override suspend fun stopRecording(): ApiResult<JsonElement> = client.stopVideoRecording().toApi().map()

    override suspend fun perceptionInfo(): ApiResult<PerceptionInfo> = when (val r = client.statusFull()) {
        is AgentResult.Ok -> ApiResult.Ok(PerceptionInfo(r.value.perceptionTier, r.value.npuTops))
        is AgentResult.Err -> ApiResult.Err(ExtensionFailure.from(r.error))
    }

    override fun detections(): Flow<String> =
        sockets.open(client, PluginApi.DETECTIONS_WS_PATH, PluginApi.DETECTIONS_TICKET_SCOPE).textFrames()

    override suspend fun cameras(): ApiResult<List<RosterCamera>> = when (val r = client.cameraRoster()) {
        is AgentResult.Ok -> ApiResult.Ok(
            r.value.mapNotNull { c ->
                val id = (c["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                RosterCamera(
                    id = id,
                    name = (c["name"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content,
                    purposes = (c["purpose"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
                    enabled = (c["enabled"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "false",
                )
            },
        )
        is AgentResult.Err -> ApiResult.Err(ExtensionFailure.from(r.error))
    }

    override fun installJob(jobId: String): Flow<String> =
        sockets.open(client, PluginApi.installJobPath(jobId)).textFrames()

    private fun Flow<WsFrame>.textFrames(): Flow<String> = mapNotNull { (it as? WsFrame.Text)?.text }

    private fun <T> ApiResult<T>.map(): ApiResult<JsonElement> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(JsonObject(emptyMap()))
        is ApiResult.Err -> this
    }
}

/** Resolves the [ExtensionApi] for a node over its best agent path. */
fun interface ExtensionApiProvider {
    suspend fun forNode(node: DeviceId, reach: ExtensionReach): ExtensionApi?
}

@Singleton
class AgentExtensionApiProvider @Inject constructor(
    private val clients: AgentClientFactory,
    private val sockets: AgentSockets,
) : ExtensionApiProvider {
    override suspend fun forNode(node: DeviceId, reach: ExtensionReach): ExtensionApi? = when (reach) {
        ExtensionReach.Lan -> clients.forNode(node)?.let { AgentExtensionApi(it, sockets) }
        is ExtensionReach.ViaGround -> clients.forCarried(reach.groundId, node)?.let { AgentExtensionApi(it, sockets) }
        ExtensionReach.CloudOnly, ExtensionReach.Unreachable -> null
    }
}
