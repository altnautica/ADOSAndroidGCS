package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.extensions.manifest.CatalogEntry
import com.altnautica.gcs.core.extensions.manifest.CatalogParser
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetailParser
import com.altnautica.gcs.core.extensions.manifest.asArray
import com.altnautica.gcs.core.extensions.manifest.asObject
import com.altnautica.gcs.core.extensions.manifest.str
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** What the app knows about one node's installed extensions. */
sealed interface ExtensionsState {
    data object Loading : ExtensionsState
    data class Loaded(val extensions: List<ExtensionDetail>) : ExtensionsState
    /** No agent path; [reasonKey] names why (cloud-only or none). */
    data class NoAgentPath(val reasonKey: String) : ExtensionsState
    data class Failed(val failure: ExtensionFailure) : ExtensionsState
}

/** Outcome of a write the operator triggered. */
sealed interface WriteOutcome {
    data object Done : WriteOutcome
    data class NoPath(val reasonKey: String) : WriteOutcome
    data class Failed(val failure: ExtensionFailure) : WriteOutcome
}

/**
 * Per-node extension inventory and the writes the extension surfaces make. Every call
 * resolves the node's best agent path (LAN first, then ground-station relay) at call time.
 */
@Singleton
class ExtensionRepository @Inject constructor(
    nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    private val apis: ExtensionApiProvider,
    @ExtensionScope scope: CoroutineScope,
) {
    private val merged: Flow<Map<DeviceId, NodeSummary>> = mergeSources(nodeSources)

    /** Every node any source knows, keyed by id; first source's reaches come first. */
    val nodes: StateFlow<Map<DeviceId, NodeSummary>> = merged.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * Reach from the sources' current lists. Suspending callers use this rather than the
     * [nodes] snapshot, which is empty until the shared collector has run once.
     */
    private suspend fun liveReach(node: DeviceId): ExtensionReach =
        ExtensionReach.classify(merged.first()[node]?.reaches.orEmpty())

    private val states = ConcurrentHashMap<DeviceId, MutableStateFlow<ExtensionsState>>()

    /** Known per-node config of each extension: agent read-back plus every accepted write. */
    private val configCache = MutableStateFlow<Map<Pair<DeviceId, String>, JsonObject>>(emptyMap())

    fun knownConfig(node: DeviceId, pluginId: String): Flow<JsonObject?> =
        configCache.map { it[node to pluginId] }

    fun reach(node: DeviceId): ExtensionReach =
        ExtensionReach.classify(nodes.value[node]?.reaches.orEmpty())

    fun profile(node: DeviceId): NodeProfile = nodes.value[node]?.profile ?: NodeProfile.Unknown

    /** Extensions on [node]; call [refresh] to (re)load. */
    fun extensions(node: DeviceId): StateFlow<ExtensionsState> = holder(node).asStateFlow()

    /** Enabled extensions on [node], empty while loading or unreachable. */
    fun enabled(node: DeviceId): Flow<List<ExtensionDetail>> = extensions(node).map { s ->
        (s as? ExtensionsState.Loaded)?.extensions.orEmpty().filter { it.status.isEnabled }
    }

    suspend fun api(node: DeviceId): ExtensionApi? {
        val r = liveReach(node)
        return if (r.hasAgentPath) apis.forNode(node, r) else null
    }

    suspend fun refresh(node: DeviceId) {
        val holder = holder(node)
        val r = liveReach(node)
        val reason = ExtensionReach.reasonKey(r)
        if (reason != null) {
            holder.value = ExtensionsState.NoAgentPath(reason)
            return
        }
        val api = apis.forNode(node, r) ?: run {
            holder.value = ExtensionsState.NoAgentPath(ExtensionReach.REASON_NO_AGENT)
            return
        }
        if (holder.value !is ExtensionsState.Loaded) holder.value = ExtensionsState.Loading
        holder.value = load(api)
    }

    private suspend fun load(api: ExtensionApi): ExtensionsState {
        val ids = when (val l = api.list()) {
            is ApiResult.Ok -> installIds(l.value)
            is ApiResult.Err -> return ExtensionsState.Failed(l.failure)
        }
        val out = ArrayList<ExtensionDetail>(ids.size)
        for (id in ids) {
            when (val d = api.detail(id)) {
                is ApiResult.Ok -> ExtensionDetailParser.parse(d.value)?.let(out::add)
                is ApiResult.Err -> if (d.failure !is ExtensionFailure.Refused) return ExtensionsState.Failed(d.failure)
            }
        }
        return ExtensionsState.Loaded(out.sortedBy { it.name.lowercase() })
    }

    suspend fun setEnabled(node: DeviceId, pluginId: String, enabled: Boolean): WriteOutcome =
        write(node) { it.setEnabled(pluginId, enabled) }

    suspend fun grant(node: DeviceId, pluginId: String, permission: String): WriteOutcome =
        write(node) { it.grant(pluginId, permission) }

    suspend fun revoke(node: DeviceId, pluginId: String, permission: String): WriteOutcome =
        write(node) { it.revoke(pluginId, permission) }

    suspend fun uninstall(node: DeviceId, pluginId: String): WriteOutcome =
        write(node) { it.uninstall(pluginId) }

    /** Install a catalog entry; [jobId] names the agent install job (stream it with [ExtensionApi.installJob]). */
    suspend fun install(node: DeviceId, entry: CatalogEntry, jobId: String): WriteOutcome {
        val url = entry.downloadUrl ?: return WriteOutcome.Failed(ExtensionFailure.Refused("no download"))
        return write(node) { it.installFromCatalog(url, entry.archiveSha256, jobId) }
    }

    suspend fun catalog(node: DeviceId): ApiResult<List<CatalogEntry>> {
        val api = api(node) ?: return ApiResult.Err(ExtensionFailure.FeatureAbsent)
        return when (val r = api.catalog()) {
            is ApiResult.Ok -> ApiResult.Ok(CatalogParser.parse(r.value))
            is ApiResult.Err -> r
        }
    }

    suspend fun config(node: DeviceId, pluginId: String): ApiResult<JsonObject> {
        val api = api(node) ?: return ApiResult.Err(ExtensionFailure.FeatureAbsent)
        return when (val r = api.config(pluginId)) {
            is ApiResult.Ok -> {
                val values = r.value["values"].asObject() ?: JsonObject(emptyMap())
                configCache.update { it + ((node to pluginId) to JsonObject((it[node to pluginId] ?: JsonObject(emptyMap())) + values)) }
                ApiResult.Ok(values)
            }
            is ApiResult.Err -> r
        }
    }

    /** Writes one key of the extension's per-node config; the agent validates the value. */
    suspend fun writeConfig(node: DeviceId, pluginId: String, key: String, value: JsonElement): WriteOutcome {
        val r = liveReach(node)
        ExtensionReach.reasonKey(r)?.let { return WriteOutcome.NoPath(it) }
        val api = apis.forNode(node, r) ?: return WriteOutcome.NoPath(ExtensionReach.REASON_NO_AGENT)
        return when (val w = api.putConfig(pluginId, key, value)) {
            is ApiResult.Ok -> {
                configCache.update { all ->
                    val prev = all[node to pluginId] ?: JsonObject(emptyMap())
                    all + ((node to pluginId) to JsonObject(prev + (key to value)))
                }
                WriteOutcome.Done
            }
            is ApiResult.Err -> WriteOutcome.Failed(w.failure)
        }
    }

    private suspend fun write(node: DeviceId, op: suspend (ExtensionApi) -> ApiResult<*>): WriteOutcome {
        val r = liveReach(node)
        ExtensionReach.reasonKey(r)?.let { return WriteOutcome.NoPath(it) }
        val api = apis.forNode(node, r) ?: return WriteOutcome.NoPath(ExtensionReach.REASON_NO_AGENT)
        return when (val res = op(api)) {
            is ApiResult.Ok -> {
                holder(node).value = load(api)
                WriteOutcome.Done
            }
            is ApiResult.Err -> WriteOutcome.Failed(res.failure)
        }
    }

    private fun holder(node: DeviceId) = states.getOrPut(node) { MutableStateFlow(ExtensionsState.Loading) }

    companion object {
        /** Plugin ids from `GET /api/plugins` (`{installs:[{plugin_id}]}`), removed installs skipped. */
        fun installIds(body: JsonElement): List<String> =
            body.asObject()?.get("installs").asArray().orEmpty().mapNotNull { e ->
                val o = e.asObject() ?: return@mapNotNull null
                if (o["status"].str() == "removed") null else o["plugin_id"].str()
            }

        internal fun mergeSources(sources: Set<NodeSource>): Flow<Map<DeviceId, NodeSummary>> {
            if (sources.isEmpty()) return flowOf(emptyMap())
            return combine(sources.map { it.nodes }) { lists ->
                val out = LinkedHashMap<DeviceId, NodeSummary>()
                for (list in lists) {
                    for (n in list) {
                        val prev = out[n.id]
                        out[n.id] = if (prev == null) n else prev.copy(reaches = (prev.reaches + n.reaches).distinct())
                    }
                }
                out
            }
        }
    }
}
