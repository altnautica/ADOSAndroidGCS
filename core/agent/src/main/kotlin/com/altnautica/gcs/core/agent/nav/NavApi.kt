package com.altnautica.gcs.core.agent.nav

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Live autonomy feeds of one node: vision detections, the navigation stream
 * (pose, scan, nav, follow and follow events) and the live occupancy map.
 * Served by the node's agent, or in-process for a simulated node.
 */
interface AutonomyFeedApi {
    /** Detection batch text frames (agent wire shape, snake_case) until the stream closes. */
    fun detections(): Flow<String>

    /** Navigation stream text frames `{kind, ts_ms, data}` until the stream closes. */
    fun navStream(): Flow<String>

    /** Occupancy tiles changed after [since]: `{seq, frame, res_m, tile_cells, tiles[]}`. */
    suspend fun mapLive(since: Long): AgentResult<JsonObject>
}

/**
 * Target and follow controls an in-process autonomy source answers (a simulated node);
 * an agent-served node takes these through its extension routes instead.
 */
interface AutonomyControlApi {
    /** `POST /api/vision/designate` equivalent: `{camera_id, bbox{x,y,width,height}}` in frame px. */
    suspend fun designate(body: JsonObject): AgentResult<JsonElement>

    /** `PUT /api/plugins/{id}/config {key, value}` equivalent. */
    suspend fun putPluginConfig(pluginId: String, key: String, value: JsonElement): AgentResult<JsonElement>
}

/** Host navigation and map routes (`/api/v1/nav/...`, `/api/v1/map/...`). */
class NavApi internal constructor(private val client: AgentClient) {

    /** `GET /api/v1/nav/status`: the latest frame of each kind with its age; absent kinds are null. */
    suspend fun status(): AgentResult<JsonObject> = client.get(STATUS_PATH, JsonObject.serializer())

    /** `GET /api/v1/map/live?since=`: occupancy tiles changed after [since]. */
    suspend fun mapLive(since: Long): AgentResult<JsonObject> =
        client.get(MAP_LIVE_PATH, JsonObject.serializer(), mapOf("since" to since.toString()))

    companion object {
        const val STATUS_PATH = "api/v1/nav/status"
        const val MAP_LIVE_PATH = "api/v1/map/live"

        /** WebSocket path of the navigation stream (needs a `nav.stream` ticket). */
        const val STREAM_WS_PATH = "api/v1/nav/stream"
        const val STREAM_TICKET_SCOPE = "nav.stream"
    }
}
