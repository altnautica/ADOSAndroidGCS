package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Latest published state of each extension on a node: pluginId → topic → payload. */
typealias NodeExtensionState = Map<String, Map<String, JsonElement>>

/**
 * Polls `GET /api/plugins/{id}/state` for every enabled extension on a node every
 * [POLL_INTERVAL_MS] while at least one holder (a visible cockpit or extension frame)
 * keeps the node acquired. Changed topics are republished on the event bus under the
 * extension's agent-state origin.
 */
@Singleton
class ExtensionStateFeed @Inject constructor(
    private val repository: ExtensionRepository,
    private val bus: ExtensionEventBus,
    @ExtensionScope private val scope: CoroutineScope,
) {
    private class Poll(var holders: Int, val job: Job)

    private val polls = HashMap<DeviceId, Poll>()
    private val states = MutableStateFlow<Map<DeviceId, NodeExtensionState>>(emptyMap())

    val all: StateFlow<Map<DeviceId, NodeExtensionState>> = states.asStateFlow()

    fun state(node: DeviceId): Flow<NodeExtensionState> = states.map { it[node].orEmpty() }

    /** Start (or join) polling [node]; the returned action releases this holder. */
    fun acquire(node: DeviceId): () -> Unit {
        synchronized(polls) {
            val existing = polls[node]
            if (existing != null) {
                existing.holders++
            } else {
                polls[node] = Poll(1, scope.launch { run(node) })
            }
        }
        var released = false
        return {
            synchronized(polls) {
                if (!released) {
                    released = true
                    val p = polls[node]
                    if (p != null && --p.holders == 0) {
                        polls.remove(node)
                        p.job.cancel()
                    }
                }
            }
        }
    }

    private suspend fun run(node: DeviceId) {
        repository.refresh(node)
        while (scope.isActive) {
            pollOnce(node)
            delay(POLL_INTERVAL_MS)
        }
    }

    internal suspend fun pollOnce(node: DeviceId) {
        val enabled: List<ExtensionDetail> =
            (repository.extensions(node).value as? ExtensionsState.Loaded)?.extensions.orEmpty()
                .filter { it.status.isEnabled }
        if (enabled.isEmpty()) return
        val api = repository.api(node) ?: return
        for (ext in enabled) {
            val body = (api.state(ext.pluginId) as? ApiResult.Ok)?.value ?: continue
            val topics = HashMap<String, JsonElement>()
            for ((topic, entry) in body) {
                val payload = (entry as? JsonObject)?.get("payload") ?: continue
                topics[topic] = payload
            }
            publish(node, ext.pluginId, topics)
        }
    }

    private fun publish(node: DeviceId, pluginId: String, topics: Map<String, JsonElement>) {
        val before = states.value[node]?.get(pluginId).orEmpty()
        if (before == topics) return
        states.update { all ->
            val nodeMap = all[node].orEmpty().toMutableMap()
            nodeMap[pluginId] = topics
            all + (node to nodeMap)
        }
        val origin = ExtensionEventBus.agentStateOrigin(pluginId, node)
        for ((topic, payload) in topics) {
            if (before[topic] != payload) bus.publish(topic, payload, origin)
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 750L
    }
}
