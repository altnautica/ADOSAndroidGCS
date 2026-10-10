package com.altnautica.gcs.core.extensions

import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionApi
import com.altnautica.gcs.core.extensions.data.ExtensionFailure
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionInfo
import com.altnautica.gcs.core.extensions.data.RosterCamera
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val NODE = DeviceId("drone-1")

fun fixture(name: String): JsonObject {
    val text = requireNotNull(object {}.javaClass.classLoader!!.getResource("fixtures/$name")) { name }.readText()
    return Json.parseToJsonElement(text) as JsonObject
}

fun summary(reaches: List<Reach>, profile: NodeProfile = NodeProfile.Drone) = NodeSummary(
    id = NODE,
    name = "Drone",
    profile = profile,
    reaches = reaches,
    liveness = Liveness.Live,
    agentVersion = null,
    fcVariant = FcVariant.MavlinkArdupilot,
    carriedDroneId = null,
    lastSeenMs = null,
)

class FakeNodeSource(initial: List<NodeSummary>) : NodeSource {
    val flow = MutableStateFlow(initial)
    override val nodes: Flow<List<NodeSummary>> get() = flow
}

/** In-memory agent: details by id, config values, state bodies; records every write. */
class FakeExtensionApi(
    var details: MutableMap<String, JsonObject> = linkedMapOf(),
) : ExtensionApi {
    val writes = mutableListOf<Triple<String, String, JsonElement>>()
    var putFailure: ExtensionFailure? = null
    var stateBodies: MutableMap<String, JsonObject> = mutableMapOf()
    var stateCalls = 0
    var config: MutableMap<String, JsonObject> = mutableMapOf()
    var bundle: ByteArray = "export default 1;".encodeToByteArray()
    var designated = true
    var lastDesignate: JsonObject? = null

    override suspend fun list(): ApiResult<JsonElement> = ApiResult.Ok(
        buildJsonObject {
            put("installs", buildJsonArray {
                for (id in details.keys) add(buildJsonObject { put("plugin_id", id); put("status", "enabled") })
            })
        },
    )

    override suspend fun detail(pluginId: String): ApiResult<JsonObject> =
        details[pluginId]?.let { ApiResult.Ok(it) } ?: ApiResult.Err(ExtensionFailure.Refused("not installed"))

    override suspend fun gcsAsset(pluginId: String, path: String): ApiResult<ByteArray> = ApiResult.Ok(bundle)

    override suspend fun config(pluginId: String): ApiResult<JsonObject> =
        ApiResult.Ok(buildJsonObject { put("values", config[pluginId] ?: JsonObject(emptyMap())) })

    override suspend fun putConfig(pluginId: String, key: String, value: JsonElement): ApiResult<JsonElement> {
        putFailure?.let { return ApiResult.Err(it) }
        writes += Triple(pluginId, key, value)
        return ApiResult.Ok(buildJsonObject { put("set", true) })
    }

    override suspend fun state(pluginId: String): ApiResult<JsonObject> {
        stateCalls++
        return ApiResult.Ok(stateBodies[pluginId] ?: JsonObject(emptyMap()))
    }

    override suspend fun setEnabled(pluginId: String, enabled: Boolean): ApiResult<JsonElement> = ok()
    override suspend fun grant(pluginId: String, permissionId: String): ApiResult<JsonElement> = ok()
    override suspend fun revoke(pluginId: String, permissionId: String): ApiResult<JsonElement> = ok()
    override suspend fun uninstall(pluginId: String): ApiResult<JsonElement> = ok()
    override suspend fun catalog(): ApiResult<JsonElement> = ApiResult.Ok(buildJsonObject { put("plugins", buildJsonArray {}) })
    override suspend fun installFromCatalog(url: String, sha256: String?, jobId: String): ApiResult<JsonElement> = ok()
    override suspend fun designate(body: JsonObject): ApiResult<JsonElement> {
        lastDesignate = body
        return ApiResult.Ok(buildJsonObject { put("designated", designated) })
    }
    override suspend fun startRecording(): ApiResult<JsonElement> = ok()
    override suspend fun stopRecording(): ApiResult<JsonElement> = ok()
    override suspend fun perceptionInfo(): ApiResult<PerceptionInfo> = ApiResult.Ok(PerceptionInfo(null, null))
    override fun detections(): Flow<String> = emptyFlow()
    override fun installJob(jobId: String): Flow<String> = emptyFlow()
    override suspend fun cameras(): ApiResult<List<RosterCamera>> = ApiResult.Ok(
        listOf(RosterCamera("cam0", "Front", listOf("video"), true), RosterCamera("cam1", "Down", listOf("detect"), true)),
    )

    private fun ok(): ApiResult<JsonElement> = ApiResult.Ok(JsonObject(emptyMap()))
}

fun repository(api: FakeExtensionApi, source: FakeNodeSource, scope: CoroutineScope) =
    ExtensionRepository(setOf(source), { _, _ -> api }, scope)
