package com.altnautica.gcs.feature.cockpit.autonomy

import android.util.Base64
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerFrame
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.atan2

/** One decoded navigation-stream frame. */
sealed interface NavFrame {
    data class Pose(val pose: PoseFrame) : NavFrame
    data class Scan(val scan: ScanFrame) : NavFrame
    data class Nav(val nav: NavState) : NavFrame
    data class Follow(val follow: FollowState) : NavFrame
    data class Event(val event: FollowEvent) : NavFrame
    data class Pilot(val state: PilotState) : NavFrame
    data class Layer(val frame: LayerFrame) : NavFrame
}

/** Parses the agent's autonomy payloads. Unknown or malformed input yields null, never a guess. */
internal object AutonomyParse {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement?.f(): Float? = num()?.toFloat()
    private fun JsonElement?.int(): Int? = num()?.toInt()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    /** A camelCase detection batch (the shape `PerceptionFeed.mapBatch` produces). */
    fun batch(o: JsonObject, receivedAtMs: Long): DetectionBatch? {
        val camera = o["cameraId"].str() ?: return null
        val items = o["detections"].arr().orEmpty().mapNotNull { e ->
            val d = e.obj() ?: return@mapNotNull null
            val b = d["bbox"].obj() ?: return@mapNotNull null
            Detection(
                box = Box(b["x"].f() ?: 0f, b["y"].f() ?: 0f, b["width"].f() ?: 0f, b["height"].f() ?: 0f),
                classLabel = d["classLabel"].str().orEmpty(),
                confidence = d["confidence"].f() ?: 0f,
                trackId = d["trackId"].int(),
                lockState = when (d["lockState"].str()) {
                    "locked" -> LockState.Locked
                    "uncertain" -> LockState.Uncertain
                    "lost" -> LockState.Lost
                    else -> null
                },
            )
        }
        return DetectionBatch(
            cameraId = camera,
            frameId = o["frameId"].num()?.toLong() ?: 0L,
            frameWidth = o["frameWidth"].f() ?: return null,
            frameHeight = o["frameHeight"].f() ?: return null,
            receivedAtMs = receivedAtMs,
            items = items,
        )
    }

    /** One `WS /api/v1/nav/stream` text frame; null for keepalives, unknown kinds and bad payloads. */
    fun navFrame(text: String, receivedAtMs: Long): NavFrame? {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull().obj() ?: return null
        val data = root["data"].obj() ?: return null
        return when (root["kind"].str()) {
            "pose" -> pose(data, receivedAtMs)?.let(NavFrame::Pose)
            "scan" -> scan(data, receivedAtMs)?.let(NavFrame::Scan)
            "nav" -> NavFrame.Nav(nav(data))
            "follow" -> follow(data)?.let(NavFrame::Follow)
            "follow_event" -> followEvent(root["topic"].str(), data, receivedAtMs)?.let(NavFrame::Event)
            "pilot" -> PilotWire.parseState(data, receivedAtMs)?.let(NavFrame::Pilot)
            "ar", "obstacles", "depth", "surfaces" -> LayerParse.frame(root["kind"].str(), data)?.let(NavFrame::Layer)
            else -> null
        }
    }

    fun pose(o: JsonObject, at: Long): PoseFrame? {
        val p = o["pose"].obj() ?: return null
        val t = p["t"].arr()?.mapNotNull { it.f() }?.takeIf { it.size >= 2 } ?: return null
        val r = p["R"].arr()?.mapNotNull { it.f() }?.takeIf { it.size == 9 } ?: return null
        // Row-major body→world rotation; yaw is the heading of the body x axis in the world.
        val yaw = atan2(r[3], r[0])
        val health = when (o["health"].str()) {
            "good" -> PoseHealth.Good
            "degraded" -> PoseHealth.Degraded
            "lost" -> PoseHealth.Lost
            else -> return null
        }
        return PoseFrame(t[0], t[1], yaw, health, at)
    }

    fun scan(o: JsonObject, at: Long): ScanFrame? {
        val ranges = o["ranges_m"].arr() ?: return null
        val values = FloatArray(ranges.size) { i -> ranges[i].f() ?: 0f }
        return ScanFrame(o["angle_start_rad"].f() ?: return null, o["angle_increment_rad"].f() ?: return null, values, at)
    }

