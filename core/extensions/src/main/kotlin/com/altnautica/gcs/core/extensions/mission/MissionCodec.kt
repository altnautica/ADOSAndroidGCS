package com.altnautica.gcs.core.extensions.mission

import com.altnautica.gcs.core.link.mission.MissionItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

/** One action attached after a waypoint (`DO_*` / `CONDITION_*`). */
data class WaypointAction(
    val id: String,
    val command: String,
    val params: Map<String, Double>,
    val jumpTargetId: String?,
)

/** The waypoint shape extensions read and write. Altitude is in the waypoint's frame. */
data class ExtWaypoint(
    val id: String,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val command: String? = null,
    val frame: String? = null,
    val speed: Double? = null,
    val holdTime: Double? = null,
    val param1: Double? = null,
    val param2: Double? = null,
    val param3: Double? = null,
    val actions: List<WaypointAction> = emptyList(),
)

/** Validation, expansion to MAVLink items and collapse back, for extension missions. */
object MissionCodec {
    val NAV = mapOf(
        "WAYPOINT" to 16, "SPLINE_WAYPOINT" to 82, "LOITER" to 17, "LOITER_TURNS" to 18, "LOITER_TIME" to 19,
        "RTL" to 20, "LAND" to 21, "TAKEOFF" to 22, "NAV_PAYLOAD_PLACE" to 94, "VTOL_TAKEOFF" to 84,
        "VTOL_LAND" to 85, "DO_LAND_START" to 189,
    )
    val ACTIONS = mapOf(
        "ROI" to 201, "DO_SET_SPEED" to 178, "DO_SET_CAM_TRIGG" to 206, "DO_DIGICAM" to 203, "DO_JUMP" to 177,
        "DELAY" to 112, "CONDITION_YAW" to 115, "DO_SET_SERVO" to 183, "DO_FENCE_ENABLE" to 207,
        "DO_MOUNT_CONTROL" to 205, "DO_GRIPPER" to 211, "DO_WINCH" to 42600, "CONDITION_DISTANCE" to 114,
        "DO_SET_HOME" to 179, "DO_AUX_FUNCTION" to 218, "DO_SET_ROI_NONE" to 197,
    )
    private val POSITIONAL = setOf("ROI", "DO_SET_HOME")
    private val ZERO_MEANS_HERE = setOf("RTL", "TAKEOFF", "LAND", "LOITER", "LOITER_TIME", "LOITER_TURNS", "VTOL_TAKEOFF", "VTOL_LAND")
    private val ACTION_NUMBERS = listOf("param1", "param2", "param3", "param4", "param5", "param6", "param7", "lat", "lon", "alt")

    const val FRAME_GLOBAL_INT = 5
    const val FRAME_RELATIVE_ALT_INT = 6
    const val FRAME_TERRAIN_ALT_INT = 11
    private const val SPEED_TYPE_GROUND = 1f
    private const val THROTTLE_NO_CHANGE = -1f

    fun frameToMav(frame: String?): Int = when (frame) {
        "absolute" -> FRAME_GLOBAL_INT
        "terrain" -> FRAME_TERRAIN_ALT_INT
        else -> FRAME_RELATIVE_ALT_INT
    }

    fun mavToFrame(mav: Int): String = when (mav) {
        0, FRAME_GLOBAL_INT -> "absolute"
        10, FRAME_TERRAIN_ALT_INT -> "terrain"
        else -> "relative"
    }

