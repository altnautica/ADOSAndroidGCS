package com.altnautica.gcs.core.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.createBitmap

/** What a marker image looks like; [id] names it inside the map style. */
internal sealed interface IconSpec {
    val id: String

    data class Waypoint(val kind: WaypointKind, val selected: Boolean, val label: String) : IconSpec {
        override val id = "wp:${kind.name}:$selected:$label"
    }

    data class Rally(val selected: Boolean) : IconSpec {
        override val id = "rally:$selected"
    }

    data object Home : IconSpec {
        override val id = MapIconIds.HOME
    }

    data object Handle : IconSpec {
        override val id = MapIconIds.HANDLE
    }

    data class Vehicle(val heading: Boolean, val stale: Boolean) : IconSpec {
        override val id = "vehicle:$heading:$stale"
    }
}

/** Collects the icons a GeoJSON update references so the map can register missing images. */
internal class MapIconIds {
    val requested = LinkedHashMap<String, IconSpec>()

    private fun request(spec: IconSpec): String {
        requested.getOrPut(spec.id) { spec }
        return spec.id
    }

    fun waypoint(wp: MapWaypoint) = request(IconSpec.Waypoint(wp.kind, wp.selected, wp.label))

    fun rally(selected: Boolean) = request(IconSpec.Rally(selected))

    fun vehicle(v: MapVehicle) = request(IconSpec.Vehicle(heading = v.headingDeg != null, stale = v.stale))

    init {
        request(IconSpec.Home)
        request(IconSpec.Handle)
    }

    companion object {
        const val HOME = "home"
        const val HANDLE = "handle"
    }
}

/** Marker colours as ARGB ints, resolved from the active theme. */
internal data class IconPalette(
    val nav: Int,
    val takeoff: Int,
    val land: Int,
    val returnHome: Int,
    val loiter: Int,
    val roi: Int,
    val selected: Int,
    val home: Int,
    val rally: Int,
    val vehicle: Int,
    val vehicleStale: Int,
    val ink: Int,
    val outline: Int,
)

/** Draws marker bitmaps; sizes are in device pixels derived from [density]. */
internal class MapIconFactory(private val density: Float, private val palette: IconPalette) {

    fun create(spec: IconSpec): Bitmap = when (spec) {
        is IconSpec.Waypoint -> badge(
            fill = waypointColor(spec.kind),
            label = spec.label,
            diameterDp = if (spec.selected) 34f else 28f,
            selected = spec.selected,
        )
        is IconSpec.Rally -> badge(palette.rally, "R", if (spec.selected) 32f else 26f, spec.selected)
        IconSpec.Home -> badge(palette.home, "H", 28f, selected = false)
        IconSpec.Handle -> handle()
        is IconSpec.Vehicle -> vehicle(spec)
    }

    private fun waypointColor(kind: WaypointKind) = when (kind) {
        WaypointKind.Nav -> palette.nav
        WaypointKind.Takeoff -> palette.takeoff
        WaypointKind.Land -> palette.land
        WaypointKind.ReturnHome -> palette.returnHome
        WaypointKind.Loiter -> palette.loiter
        WaypointKind.RegionOfInterest -> palette.roi
    }

    private fun px(dp: Float) = dp * density

    private fun badge(fill: Int, label: String, diameterDp: Float, selected: Boolean): Bitmap {
        val size = px(diameterDp).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val r = size / 2f
        val stroke = px(if (selected) 3f else 2f)
        canvas.drawCircle(r, r, r - stroke / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        canvas.drawCircle(
            r,
            r,
            r - stroke / 2,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                color = if (selected) palette.selected else palette.outline
            },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = px(if (label.length > 2) 10f else 12f)
        }
        val baseline = r - (text.descent() + text.ascent()) / 2
        canvas.drawText(label, r, baseline, text)
        return bitmap
    }

    private fun handle(): Bitmap {
        val size = px(18f).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val r = size / 2f
        canvas.drawCircle(r, r, r - px(1f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink })
        canvas.drawCircle(
            r,
            r,
            r - px(2f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = px(2f)
                color = palette.outline
            },
        )
        return bitmap
    }

    private fun vehicle(spec: IconSpec.Vehicle): Bitmap {
        val size = px(36f).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (spec.stale) palette.vehicleStale else palette.vehicle }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px(2f)
            strokeJoin = Paint.Join.ROUND
            color = palette.outline
        }
        val c = size / 2f
        if (spec.heading) {
            // Arrow pointing to the top of the bitmap (north before rotation).
            val path = Path().apply {
                moveTo(c, px(3f))
                lineTo(size - px(6f), size - px(5f))
                lineTo(c, size - px(11f))
                lineTo(px(6f), size - px(5f))
                close()
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, outline)
        } else {
            canvas.drawCircle(c, c, px(9f), fill)
            canvas.drawCircle(c, c, px(9f), outline)
        }
        return bitmap
    }
}
