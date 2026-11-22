package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.tokens.AdosCockpitColors
import com.altnautica.gcs.core.designsystem.tokens.AdosHudTarget
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.MoverKind
import com.altnautica.gcs.core.model.TrackedMover
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosFonts
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/**
 * How the minimaps draw the pilot's tracked movers, the same as every surface: a marker shaped
 * and coloured by kind (a person a disc in the avoiding hue, a vehicle a square in the blocked
 * hue, anything else a triangle in the stale grey), a velocity arrow over [ARROW_S] seconds of
 * travel, a ring at the one-sigma uncertainty, all faded while the mover has not been measured
 * for over a second; and the line "Movers: 3 · nearest person 7.4 m".
 */
internal object MinimapMovers {
    /** The velocity arrow shows this many seconds of travel. */
    const val ARROW_S = 2f

    /** Slower than this (m/s) a mover draws no arrow. */
    const val MIN_ARROW_MPS = 0.1f

    /** Alpha of a mover not measured for over a second. */
    const val STALE_ALPHA = 0.4f

    /**
     * The arrow's tip on a north-up map at [pxPerM], the mover drawn at [p]: [ARROW_S] seconds
     * of travel; [Offset.Unspecified] when the mover is all but still.
     */
    fun arrowTip(m: TrackedMover, p: Offset, pxPerM: Float): Offset {
        if (hypot(m.velNorthMps, m.velEastMps) < MIN_ARROW_MPS) return Offset.Unspecified
        return Offset(p.x + m.velEastMps * ARROW_S * pxPerM, p.y - m.velNorthMps * ARROW_S * pxPerM)
    }

    /** The uncertainty ring's radius in pixels, or 0 while it would sit inside the marker of radius [markerPx]. */
    fun ringPx(m: TrackedMover, pxPerM: Float, markerPx: Float): Float = (m.sigmaM * pxPerM).takeIf { it > markerPx } ?: 0f

    /** The mover's alpha: full while measured within the last second, faded after. */
    fun alpha(m: TrackedMover): Float = if (m.stale) STALE_ALPHA else 1f

    /** The horizontal distance (m) from the drone at ([north], [east]) to the nearest person, or null with none. */
    fun nearestPersonM(movers: List<TrackedMover>, north: Float, east: Float): Float? =
        movers.filter { it.kind == MoverKind.Person }.minOfOrNull { hypot(it.northM - north, it.eastM - east) }

    /**
     * `Movers: 3 · nearest person 7.4 m` (`Movers: 3` without a person or a drone position
     * [north]/[east]), worded through [text] (`pilot.movers.count*`); null while none is tracked.
     */
    fun line(movers: List<TrackedMover>, north: Float?, east: Float?, text: (String, Map<String, String>) -> String): String? {
        if (movers.isEmpty()) return null
        val n = movers.size.toString()
        val d = if (north != null && east != null) nearestPersonM(movers, north, east) else null
        return if (d == null) {
            text("pilot.movers.count", mapOf("n" to n))
        } else {
            text("pilot.movers.count_person", mapOf("n" to n, "d" to String.format(java.util.Locale.ROOT, "%.1f", d)))
        }
    }

    /** The flight controller's local position older than this (ms) places nothing. */
    const val LOCAL_POSITION_STALE_MS = 2_000L

    /** [p] while it is at most [LOCAL_POSITION_STALE_MS] old at [nowMs] (same monotonic time base), else null. */
    fun freshLocal(p: LocalPosition?, nowMs: Long): LocalPosition? = p?.takeIf { nowMs - it.tMs <= LOCAL_POSITION_STALE_MS }

    /**
     * The local minimap's movers line: the nearest person measured from the drone as drawn (the
     * fresh pose, else a fresh flight-controller position); with neither, the count only.
     */
    fun line(inputs: LocalMapInputs, text: (String, Map<String, String>) -> String): String? {
        val pose = inputs.freshPose
        val fc = freshLocal(inputs.fcPosition, inputs.nowMs)
        val north = pose?.northM ?: fc?.northM
        val east = pose?.eastM ?: fc?.eastM
        return line(inputs.movers, north, east, text)
    }
}

