package com.altnautica.gcs.core.demo.node

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.util.Base64
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyControlApi
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Scripted autonomy feeds for the demo drone: a person walks across the front camera
 * while follow-me cycles through acquiring, following, coasting, searching, re-acquired
 * and lost (4 s each), the vehicle circles a 16 × 10 m room with a planar scan, and the
 * room's occupancy map is served as live tiles.
 */
internal class DemoAutonomy : AutonomyFeedApi, AutonomyControlApi {
    @Volatile private var reacquireMode = "smart"

    private fun t() = SystemClock.elapsedRealtime()

    /** 0 candidate, 1 tracked, 2 acquiring, 3 following, 4 coasting, 5 searching, 6 re-acquired, 7 lost. */
    private fun step(now: Long) = ((now / STEP_MS) % STEPS).toInt()

    private fun personX(now: Long) = 560f + 220f * sin(now / 3_000.0).toFloat()

    override fun detections(): Flow<String> = flow {
        var frame = 0L
        while (true) {
            val now = t()
            val s = step(now)
            val x = personX(now)
            val items = buildJsonArray {
                // A second walker that never gets a track: always a candidate.
                add(det(980f, 330f, 40f, 104f, 0.64f, null, null))
                when (s) {
                    0 -> add(det(x, 280f, 60f, 150f, 0.82f, null, null))
                    1, 2, 3 -> add(det(x, 280f, 60f, 150f, 0.97f, 12, "locked"))
                    6 -> add(det(x, 280f, 60f, 150f, 0.91f, 15, "locked"))
                    else -> Unit
                }
            }
            emit(
                buildJsonObject {
                    put("v", 2)
                    put("camera_id", CAMERA)
                    put("frame_id", frame++)
                    put("ts_ms", now)
                    put("frame_width", FRAME_W)
                    put("frame_height", FRAME_H)
                    put("detections", items)
                }.toString(),
            )
            delay(TICK_MS)
        }
    }

