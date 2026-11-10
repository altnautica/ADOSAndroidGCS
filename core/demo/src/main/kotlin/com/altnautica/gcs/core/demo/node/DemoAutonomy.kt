package com.altnautica.gcs.core.demo.node

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import android.util.Base64
import android.util.Log
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.mission.MissionStatusWire
import com.altnautica.gcs.core.agent.nav.AutonomyControlApi
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.demo.DemoFc
import com.altnautica.gcs.core.demo.DemoScenarioStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Scripted autonomy feeds for the demo drone, following its onboard pilot ([DemoPilot]): a
 * person walks across the front camera, and while the pilot reports the follow skill flying
 * the follow feed ([DemoFollowFeed]) tracks and follows them; the vehicle circles a 16 × 10 m
 * room at the pilot's height with a planar scan, and the room's occupancy map is served as
 * live tiles.
 */
internal class DemoAutonomy(fc: DemoFc, scenarios: DemoScenarioStore) : AutonomyFeedApi, AutonomyControlApi {
    private fun t() = SystemClock.elapsedRealtime()

    /** Owner and input-source changes go to logcat under [PILOT_LOG_TAG], the demo pilot's flight log. */
    private val pilot = DemoPilot(fc, { Log.i(PILOT_LOG_TAG, it) }, scenarios) { t() }

    private val follow = DemoFollowFeed()

    override fun detections(): Flow<String> = flow {
        var frame = 0L
        while (true) {
            emit(follow.detections(t(), frame++, pilot.view().followRunning).toString())
            delay(TICK_MS)
        }
    }

    override fun navStream(outbound: Flow<String>): Flow<String> = flow {
        // The follow step last seen while the follow skill flies; null while it does not.
        var last: Int? = null
        var n = 0
        while (true) {
            val now = t()
            val snapshot = pilot.snapshot()
            val view = snapshot.view
            val s = follow.step(now)
            val following = view.followRunning
            follow.event(last, now, following)?.let { (topic, data) -> emit(event(topic, data, now)) }
            last = if (following) s else null
            val searching = following && follow.searching(s)
            val angle = (now % ORBIT_MS) / ORBIT_MS.toDouble() * 2 * PI
            val north = (4 * sin(angle)).toFloat()
            val east = (2.5 * cos(angle)).toFloat()
            val yaw = (angle + PI / 2).toFloat()
            emit(frame("pose", pose(north, east, -view.heightM.toFloat(), yaw, if (searching) "degraded" else "good"), now))
            emit(frame("scan", scan(north, east, yaw), now))
            if (following) emit(frame("follow", follow.follow(s, now, yaw, view.heightM), now))
            if (n++ % 5 == 0) emit(frame("nav", nav(searching), now))
            layers.frames(now, view) { kind, data -> frame(kind, data, now) }.forEach { emit(it) }
            snapshot.frame?.let { emit(it) }
            delay(TICK_MS)
        }
    }.flowOn(Dispatchers.Default)

    override fun pilotStream(outgoing: Flow<String>): Flow<String> = pilot.stream(outgoing).flowOn(Dispatchers.Default)

    /** The agent's mission mirror for the demo drone: the pilot frame's mission block, every 2 s. */
    fun missionMirror(): Flow<MissionMirror?> = flow {
        while (true) {
            emit(demoMissionMirror(pilot.view()))
            delay(MIRROR_POLL_MS)
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

    private fun pose(n: Float, e: Float, d: Float, yaw: Float, health: String) = buildJsonObject {
        putJsonObject("pose") {
            putJsonArray("R") { listOf(cos(yaw), -sin(yaw), 0f, sin(yaw), cos(yaw), 0f, 0f, 0f, 1f).forEach { add(JsonPrimitive(it)) } }
            putJsonArray("t") { add(JsonPrimitive(n)); add(JsonPrimitive(e)); add(JsonPrimitive(d)) }
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

    private fun nav(searching: Boolean) = buildJsonObject {
        put("mode", "external_nav")
        put("active_set", 1)
        put("extnav_health", if (searching) "degraded" else "good")
        put("odom_rate_hz", 30.0)
        put("last_reset_counter", 0)
        put("obstacle_rate_hz", 10.0)
        put("min_obstacle_m", 1.8)
        put("gps_fix", 0)
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

    private val layers = DemoLayerFrames()

    override suspend fun visionDemand(body: JsonObject): AgentResult<JsonElement> {
        layers.demand(body, t())
        return AgentResult.Ok(buildJsonObject { put("detecting", true) })
    }

    override suspend fun visionStatus(): AgentResult<JsonObject> = AgentResult.Ok(layers.status())

    override suspend fun setDetector(modelId: String): AgentResult<JsonElement> {
        layers.activeModel = modelId
        return AgentResult.Ok(buildJsonObject { put("ok", true) })
    }

    override suspend fun designate(body: JsonObject): AgentResult<JsonElement> =
        AgentResult.Ok(buildJsonObject { put("designated", true) })

    override suspend fun putPluginConfig(pluginId: String, key: String, value: JsonElement): AgentResult<JsonElement> {
        if (key == "reacquire_mode") (value as? JsonPrimitive)?.content?.let { follow.reacquireMode = it }
        return AgentResult.Ok(buildJsonObject { put("ok", true) })
    }

    private companion object {
        const val PILOT_LOG_TAG = "DemoPilot"
        const val TICK_MS = 100L
        const val MIRROR_POLL_MS = 2_000L
        const val ORBIT_MS = 40_000L
        const val ROOM_N = 8f
        const val ROOM_E = 5f
        const val WALL_M = 0.15f
        const val RES_M = 0.05f
        const val CELLS = 64
        const val MAP_SEQ = 1L
    }
}

private val MIRROR_JSON = Json { ignoreUnknownKeys = true }

/** The mirror the demo drone's agent would serve: no plan items, the pilot's mission state (`none` when idle). */
internal fun demoMissionMirror(view: DemoPilotView): MissionMirror = MissionMirror(
    mission = runCatching { MIRROR_JSON.decodeFromJsonElement(MissionStatusWire.serializer(), view.mission) }.getOrNull()
        ?: MissionStatusWire(),
)
