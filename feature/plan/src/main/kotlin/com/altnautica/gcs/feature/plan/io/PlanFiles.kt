package com.altnautica.gcs.feature.plan.io

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.feature.plan.model.CollapsedItems
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.FlatItem
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import com.altnautica.gcs.feature.plan.model.MissionFlattener
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanHome
import com.altnautica.gcs.feature.plan.model.PlanWarning
import com.altnautica.gcs.feature.plan.model.RallyPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal

/** Interchange formats the planner reads and writes. */
enum class PlanFileFormat(val extension: String, val mimeType: String) {
    /** QGroundControl JSON plan: mission, geofence and rally points. */
    QgcPlan("plan", "application/json"),

    /** `QGC WPL 110` tab-separated waypoint list: mission only. */
    Waypoints("waypoints", "text/plain"),
    ;

    companion object {
        fun fromFileName(name: String): PlanFileFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }
    }
}

/** A plan read from a file plus non-fatal findings. */
data class ImportedPlan(val plan: MissionPlan, val warnings: List<PlanWarning>)

/** A file the planner cannot read; [reason] says why. */
class PlanFormatException(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason { NotWaypoints, NotPlan, Malformed, ComplexWithoutGeometry, ComplexWithoutAltitude }
}

/**
 * Reads and writes `.plan` and `.waypoints` files with the same numbering rules as the
 * web planner: mission rows count from 1, the home position sits in row 0 of a
 * `.waypoints` file and in `plannedHomePosition` of a `.plan`, and `DO_JUMP` targets use
 * that numbering.
 */
object PlanFiles {

    private const val WPL_HEADER = "QGC WPL 110"
    private const val GROUND_STATION = "ADOS GCS"
    private const val DEFAULT_CRUISE_SPEED = 15.0
    private const val FIRMWARE_ARDUPILOT = 3
    private const val VEHICLE_MULTIROTOR = 2

    private val json = Json { prettyPrint = true }

    // ── .waypoints ─────────────────────────────────────────────

    fun exportWaypoints(plan: MissionPlan): String {
        val home = homeOf(plan)
        val lines = ArrayList<String>()
        lines += WPL_HEADER
        lines += row(0, current = 1, MavFrame.GLOBAL, MavCmd.NAV_WAYPOINT, 0f, 0f, 0f, 0f, home.first.latDeg, home.first.lonDeg, home.second, true)
        for (it in MissionFlattener.flatten(plan.items)) {
            lines += row(it.seq, current = 0, it.frame, it.command, it.param1, it.param2, it.param3, it.param4, it.x, it.y, it.z, it.autoContinue)
        }
        return lines.joinToString("\n") + "\n"
    }