    private fun det(x: Float, y: Float, w: Float, h: Float, conf: Float, track: Int?, lock: String?) = buildJsonObject {
        putJsonObject("bbox") {
            put("x", x)
            put("y", y)
            put("width", w)
            put("height", h)
        }
        put("class_label", "person")
        put("confidence", conf)
        put("track_id", track?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lock_state", lock?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    override fun navStream(): Flow<String> = flow {
        var last = -1
        var n = 0
        while (true) {
            val now = t()
            val s = step(now)
            if (s != last) {
                if (s == 6) emit(event("follow.reacquired", buildJsonObject { put("track_id", 15); put("similarity", 0.862); put("gap_s", 1.8) }, now))
                if (s == 7) emit(event("follow.lost", buildJsonObject { put("track_id", 15); put("reason", "lock_lost") }, now))
                last = s
            }
            val angle = (now % ORBIT_MS) / ORBIT_MS.toDouble() * 2 * PI
            val north = (4 * sin(angle)).toFloat()
            val east = (2.5 * cos(angle)).toFloat()
            val yaw = (angle + PI / 2).toFloat()
            emit(frame("pose", pose(north, east, yaw, if (s == 5) "degraded" else "good"), now))
            emit(frame("scan", scan(north, east, yaw), now))
            emit(frame("follow", follow(s, now, yaw), now))
            if (n++ % 5 == 0) emit(frame("nav", nav(s), now))
            delay(TICK_MS)
        }
    }.flowOn(Dispatchers.Default)

    private fun frame(kind: String, data: JsonObject, now: Long) = buildJsonObject {
        put("kind", kind)
        put("ts_ms", now)
        put("data", data)
    }.toString()

    private fun event(topic: String, data: JsonObject, now: Long) = buildJsonObject {
        put("kind", "follow_event")
        put("ts_ms", now)
        put("topic", topic)
        put("data", data)
    }.toString()

    private fun pose(n: Float, e: Float, yaw: Float, health: String) = buildJsonObject {
        putJsonObject("pose") {
            putJsonArray("R") { listOf(cos(yaw), -sin(yaw), 0f, sin(yaw), cos(yaw), 0f, 0f, 0f, 1f).forEach { add(JsonPrimitive(it)) } }
            putJsonArray("t") { add(JsonPrimitive(n)); add(JsonPrimitive(e)); add(JsonPrimitive(-3f)) }
        }
        put("health", health)
        put("frame", "map_ned")
        put("source", "lidar_planar")
    }

    /** Ray from (n, e) against the room walls (|north| = 8, |east| = 5), one return per degree. */
    private fun scan(n: Float, e: Float, yaw: Float) = buildJsonObject {
        put("frame", "body_frd")
        put("angle_start_rad", 0.0)
        put("angle_increment_rad", PI / 180)
        putJsonArray("ranges_m") {
            for (i in 0 until 360) {
                val a = yaw + i * PI.toFloat() / 180f
                val dn = cos(a)
                val de = sin(a)
                val tn = if (abs(dn) < 1e-4f) Float.MAX_VALUE else ((if (dn > 0) ROOM_N else -ROOM_N) - n) / dn
                val te = if (abs(de) < 1e-4f) Float.MAX_VALUE else ((if (de > 0) ROOM_E else -ROOM_E) - e) / de
                val r = min(tn, te)
                add(JsonPrimitive(if (r in 0.05f..12f) r else 0f))
            }
        }
    }

    private fun nav(s: Int) = buildJsonObject {
        put("mode", "external_nav")
        put("active_set", 1)
        put("extnav_health", if (s == 5) "degraded" else "good")
        put("odom_rate_hz", 30.0)
        put("last_reset_counter", 0)
        put("obstacle_rate_hz", 10.0)
        put("min_obstacle_m", 1.8)
        put("gps_fix", 0)
    }

    private fun follow(s: Int, now: Long, yaw: Float) = buildJsonObject {
        val phase = when (s) {
            0, 1 -> "idle"
            2 -> "acquiring"
            3, 6 -> "following"
            4 -> "coasting"
            5 -> "searching"
            else -> "lost"
        }
        put("phase", phase)
        put("track_id", if (s >= 6) 15 else 12)
        put("confidence", 0.97)
        put("range_m", 10.2)
        put("range_sigma_m", 0.3)
        putJsonArray("range_cues") {
            add(buildJsonObject { put("kind", "ground"); put("range_m", 10.1); put("sigma_m", 0.4) })
            add(buildJsonObject { put("kind", "box_height"); put("range_m", 10.4); put("sigma_m", 1.5) })
        }
        put("target_speed_mps", 1.4)
        put("agl_m", 4.1)
        put("agl_source", "rng")
        put("reacquire_mode", reacquireMode)
        put("coast_remaining_s", if (s == 4) (STEP_MS - now % STEP_MS) / 1000.0 else 0.0)
        put("pipeline_latency_ms", 85)
        put("hold_reason", JsonNull)
        put("bearing_deg", 64.0)
        put("depression_deg", 22.0)
        putJsonArray("target_ned") { add(JsonPrimitive(5.5)); add(JsonPrimitive(1.5)) }
        putJsonArray("target_vel_ned") { add(JsonPrimitive(1.2 * cos(yaw))); add(JsonPrimitive(1.2 * sin(yaw))) }
        if (s == 4) {
            putJsonObject("predicted_bbox") {
                put("camera_id", CAMERA)
                put("frame_w", FRAME_W)
                put("frame_h", FRAME_H)
                put("x", personX(now) + 40f)
                put("y", 280f)
                put("w", 60f)
                put("h", 150f)
            }
        } else {
            put("predicted_bbox", JsonNull)
        }
    }

    private val tiles: List<JsonObject> by lazy { buildTiles() }

    override suspend fun mapLive(since: Long): AgentResult<JsonObject> = AgentResult.Ok(
        buildJsonObject {
            put("seq", MAP_SEQ)
            put("frame", "map_ned")
            put("res_m", RES_M)
            put("tile_cells", CELLS)
            putJsonArray("tiles") { if (since < MAP_SEQ) tiles.forEach { add(it) } }
        },
    )

    private fun buildTiles(): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val span = CELLS * RES_M
        for (ix in -3..2) for (iy in -2..1) {
            val bmp = createBitmap(CELLS, CELLS)
            for (row in 0 until CELLS) for (col in 0 until CELLS) {
                val north = (ix + 1) * span - (row + 0.5f) * RES_M
                val east = iy * span + (col + 0.5f) * RES_M
                val inside = abs(north) <= ROOM_N && abs(east) <= ROOM_E
                val wall = inside && (abs(north) > ROOM_N - WALL_M || abs(east) > ROOM_E - WALL_M)
                val v = when {
                    wall -> 0
                    inside -> 254
                    else -> 205
                }
                bmp[col, row] = android.graphics.Color.rgb(v, v, v)
            }
            val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bmp.recycle()
            out += buildJsonObject {
                put("ix", ix)
                put("iy", iy)
                put("png_b64", Base64.encodeToString(png, Base64.NO_WRAP))
            }
        }
        return out
    }

    override suspend fun designate(body: JsonObject): AgentResult<JsonElement> =
        AgentResult.Ok(buildJsonObject { put("designated", true) })

    override suspend fun putPluginConfig(pluginId: String, key: String, value: JsonElement): AgentResult<JsonElement> {
        if (key == "reacquire_mode") (value as? JsonPrimitive)?.content?.let { reacquireMode = it }
        return AgentResult.Ok(buildJsonObject { put("ok", true) })
    }

    private companion object {
        const val CAMERA = "main"
        const val FRAME_W = 1280
        const val FRAME_H = 720
        const val TICK_MS = 100L
        const val STEP_MS = 4_000L
        const val STEPS = 8
        const val ORBIT_MS = 40_000L
        const val ROOM_N = 8f
        const val ROOM_E = 5f
        const val WALL_M = 0.15f
        const val RES_M = 0.05f
        const val CELLS = 64
        const val MAP_SEQ = 1L
    }
}
