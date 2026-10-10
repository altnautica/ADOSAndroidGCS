package com.altnautica.gcs.core.extensions.marks

import com.altnautica.gcs.core.extensions.manifest.asArray
import com.altnautica.gcs.core.extensions.manifest.asObject
import com.altnautica.gcs.core.extensions.manifest.bool
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates of a mark: decoded-frame pixels, or 0..1 of the video rect. */
enum class MarkSpace { Frame, Normalized }

/** One vector mark an extension asks the host to draw over the video. */
sealed interface CockpitMark {
    val id: String
    val space: MarkSpace
    val color: String?

    data class Box(
        override val id: String, override val space: MarkSpace, override val color: String?,
        val x: Double, val y: Double, val width: Double, val height: Double, val dashed: Boolean,
    ) : CockpitMark

    data class Reticle(
        override val id: String, override val space: MarkSpace, override val color: String?,
        val x: Double, val y: Double, val width: Double, val height: Double,
    ) : CockpitMark

    data class Point(
        override val id: String, override val space: MarkSpace, override val color: String?,
        val x: Double, val y: Double, val radius: Double?,
    ) : CockpitMark

    data class Polyline(
        override val id: String, override val space: MarkSpace, override val color: String?,
        val points: List<Pair<Double, Double>>, val width: Double?,
    ) : CockpitMark

    data class Label(
        override val id: String, override val space: MarkSpace, override val color: String?,
        val x: Double, val y: Double, val text: String,
    ) : CockpitMark
}

/** The decoded video rect inside the overlay, in px, plus the decoded frame size. */
data class MarkFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val frameWidth: Int,
    val frameHeight: Int,
)

object CockpitMarksParser {
    const val MAX_MARKS_PER_SOURCE = 512
    const val MAX_POINTS_PER_POLYLINE = 1024
    const val MAX_POINTS_PER_SOURCE = 4096
    const val MAX_LABEL_CHARS = 64

    /** Untrusted payload → validated marks; malformed entries dropped, sizes capped. */
    fun parse(value: JsonElement?): List<CockpitMark> {
        val arr = value.asArray() ?: return emptyList()
        val out = ArrayList<CockpitMark>()
        var budget = MAX_POINTS_PER_SOURCE
        for (item in arr) {
            if (out.size >= MAX_MARKS_PER_SOURCE) break
            var mark = parseOne(item) ?: continue
            if (mark is CockpitMark.Polyline) {
                if (budget == 0) continue
                if (mark.points.size > budget) mark = mark.copy(points = mark.points.take(budget))
                budget -= mark.points.size
            }
            out += mark
        }
        return out
    }

    private fun n(v: JsonElement?): Double? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.doubleOrNull?.takeIf { it.isFinite() }
    }

    private fun parseOne(raw: JsonElement): CockpitMark? {
        val o = raw.asObject() ?: return null
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val spaceRaw = (o["space"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val space = if (spaceRaw == "normalized") MarkSpace.Normalized else MarkSpace.Frame
        val color = (o["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = (o["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (kind) {
            "box", "reticle" -> {
                val x = n(o["x"]) ?: return null
                val y = n(o["y"]) ?: return null
                val w = n(o["width"]) ?: return null
                val h = n(o["height"]) ?: return null
                if (kind == "box") {
                    CockpitMark.Box(id, space, color, x, y, w, h, o["dashed"].bool() == true)
                } else {
                    CockpitMark.Reticle(id, space, color, x, y, w, h)
                }
            }
            "point" -> {
                val x = n(o["x"]) ?: return null
                val y = n(o["y"]) ?: return null
                CockpitMark.Point(id, space, color, x, y, n(o["radius"]))
            }
            "polyline" -> {
                val pts = o["points"].asArray()?.takeIf { it.isNotEmpty() } ?: return null
                val points = ArrayList<Pair<Double, Double>>()
                for (p in pts.take(MAX_POINTS_PER_POLYLINE)) {
                    val a = p.asArray() ?: return null
                    if (a.size < 2) return null
                    points += (n(a[0]) ?: return null) to (n(a[1]) ?: return null)
                }
                CockpitMark.Polyline(id, space, color, points, n(o["width"]))
            }
            "label" -> {
                val x = n(o["x"]) ?: return null
                val y = n(o["y"]) ?: return null
                val text = (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                CockpitMark.Label(id, space, color, x, y, text.take(MAX_LABEL_CHARS))
            }
            else -> null
        }
    }

    /** Map a mark point into overlay px; null when frame space has no known frame. */
    fun mapPoint(x: Double, y: Double, space: MarkSpace, w: Float, h: Float, frame: MarkFrame?): Pair<Float, Float>? {
        if (space == MarkSpace.Normalized) return (x * w).toFloat() to (y * h).toFloat()
        if (frame == null || frame.frameWidth <= 0 || frame.frameHeight <= 0) return null
        val sx = frame.width / frame.frameWidth
        val sy = frame.height / frame.frameHeight
        return (frame.left + x * sx).toFloat() to (frame.top + y * sy).toFloat()
    }
}

/** Marks per source (`plugin:<id>:<mount>`) per node. */
@Singleton
class CockpitMarksStore @Inject constructor() {
    private val sources = MutableStateFlow<Map<DeviceId, Map<String, List<CockpitMark>>>>(emptyMap())

    fun marks(node: DeviceId): Flow<List<CockpitMark>> = sources.map { it[node].orEmpty().values.flatten() }

    fun set(node: DeviceId, source: String, marks: List<CockpitMark>) = sources.update { all ->
        all + (node to (all[node].orEmpty() + (source to marks)))
    }

    fun clear(node: DeviceId, source: String) = sources.update { all ->
        val m = all[node] ?: return@update all
        all + (node to (m - source))
    }
}