    fun parseWaypoints(text: String, nextId: () -> Long): ImportedPlan {
        val lines = text.trim().lines()
        if (lines.firstOrNull()?.trim()?.startsWith("QGC WPL") != true) {
            throw PlanFormatException(PlanFormatException.Reason.NotWaypoints)
        }
        var home: PlanHome? = null
        val rows = ArrayList<FlatItem>()
        for (raw in lines.drop(1)) {
            val cols = raw.trim().split('\t', ' ').filter { it.isNotEmpty() }
            if (cols.size < 12) continue
            val seq = cols[0].toIntOrNull() ?: (rows.size + 1)
            val x = cols[8].toDoubleOrNull()
            val y = cols[9].toDoubleOrNull()
            // A non-numeric lat/lon would place an item nowhere; skip the row.
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) continue
            if (seq == 0) {
                if (!(x == 0.0 && y == 0.0)) home = PlanHome(GeoPoint(x, y), cols[10].toFloatOrNull())
                continue
            }
            rows += FlatItem(
                seq = seq,
                command = cols[3].toIntOrNull() ?: MavCmd.NAV_WAYPOINT,
                frame = cols[2].toIntOrNull() ?: MavFrame.GLOBAL_RELATIVE_ALT,
                param1 = cols[4].finiteFloat(),
                param2 = cols[5].finiteFloat(),
                param3 = cols[6].finiteFloat(),
                param4 = cols[7].finiteFloat(),
                x = x,
                y = y,
                z = cols[10].finiteFloat(),
                autoContinue = (cols[11].toIntOrNull() ?: 1) != 0,
            )
        }
        val collapsed = MissionFlattener.collapse(rows, nextId)
        return ImportedPlan(MissionPlan(items = collapsed.items, home = home), collapsed.warnings)
    }

    // ── .plan ──────────────────────────────────────────────────

    fun exportPlan(plan: MissionPlan): String {
        val home = homeOf(plan)
        val root = buildJsonObject {
            put("fileType", "Plan")
            put("groundStation", GROUND_STATION)
            put("version", 1)
            putJsonObject("mission") {
                put("cruiseSpeed", plan.cruiseSpeedMs?.toDouble() ?: DEFAULT_CRUISE_SPEED)
                put("firmwareType", FIRMWARE_ARDUPILOT)
                putJsonArray("items") {
                    MissionFlattener.flatten(plan.items).forEach { item ->
                        addJsonObject {
                            put("autoContinue", item.autoContinue)
                            put("command", item.command)
                            put("doJumpId", item.seq)
                            put("frame", item.frame)
                            putJsonArray("params") {
                                listOf(item.param1, item.param2, item.param3, item.param4).forEach { add(jsonNumber(it)) }
                                add(jsonNumber(item.x))
                                add(jsonNumber(item.y))
                                add(jsonNumber(item.z))
                            }
                            put("type", "SimpleItem")
                        }
                    }
                }
                putJsonArray("plannedHomePosition") {
                    add(home.first.latDeg)
                    add(home.first.lonDeg)
                    add(home.second.toDouble())
                }
                put("vehicleType", VEHICLE_MULTIROTOR)
                put("version", 2)
            }
            putJsonObject("geoFence") {
                putJsonArray("circles") {
                    plan.fence.filterIsInstance<FenceZone.Circle>().forEach { c ->
                        addJsonObject {
                            put("inclusion", c.inclusion)
                            put("version", 1)
                            putJsonObject("circle") {
                                putJsonArray("center") {
                                    add(c.center.latDeg)
                                    add(c.center.lonDeg)
                                }
                                put("radius", c.radiusM)
                            }
                        }
                    }
                }
                putJsonArray("polygons") {
                    plan.fence.filterIsInstance<FenceZone.Polygon>().filter { it.points.size >= 3 }.forEach { p ->
                        addJsonObject {
                            put("inclusion", p.inclusion)
                            put("version", 1)
                            putJsonArray("polygon") {
                                p.points.forEach { pt ->
                                    addJsonArray {
                                        add(pt.latDeg)
                                        add(pt.lonDeg)
                                    }
                                }
                            }
                        }
                    }
                }
                put("version", 2)
            }
            putJsonObject("rallyPoints") {
                putJsonArray("points") {
                    plan.rally.forEach { r ->
                        addJsonArray {
                            add(r.position.latDeg)
                            add(r.position.lonDeg)
                            add(r.altM.toDouble())
                        }
                    }
                }
                put("version", 2)
            }
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun parsePlan(text: String, nextId: () -> Long): ImportedPlan {
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw PlanFormatException(PlanFormatException.Reason.Malformed, e.message)
        }
        val mission = root["mission"] as? JsonObject
        val items = mission?.get("items") as? JsonArray
        if (root.string("fileType") != "Plan" || items == null) throw PlanFormatException(PlanFormatException.Reason.NotPlan)

        val warnings = ArrayList<PlanWarning>()
        val jumpSeq = HashMap<Int, Int>()
        val rows = ArrayList<FlatItem>()
        items.forEach { expand(it.jsonObject, rows, jumpSeq, warnings) }
        val resolved = resolveJumps(rows, jumpSeq, warnings)
        val collapsed: CollapsedItems = MissionFlattener.collapse(resolved, nextId)
        val plan = MissionPlan(
            items = collapsed.items,
            fence = parseFence(root["geoFence"] as? JsonObject, nextId),
            rally = parseRally(root["rallyPoints"] as? JsonObject, nextId),
            home = plannedHome(mission["plannedHomePosition"] as? JsonArray),
            cruiseSpeedMs = mission.number("cruiseSpeed")?.toFloat(),
        )
        return ImportedPlan(plan, warnings + collapsed.warnings)
    }

    /** Expands a `.plan` item into flat rows numbered by output position (from 1). */
    private fun expand(item: JsonObject, out: MutableList<FlatItem>, jumpSeq: MutableMap<Int, Int>, warnings: MutableList<PlanWarning>) {
        item.number("doJumpId")?.toInt()?.let { jumpSeq[it] = out.size + 1 }
        when (val type = item.string("type")) {
            "SimpleItem" -> out += simpleItem(item, out.size + 1)
            "ComplexItem", "TransectStyleComplexItem" -> {
                val transect = (item["TransectStyleComplexItem"] as? JsonObject)
                    ?: item.takeIf { type == "TransectStyleComplexItem" }
                val embedded = transect?.get("Items") as? JsonArray
                if (embedded != null && embedded.isNotEmpty()) {
                    embedded.forEach { expand(it.jsonObject, out, jumpSeq, warnings) }
                    return
                }
                val visual = (transect?.get("VisualTransectPoints") ?: item["VisualTransectPoints"]) as? JsonArray
                if (visual != null && visual.isNotEmpty()) {
                    val calc = (transect?.get("CameraCalc") ?: item["CameraCalc"]) as? JsonObject
                    val (z, frame) = transectAltitude(calc)
                    visual.forEach { pt ->
                        val arr = pt as? JsonArray ?: return@forEach
                        val lat = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                        val lon = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                            out += FlatItem(out.size + 1, MavCmd.NAV_WAYPOINT, frame, 0f, 0f, 0f, 0f, lat, lon, z, true)
                        }
                    }
                    return
                }
                throw PlanFormatException(PlanFormatException.Reason.ComplexWithoutGeometry, item.string("complexItemType") ?: type)
            }
            else -> warnings += PlanWarning.SkippedItem(type ?: "none")
        }
    }

    private fun simpleItem(item: JsonObject, seq: Int): FlatItem {
        val params = (item["params"] as? JsonArray).orEmpty()
        fun param(i: Int): Double? = (params.getOrNull(i) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
        // QGC writes NaN ("leave unchanged") as null; keep it as NaN.
        fun paramF(i: Int): Float = param(i)?.toFloat() ?: Float.NaN
        return FlatItem(
            seq = seq,
            command = item.number("command")?.toInt() ?: MavCmd.NAV_WAYPOINT,
            frame = item.number("frame")?.toInt() ?: MavFrame.GLOBAL_RELATIVE_ALT,
            param1 = paramF(0),
            param2 = paramF(1),
            param3 = paramF(2),
            param4 = paramF(3),
            x = param(4)?.takeIf { it.isFinite() } ?: 0.0,
            y = param(5)?.takeIf { it.isFinite() } ?: 0.0,
            z = param(6)?.toFloat()?.takeIf { it.isFinite() } ?: 0f,
            autoContinue = (item["autoContinue"] as? JsonPrimitive)?.booleanOrNull != false,
        )
    }

    /** Flight height of a survey rebuilt from its visual points; a file without one is refused. */
    private fun transectAltitude(calc: JsonObject?): Pair<Float, Int> {
        val z = calc?.number("DistanceToSurface")
        if (z == null || !z.isFinite() || z <= 0) throw PlanFormatException(PlanFormatException.Reason.ComplexWithoutAltitude)
        val relative = (calc["DistanceToSurfaceRelative"] as? JsonPrimitive)?.booleanOrNull
        val mode = calc.number("DistanceMode")?.toInt() ?: if (relative == false) 2 else 1
        val frame = when (mode) {
            1 -> MavFrame.GLOBAL_RELATIVE_ALT
            2 -> MavFrame.GLOBAL
            4 -> MavFrame.GLOBAL_TERRAIN_ALT
            else -> throw PlanFormatException(PlanFormatException.Reason.ComplexWithoutAltitude)
        }
        return z.toFloat() to frame
    }

    /** Rewrites `DO_JUMP` targets from file `doJumpId` numbering into output rows; unknown targets are dropped. */
    private fun resolveJumps(rows: List<FlatItem>, jumpSeq: Map<Int, Int>, warnings: MutableList<PlanWarning>): List<FlatItem> {
        if (jumpSeq.isEmpty()) return rows
        return rows.mapNotNull { row ->
            if (row.command != MavCmd.DO_JUMP) return@mapNotNull row
            val target = jumpSeq[row.param1.toInt()]
            if (target == null) {
                warnings += PlanWarning.DroppedJump(row.seq, row.param1.toInt())
                null
            } else {
                row.copy(param1 = target.toFloat())
            }
        }
    }

    private fun parseFence(fence: JsonObject?, nextId: () -> Long): List<FenceZone> {
        if (fence == null) return emptyList()
        val zones = ArrayList<FenceZone>()
        (fence["circles"] as? JsonArray).orEmpty().forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val circle = obj["circle"] as? JsonObject ?: return@forEach
            val center = (circle["center"] as? JsonArray)?.toPoint() ?: return@forEach
            val radius = circle.number("radius")?.takeIf { it.isFinite() } ?: return@forEach
            zones += FenceZone.Circle(nextId(), inclusion = obj.bool("inclusion") != false, center = center, radiusM = radius)
        }
        (fence["polygons"] as? JsonArray).orEmpty().forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val points = (obj["polygon"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonArray)?.toPoint() }
            if (points.size >= 3) zones += FenceZone.Polygon(nextId(), inclusion = obj.bool("inclusion") != false, points = points)
        }
        return zones
    }

    private fun parseRally(rally: JsonObject?, nextId: () -> Long): List<RallyPoint> =
        (rally?.get("points") as? JsonArray).orEmpty().mapNotNull { entry ->
            val arr = entry as? JsonArray ?: return@mapNotNull null
            val point = arr.toPoint() ?: return@mapNotNull null
            val alt = arr.getOrNull(2)?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() } ?: 0.0
            RallyPoint(nextId(), point, alt.toFloat())
        }

    private fun plannedHome(position: JsonArray?): PlanHome? {
        val point = position?.toPoint() ?: return null
        if (point.latDeg == 0.0 && point.lonDeg == 0.0) return null
        val alt = position.getOrNull(2)?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }
        return PlanHome(point, alt?.toFloat())
    }

    // ── helpers ────────────────────────────────────────────────

    /** Home written into the file: the plan home, else the first located item, else 0/0. */
    private fun homeOf(plan: MissionPlan): Pair<GeoPoint, Float> {
        plan.home?.let { return it.position to (it.altMslM ?: 0f) }
        val first = plan.items.firstNotNullOfOrNull { it.position }
        return (first ?: GeoPoint(0.0, 0.0)) to 0f
    }

    private fun row(
        seq: Int,
        current: Int,
        frame: Int,
        command: Int,
        p1: Float,
        p2: Float,
        p3: Float,
        p4: Float,
        x: Double,
        y: Double,
        z: Float,
        autoContinue: Boolean,
    ): String = listOf(
        seq.toString(),
        current.toString(),
        frame.toString(),
        command.toString(),
        num(p1),
        num(p2),
        num(p3),
        num(p4),
        num(x),
        num(y),
        num(z),
        if (autoContinue) "1" else "0",
    ).joinToString("\t")

    /** Plain decimal text that parses back to the same value; NaN is written as 0. */
    private fun num(value: Double): String {
        if (!value.isFinite()) return "0"
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    private fun num(value: Float): String {
        if (!value.isFinite()) return "0"
        return BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }

    /** JSON number for a param; NaN becomes null as QGC writes it. */
    private fun jsonNumber(value: Double): JsonElement = if (value.isFinite()) JsonPrimitive(value) else JsonNull

    /** Float params go through their shortest decimal form so 0.1f is written as 0.1. */
    private fun jsonNumber(value: Float): JsonElement =
        if (value.isFinite()) JsonPrimitive(value.toString().toDouble()) else JsonNull

    private fun String.finiteFloat(): Float = toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonArray.toPoint(): GeoPoint? {
        val lat = getOrNull(0)?.jsonPrimitive?.doubleOrNull
        val lon = getOrNull(1)?.jsonPrimitive?.doubleOrNull
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return null
        return GeoPoint(lat, lon)
    }

}