    fun nav(o: JsonObject) = NavState(
        mode = o["mode"].str(),
        activeSet = o["active_set"].int(),
        extnavHealth = when (o["extnav_health"].str()) {
            "good" -> ExtNavHealth.Good
            "degraded" -> ExtNavHealth.Degraded
            "lost" -> ExtNavHealth.Lost
            else -> null
        },
        odomRateHz = o["odom_rate_hz"].f(),
        obstacleRateHz = o["obstacle_rate_hz"].f(),
        minObstacleM = o["min_obstacle_m"].f(),
        gpsFix = o["gps_fix"].int(),
    )

    fun follow(o: JsonObject): FollowState? {
        val phase = when (o["phase"].str()) {
            "idle" -> FollowPhase.Idle
            "acquiring" -> FollowPhase.Acquiring
            "following" -> FollowPhase.Following
            "coasting" -> FollowPhase.Coasting
            "searching" -> FollowPhase.Searching
            "lost" -> FollowPhase.Lost
            "holding" -> FollowPhase.Holding
            else -> return null
        }
        fun pair(e: JsonElement?): Pair<Float, Float>? {
            val a = e.arr()?.mapNotNull { it.f() } ?: return null
            return if (a.size >= 2) a[0] to a[1] else null
        }
        val predicted = o["predicted_bbox"].obj()?.let { p ->
            val cam = p["camera_id"].str() ?: return@let null
            PredictedBox(
                cam,
                p["frame_w"].f() ?: return@let null,
                p["frame_h"].f() ?: return@let null,
                Box(p["x"].f() ?: return@let null, p["y"].f() ?: return@let null, p["w"].f() ?: return@let null, p["h"].f() ?: return@let null),
            )
        }
        return FollowState(
            phase = phase,
            trackId = o["track_id"].int(),
            confidence = o["confidence"].f(),
            rangeM = o["range_m"].f(),
            rangeSigmaM = o["range_sigma_m"].f(),
            rangeCues = o["range_cues"].arr().orEmpty().mapNotNull { c ->
                when (c.obj()?.get("kind").str()) {
                    "ground" -> RangeCue.Ground
                    "box_height" -> RangeCue.BoxHeight
                    "lidar" -> RangeCue.Lidar
                    else -> null
                }
            }.toSet(),
            targetSpeedMps = o["target_speed_mps"].f(),
            aglM = o["agl_m"].f(),
            aglSource = o["agl_source"].str(),
            reacquireMode = o["reacquire_mode"].str(),
            coastRemainingS = o["coast_remaining_s"].f(),
            pipelineLatencyMs = o["pipeline_latency_ms"].f(),
            holdReason = o["hold_reason"].str(),
            bearingDeg = o["bearing_deg"].f(),
            depressionDeg = o["depression_deg"].f(),
            targetNed = pair(o["target_ned"]),
            targetVelNed = pair(o["target_vel_ned"]),
            predicted = predicted,
        )
    }

    fun followEvent(topic: String?, o: JsonObject, at: Long): FollowEvent? = when (topic) {
        "follow.reacquired" -> FollowEvent.Reacquired(
            o["track_id"].int() ?: return null,
            o["similarity"].f() ?: return null,
            o["gap_s"].f() ?: 0f,
            at,
        )
        "follow.lost" -> FollowEvent.Lost(o["track_id"].int(), o["reason"].str(), at)
        else -> null
    }

    /** A `/api/v1/map/live` reply; tiles whose PNG does not decode from base64 are skipped. */
    fun occupancy(o: JsonObject): OccupancyDelta? {
        val seq = o["seq"].num()?.toLong() ?: return null
        val tiles = o["tiles"].arr().orEmpty().mapNotNull { e ->
            val t = e.obj() ?: return@mapNotNull null
            val png = t["png_b64"].str()?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return@mapNotNull null
            OccupancyTile(t["ix"].int() ?: return@mapNotNull null, t["iy"].int() ?: return@mapNotNull null, png)
        }
        return OccupancyDelta(seq, o["res_m"].f() ?: return null, o["tile_cells"].int() ?: DEFAULT_TILE_CELLS, tiles)
    }

    const val DEFAULT_TILE_CELLS = 64
}