/** Draws tracked movers for [MinimapPainter]; keeps its own path and strokes so a draw allocates nothing per mover. */
internal class MoverPainter {
    private val path = Path()
    private var strokeDensity = 0f
    private var thin = Stroke(1f)
    private var line = Stroke(1.5f)
    private var outline = Stroke(3.5f)

    private fun colour(kind: MoverKind): Color = when (kind) {
        MoverKind.Person -> AdosCockpitColors.StateAvoiding
        MoverKind.Vehicle -> AdosCockpitColors.StateBlocked
        MoverKind.Unknown -> AdosCockpitColors.StateStale
    }

    /** Draws [m] at screen point [p] on a north-up map at [pxPerM]. */
    fun DrawScope.mover(m: TrackedMover, p: Offset, pxPerM: Float) {
        if (density != strokeDensity) {
            strokeDensity = density
            thin = Stroke(1.dp.toPx())
            line = Stroke(1.5.dp.toPx())
            outline = Stroke(3.5.dp.toPx())
        }
        val colour = colour(m.kind)
        val a = MinimapMovers.alpha(m)
        val s = 4.dp.toPx()
        val ring = MinimapMovers.ringPx(m, pxPerM, s)
        if (ring > 0f) {
            drawCircle(colour, ring, p, alpha = 0.12f * a)
            drawCircle(colour, ring, p, alpha = 0.6f * a, style = thin)
        }
        val tip = MinimapMovers.arrowTip(m, p, pxPerM)
        if (tip.isSpecified) arrow(p, tip, colour, a)
        when (m.kind) {
            MoverKind.Person -> {
                drawCircle(AdosHudTarget.OutlineColor, s, p, alpha = a, style = outline)
                drawCircle(colour, s, p, alpha = a)
            }
            MoverKind.Vehicle -> {
                val tl = Offset(p.x - s, p.y - s)
                val size = Size(2 * s, 2 * s)
                drawRect(AdosHudTarget.OutlineColor, tl, size, alpha = a, style = outline)
                drawRect(colour, tl, size, alpha = a)
            }
            MoverKind.Unknown -> {
                path.rewind()
                path.moveTo(p.x, p.y - s * 1.2f); path.lineTo(p.x + s, p.y + s * 0.8f); path.lineTo(p.x - s, p.y + s * 0.8f); path.close()
                drawPath(path, AdosHudTarget.OutlineColor, alpha = a, style = outline)
                drawPath(path, colour, alpha = a)
            }
        }
    }

    /** A line from [from] to [to] with a head at [to]. */
    private fun DrawScope.arrow(from: Offset, to: Offset, colour: Color, a: Float) {
        drawLine(AdosHudTarget.OutlineColor, from, to, outline.width, alpha = a)
        drawLine(colour, from, to, line.width, alpha = a)
        val th = atan2(to.y - from.y, to.x - from.x)
        val head = 5.dp.toPx()
        drawLine(colour, to, Offset(to.x - head * cos(th + HEAD_RAD), to.y - head * sin(th + HEAD_RAD)), line.width, alpha = a)
        drawLine(colour, to, Offset(to.x - head * cos(th - HEAD_RAD), to.y - head * sin(th - HEAD_RAD)), line.width, alpha = a)
    }

    private companion object {
        /** The arrowhead's barbs, 28° off the shaft. */
        const val HEAD_RAD = 0.49f
    }
}

/** Test tag of a map's movers line. */
const val MOVERS_LINE_TAG = "movers-line"

/** A map's movers line ([MinimapMovers.line]) on glass; nothing while the pilot tracks none. */
@Composable
internal fun MoversLine(text: String?, modifier: Modifier = Modifier) {
    text ?: return
    val c = AdosTheme.colors
    Text(
        text,
        style = AdosTypography.numeric.small.copy(fontFamily = AdosFonts.Inter, fontSize = 10.sp),
        color = c.hudInk,
        maxLines = 1,
        modifier = modifier
            .padding(AdosSpacing.S1)
            .background(c.hudGlass, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(MOVERS_LINE_TAG),
    )
}