    private fun num(o: JsonObject, k: String): Result<Double?> {
        val v = o[k] ?: return Result.success(null)
        val p = v as? JsonPrimitive
        val d = if (p == null || p.isString) null else p.doubleOrNull?.takeIf { it.isFinite() }
        return if (d == null) Result.failure(IllegalArgumentException("$k must be a finite number")) else Result.success(d)
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Validate an untrusted `waypoints` array; the error names the first offending field. */
    fun sanitize(raw: JsonElement?): Result<List<ExtWaypoint>> {
        val arr = raw as? JsonArray
        if (arr == null || arr.isEmpty()) return fail("payload.waypoints must be a non-empty array")
        val out = ArrayList<ExtWaypoint>()
        for ((i, e) in arr.withIndex()) {
            val where = "waypoint $i"
            val w = e as? JsonObject ?: return fail("$where is not an object")
            val id = str(w, "id")?.takeIf { it.isNotEmpty() } ?: return fail("$where needs a string id")
            val lat = num(w, "lat").getOrNull()
            val lon = num(w, "lon").getOrNull()
            val alt = num(w, "alt").getOrNull()
            if (lat == null || lon == null || alt == null) return fail("$where needs finite lat, lon and alt")
            if (abs(lat) > 90 || abs(lon) > 180) return fail("$where coordinates are out of range")
            val command = w["command"]?.let { str(w, "command")?.takeIf { c -> c in NAV } ?: return fail("$where command ${w["command"]} is not permitted") }
            val frame = w["frame"]?.let {
                str(w, "frame")?.takeIf { f -> f == "relative" || f == "absolute" || f == "terrain" }
                    ?: return fail("$where frame $it is not an altitude frame")
            }
            val numbers = HashMap<String, Double?>()
            for (k in listOf("speed", "holdTime", "param1", "param2", "param3")) {
                numbers[k] = num(w, k).getOrElse { return fail("$where ${it.message}") }
            }
            val actions = ArrayList<WaypointAction>()
            w["actions"]?.let { a ->
                val list = a as? JsonArray ?: return fail("$where actions must be an array")
                for (ra in list) {
                    val act = ra as? JsonObject ?: return fail("$where: action is not an object")
                    val aid = str(act, "id")?.takeIf { it.isNotEmpty() } ?: return fail("$where: action needs a string id")
                    val cmd = str(act, "command")?.takeIf { it in ACTIONS }
                        ?: return fail("$where: action command ${act["command"]} is not permitted")
                    val params = HashMap<String, Double>()
                    for (k in ACTION_NUMBERS) {
                        num(act, k).getOrElse { return fail("$where: action ${it.message}") }?.let { params[k] = it }
                    }
                    val jump = act["jumpTargetId"]?.let { str(act, "jumpTargetId") ?: return fail("$where: jumpTargetId must be a string") }
                    actions += WaypointAction(aid, cmd, params, jump)
                }
            }
            out += ExtWaypoint(
                id, lat, lon, alt, command, frame,
                numbers["speed"], numbers["holdTime"], numbers["param1"], numbers["param2"], numbers["param3"], actions,
            )
        }
        return Result.success(out)
    }

    private fun fail(message: String): Result<List<ExtWaypoint>> = Result.failure(IllegalArgumentException(message))

    private sealed interface Slot {
        data class Nav(val wp: ExtWaypoint) : Slot
        data class Action(val act: WaypointAction, val frame: Int) : Slot
        data class Speed(val speed: Double, val frame: Int) : Slot
    }

    /**
     * Expand waypoints into operator items (no home slot): a ground-speed item precedes a
     * leg whose speed changes, actions follow their waypoint, `DO_JUMP` targets resolve to
     * item indices and a jump to an unknown waypoint is dropped.
     */
    fun expand(waypoints: List<ExtWaypoint>): List<MissionItem> {
        val ids = waypoints.map { it.id }.toSet()
        val slots = ArrayList<Slot>()
        var current: Double? = null
        waypoints.forEachIndexed { i, wp ->
            val frame = frameToMav(wp.frame)
            if (i == 0 || wp.speed != null) {
                val want = wp.speed
                if (want != null && want > 0 && want != current) {
                    slots += Slot.Speed(want, frame)
                    current = want
                }
            }
            slots += Slot.Nav(wp)
            if (wp.actions.any { it.command == "DO_SET_SPEED" }) current = null
            for (a in wp.actions) {
                if (a.command == "DO_JUMP" && (a.jumpTargetId == null || a.jumpTargetId !in ids)) continue
                slots += Slot.Action(a, frame)
            }
        }
        val seqById = HashMap<String, Int>()
        slots.forEachIndexed { i, s ->
            if (s is Slot.Nav) {
                val prevSpeed = slots.getOrNull(i - 1) is Slot.Speed
                seqById[s.wp.id] = if (prevSpeed) i - 1 else i
            }
        }
        return slots.map { s ->
            when (s) {
                is Slot.Speed -> MissionItem(
                    command = ACTIONS.getValue("DO_SET_SPEED"), frame = s.frame,
                    param1 = SPEED_TYPE_GROUND, param2 = s.speed.toFloat(), param3 = THROTTLE_NO_CHANGE,
                )
                is Slot.Nav -> MissionItem.global(
                    command = NAV[s.wp.command ?: "WAYPOINT"] ?: 16,
                    latDeg = s.wp.lat,
                    lonDeg = s.wp.lon,
                    altM = s.wp.alt.toFloat(),
                    param1 = (s.wp.holdTime ?: 0.0).toFloat(),
                    param2 = (s.wp.param1 ?: 0.0).toFloat(),
                    param3 = (s.wp.param2 ?: 0.0).toFloat(),
                    param4 = (s.wp.param3 ?: 0.0).toFloat(),
                    frame = frameToMav(s.wp.frame),
                )
                is Slot.Action -> {
                    val a = s.act
                    val p = a.params
                    val positional = a.command in POSITIONAL
                    MissionItem(
                        command = ACTIONS.getValue(a.command),
                        frame = s.frame,
                        param1 = if (a.command == "DO_JUMP") seqById.getValue(a.jumpTargetId!!).toFloat() else (p["param1"] ?: 0.0).toFloat(),
                        param2 = (p["param2"] ?: 0.0).toFloat(),
                        param3 = (p["param3"] ?: 0.0).toFloat(),
                        param4 = (p["param4"] ?: 0.0).toFloat(),
                        x = if (positional) Math.round((p["lat"] ?: 0.0) * 1e7).toInt() else (p["param5"] ?: 0.0).roundToInt(),
                        y = if (positional) Math.round((p["lon"] ?: 0.0) * 1e7).toInt() else (p["param6"] ?: 0.0).roundToInt(),
                        z = (if (positional) p["alt"] else p["param7"])?.toFloat() ?: 0f,
                    )
                }
            }
        }
    }

    /** Collapse vehicle items back into waypoints; ground-speed items fold into the next leg. */
    fun collapse(items: List<MissionItem>): JsonArray {
        val navByName = NAV.entries.associate { (k, v) -> v to k }
        val actByName = ACTIONS.entries.associate { (k, v) -> v to k }
        val wps = ArrayList<JsonObject>()
        val pendingActions = ArrayList<JsonObject>()
        var pendingSpeed: Double? = null
        var last: MutableMap<String, JsonElement>? = null
        // A jump lands on a nav item, or on the speed item that opens its leg.
        val navIdAt = HashMap<Int, String>()
        items.forEachIndexed { seq, item ->
            if (item.command in navByName) {
                navIdAt[seq] = "wp-$seq"
                if (seq > 0 && items[seq - 1].command == 178) navIdAt[seq - 1] = "wp-$seq"
            }
        }
        fun flush() {
            last?.let { m ->
                if (pendingActions.isNotEmpty()) m["actions"] = JsonArray(pendingActions.toList())
                wps += JsonObject(m)
            }
            pendingActions.clear()
        }
        items.forEachIndexed { seq, item ->
            val nav = navByName[item.command]
            if (nav != null) {
                flush()
                val here = nav in ZERO_MEANS_HERE && item.x == 0 && item.y == 0
                last = linkedMapOf<String, JsonElement>(
                    "id" to JsonPrimitive("wp-$seq"),
                    "lat" to JsonPrimitive(item.latDeg),
                    "lon" to JsonPrimitive(item.lonDeg),
                    "alt" to JsonPrimitive(item.z),
                    "command" to JsonPrimitive(nav),
                    "frame" to JsonPrimitive(mavToFrame(item.frame)),
                    "holdTime" to JsonPrimitive(item.param1),
                    "param1" to JsonPrimitive(item.param2),
                    "param2" to JsonPrimitive(item.param3),
                    "param3" to JsonPrimitive(item.param4),
                ).also { m ->
                    pendingSpeed?.let { m["speed"] = JsonPrimitive(it) }
                    if (here) m["inheritsPosition"] = JsonPrimitive(true)
                }
                pendingSpeed = null
                return@forEachIndexed
            }
            val isSpeed = item.command == 178 && item.param1 == SPEED_TYPE_GROUND && item.param2 > 0 &&
                item.param3 == THROTTLE_NO_CHANGE && item.x == 0 && item.y == 0 && item.z == 0f
            if (isSpeed && seq < items.lastIndex) {
                pendingSpeed = item.param2.toDouble()
                return@forEachIndexed
            }
            val name = actByName[item.command] ?: "RAW"
            pendingActions += buildJsonObject {
                put("id", "act-$seq")
                put("command", name)
                if (name == "RAW") put("rawCommand", item.command)
                if (name == "DO_JUMP") navIdAt[item.param1.toInt()]?.let { put("jumpTargetId", it) }
                put("param1", item.param1)
                put("param2", item.param2)
                put("param3", item.param3)
                put("param4", item.param4)
                if (name in POSITIONAL) {
                    put("lat", item.latDeg)
                    put("lon", item.lonDeg)
                    put("alt", item.z)
                } else {
                    put("param5", item.x)
                    put("param6", item.y)
                    put("param7", item.z)
                }
            }
        }
        flush()
        return buildJsonArray { wps.forEach { add(it) } }
    }
}
