package com.altnautica.gcs.feature.cockpit.hud

import android.util.SparseArray
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.cockpit.Geo
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private const val DASH = "—"

/** MAV_TYPE_FIXED_WING. */
private const val MAV_TYPE_FIXED_WING = 1

/** How far above the reticle the heading readout's bottom edge sits. */
internal val HEADING_READOUT_LIFT = 64.dp

/** The heading readout's height with its plate. */
internal val HEADING_READOUT_HEIGHT = 20.dp

/**
 * Text layouts keyed by an integer, each measured once; bounded so a long flight's readouts
 * do not pile up. A steady draw looks its labels up here and allocates nothing.
 */
private class IntLabels(private val measurer: TextMeasurer, private val style: TextStyle, private val text: (Int) -> String) {
    private val cache = SparseArray<TextLayoutResult>()

    operator fun get(key: Int): TextLayoutResult {
        cache.get(key)?.let { return it }
        if (cache.size() >= MAX_LABELS) cache.clear()
        return measurer.measure(text(key), style).also { cache.put(key, it) }
    }

    private companion object {
        const val MAX_LABELS = 512
    }
}

/** A heading in whole degrees as three digits ("007"). */
private fun threeDigits(deg: Int): String = deg.toString().padStart(3, '0')

/** Tenths as a one-decimal number ("12.3", "-0.5"). */
private fun tenthsText(tenths: Int): String {
    val a = abs(tenths)
    return (if (tenths < 0) "-" else "") + (a / 10) + "." + (a % 10)
}

/**
 * Draws the instruments from the current telemetry values. One instance per theme keeps its
 * label layouts, paths, strokes and dash effect, so a draw with unchanged telemetry allocates
 * nothing: readouts are keyed by their integer value (whole degrees, tenths) and laid out once.
 */
internal class HudPainter(
    private val measurer: TextMeasurer,
    private val colors: AdosColorRoles,
    small: TextStyle,
    medium: TextStyle,
    /** The plate under the heading readout: the chrome panel tint at the small chrome radius. */
    private val plate: Color = Color(0xEB0B0D12),
    private val plateRadius: Dp = 4.dp,
) {
    private val smallStyle = small.copy(color = colors.hudInk)
    private val mediumStyle = medium.copy(color = colors.hudInk)
    private val dimSmall = small.copy(color = colors.hudMuted)
    private val dimMedium = medium.copy(color = colors.hudMuted)
    private val path = Path()
    private val rungDash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    private var outline = Stroke(1f)

    private val numbers = IntLabels(measurer, smallStyle) { it.toString() }
    private val headingTicks = IntLabels(measurer, smallStyle) { HudMath.headingLabel(it) }
    private val headingValue = IntLabels(measurer, mediumStyle, ::threeDigits)
    private val centreHeading = IntLabels(measurer, smallStyle) { threeDigits(it) + "°" }
    private val centreHeadingDim = IntLabels(measurer, dimSmall) { threeDigits(it) + "°" }
    private val tapeValue = IntLabels(measurer, mediumStyle, ::tenthsText)
    private val windSpeed = IntLabels(measurer, smallStyle) { "$it m/s" }
    private val captions = HashMap<String, TextLayoutResult>()
    private val dimCaptions = HashMap<String, TextLayoutResult>()
    private val dashMedium by lazy { measurer.measure(DASH, dimMedium) }

    /** The home bearing, recomputed only when the position or home sample changes. */
    private var bearingPos: Any? = null
    private var bearingHome: Any? = null
    private var homeBearing = Float.NaN

    private fun caption(text: String, dim: Boolean): TextLayoutResult =
        if (dim) dimCaptions.getOrPut(text) { measurer.measure(text, dimSmall) } else captions.getOrPut(text) { measurer.measure(text, smallStyle) }

    /** The one outline stroke, rebuilt only when its width changes (a new density). */
    private fun outline(width: Float): Stroke {
        if (outline.width != width) outline = Stroke(width)
        return outline
    }

    /**
     * The centre: always the reticle and the small heading and horizon marks; with
     * [instruments] the flight instruments as well (pitch ladder, roll arc, flight-path
     * marker, speed and altitude tapes, vertical-speed bar, heading tape and wind).
     */
    fun DrawScope.paint(t: VehicleTelemetry?, altRef: AltitudeReference, density: CockpitDensity, now: Long, instruments: Boolean = true) {
        val stroke = 1.5.dp.toPx()
        val cx = size.width / 2
        val cy = size.height / 2
        val ppd = size.height / HudMath.VISIBLE_PITCH_SPAN_DEG

        val att = t?.attitude?.value?.takeIf { HudMath.isFresh(it.tMs, now) }
        val pos = t?.position?.value
        val posFresh = pos != null && HudMath.isFresh(pos.tMs, now)
        val vfr = t?.vfr?.value?.takeIf { HudMath.isFresh(it.tMs, now) }
        val hb = t?.heartbeat?.value
        val home = t?.home?.value

        // Unknown values are NaN rather than null, so no reading is boxed.
        val rollDeg = if (att != null) Math.toDegrees(att.rollRad.toDouble()).toFloat() else 0f
        val pitchDeg = if (att != null) Math.toDegrees(att.pitchRad.toDouble()).toFloat() else 0f
        val headingDeg = when {
            vfr != null -> vfr.headingDeg
            att != null -> HudMath.wrap360(Math.toDegrees(att.yawRad.toDouble()).toFloat())
            else -> Float.NaN
        }

        if (!instruments) {
            centreMarks(att != null, rollDeg, pitchDeg, headingDeg, ppd, stroke)
            return
        }
        // Horizon and pitch ladder.
        val lineColor = if (att != null) colors.hudPrimary else colors.hudMuted
        clipRect(0f, size.height * 0.12f, size.width, size.height * 0.88f) {
            rotate(-rollDeg, Offset(cx, cy)) {
                translate(0f, pitchDeg * ppd) {
                    drawLine(lineColor, Offset(cx - size.width, cy), Offset(cx - size.width * 0.08f, cy), stroke * 1.5f)
                    drawLine(lineColor, Offset(cx + size.width * 0.08f, cy), Offset(cx + size.width, cy), stroke * 1.5f)
                    if (att != null && density != CockpitDensity.Min) {
                        var rung = HudMath.ladderLow(pitchDeg)
                        val last = HudMath.ladderHigh(pitchDeg)
                        while (rung <= last) {
                            if (rung != 0 && rung >= -90 && rung <= 90) {
                                val y = cy - rung * ppd
                                val major = rung % 10 == 0
                                val half = if (major) size.width * 0.09f else size.width * 0.045f
                                drawLine(colors.hudPrimary, Offset(cx - half, y), Offset(cx + half, y), stroke, pathEffect = if (rung < 0) rungDash else null)
                                if (major) {
                                    val label = numbers[abs(rung)]
                                    drawText(label, topLeft = Offset(cx + half + 6f, y - label.size.height / 2f))
                                    drawText(label, topLeft = Offset(cx - half - 6f - label.size.width, y - label.size.height / 2f))
                                }
                            }
                            rung += 5
                        }
                    }
                }
            }
        }

        // Roll arc with ±10/20/30/45/60 ticks and the sky pointer.
        val radius = min(size.width, size.height) * 0.36f
        for (tick in HudMath.ROLL_TICKS) {
            rotate(tick, Offset(cx, cy)) {
                val len = if (abs(tick) == 30f || abs(tick) == 60f || tick == 0f) 12.dp.toPx() else 7.dp.toPx()
                drawLine(colors.hudInk2, Offset(cx, cy - radius), Offset(cx, cy - radius - len), stroke)
            }
        }
        rotate(-rollDeg, Offset(cx, cy)) {
            path.rewind()
            path.moveTo(cx, cy - radius + 2f)
            path.lineTo(cx - 7.dp.toPx(), cy - radius + 12.dp.toPx())
            path.lineTo(cx + 7.dp.toPx(), cy - radius + 12.dp.toPx())
            path.close()
            drawPath(path, lineColor)
        }

        // Flight-path marker from the velocity vector, in the roll-stabilised frame.
        if (posFresh && att != null && !headingDeg.isNaN() && HudMath.flightPathShown(pos!!.vxMs, pos.vyMs, pos.vzMs)) {
            val driftDeg = HudMath.flightPathDriftDeg(pos.vxMs, pos.vyMs, headingDeg)
            val climbDeg = HudMath.flightPathClimbDeg(pos.vxMs, pos.vyMs, pos.vzMs, pitchDeg)
            rotate(-rollDeg, Offset(cx, cy)) {
                val x = cx + driftDeg.coerceIn(-15f, 15f) * ppd
                val y = cy - climbDeg.coerceIn(-15f, 15f) * ppd
                val r = 7.dp.toPx()
                drawCircle(colors.hudGood, r, Offset(x, y), style = outline(stroke))
                drawLine(colors.hudGood, Offset(x - r * 2.2f, y), Offset(x - r, y), stroke)
                drawLine(colors.hudGood, Offset(x + r, y), Offset(x + r * 2.2f, y), stroke)
                drawLine(colors.hudGood, Offset(x, y - r), Offset(x, y - r * 1.8f), stroke)
            }
        }

        // Centre crosshair (fixed boresight).
        val cross = 10.dp.toPx()
        drawLine(colors.hudInk, Offset(cx - cross * 2, cy), Offset(cx - cross * 0.6f, cy), stroke * 1.5f, StrokeCap.Round)
        drawLine(colors.hudInk, Offset(cx + cross * 0.6f, cy), Offset(cx + cross * 2, cy), stroke * 1.5f, StrokeCap.Round)
        drawCircle(colors.hudInk, 2.dp.toPx(), Offset(cx, cy))

        // Heading tape with the home-bearing caret.
        if (pos == null || home == null) {
            homeBearing = Float.NaN
        } else if (pos !== bearingPos || home !== bearingHome) {
            homeBearing = Geo.distanceBearing(pos.latDeg, pos.lonDeg, home.latDeg, home.lonDeg).second
        }
        bearingPos = pos
        bearingHome = home
        headingTape(headingDeg, homeBearing, stroke)

        // Speed tape (left): airspeed on fixed-wing when reported, else groundspeed.
        val fixedWing = hb?.vehicleType == MAV_TYPE_FIXED_WING
        val useAir = fixedWing && vfr != null && vfr.airspeedMs > 0f
        val speed = when {
            vfr != null -> if (useAir) vfr.airspeedMs else vfr.groundspeedMs
            posFresh -> hypot(pos!!.vxMs, pos.vyMs)
            else -> Float.NaN
        }
        val dp = 1.dp.toPx()
        val tapeTop = TapeGeometry.top(size.height)
        val tapeHeight = TapeGeometry.height(size.height)
        val tapeWidth = TapeGeometry.width(dp)
        verticalTape(
            left = TapeGeometry.speedLeft(dp), top = tapeTop, width = tapeWidth, height = tapeHeight, value = speed,
            span = 20f, minor = 1f, major = 5f, label = if (useAir) "AS" else "GS", alignRight = false, stroke = stroke,
        )

        // Altitude tape (right) with REL/MSL reference and the vertical-speed bar.
        val altitude = if (posFresh) (if (altRef == AltitudeReference.Rel) pos!!.altRelM else pos!!.altMslM) else Float.NaN
        val altLeft = TapeGeometry.altitudeLeft(size.width, dp)
        verticalTape(
            left = altLeft, top = tapeTop, width = tapeWidth, height = tapeHeight, value = altitude,
            span = 40f, minor = 2f, major = 10f, label = if (altRef == AltitudeReference.Rel) "REL" else "MSL", alignRight = true, stroke = stroke,
        )
        if (density != CockpitDensity.Min) {
            val climb = when {
                vfr != null -> vfr.climbMs
                posFresh -> -pos!!.vzMs
                else -> Float.NaN
            }
            vsiBar(altLeft + tapeWidth + 6.dp.toPx(), tapeTop, tapeHeight, climb, stroke)
        }

        // Wind arrow and speed, relative to the nose.
        val wind = t?.wind?.value?.takeIf { HudMath.isFresh(it.tMs, now) }
        if (wind != null && !headingDeg.isNaN() && density != CockpitDensity.Min) {
            val origin = Offset(12.dp.toPx() + tapeWidth / 2, tapeTop + tapeHeight + 40.dp.toPx())
            // Wind direction is where it blows from; the arrow points where it blows to.
            val toward = HudMath.wrap360(wind.directionDeg + 180f - headingDeg)
            rotate(toward, origin) {
                val len = 14.dp.toPx()
                drawLine(colors.hudInk, Offset(origin.x, origin.y + len), Offset(origin.x, origin.y - len), stroke)
                path.rewind()
                path.moveTo(origin.x, origin.y - len - 4f)
                path.lineTo(origin.x - 5.dp.toPx(), origin.y - len + 6.dp.toPx())
                path.lineTo(origin.x + 5.dp.toPx(), origin.y - len + 6.dp.toPx())
                path.close()
                drawPath(path, colors.hudInk)
            }
            val text = windSpeed[wind.speedMs.roundToInt()]
            drawText(text, topLeft = Offset(origin.x - text.size.width / 2f, origin.y + 18.dp.toPx()))
        }
    }

    /** Reticle, two short horizon marks (roll and pitch) and the heading in degrees above the reticle; [heading] NaN when unknown. */
    private fun DrawScope.centreMarks(fresh: Boolean, rollDeg: Float, pitchDeg: Float, heading: Float, ppd: Float, stroke: Float) {
        val cx = size.width / 2
        val cy = size.height / 2
        val ink = if (fresh) colors.hudInk else colors.hudMuted
        // Horizon marks: short bars either side of the reticle, never a full line across the view.
        rotate(-rollDeg, Offset(cx, cy)) {
            val y = cy + (pitchDeg * ppd).coerceIn(-size.height * 0.3f, size.height * 0.3f)
            val inner = 46.dp.toPx()
            val outer = 84.dp.toPx()
            drawLine(ink, Offset(cx - outer, y), Offset(cx - inner, y), stroke * 1.4f, StrokeCap.Round)
            drawLine(ink, Offset(cx + inner, y), Offset(cx + outer, y), stroke * 1.4f, StrokeCap.Round)
        }
        // Reticle.
        val cross = 9.dp.toPx()
        drawLine(colors.hudInk, Offset(cx - cross * 2, cy), Offset(cx - cross * 0.7f, cy), stroke * 1.4f, StrokeCap.Round)
        drawLine(colors.hudInk, Offset(cx + cross * 0.7f, cy), Offset(cx + cross * 2, cy), stroke * 1.4f, StrokeCap.Round)
        drawLine(colors.hudInk, Offset(cx, cy + cross * 0.7f), Offset(cx, cy + cross * 1.5f), stroke * 1.4f, StrokeCap.Round)
        drawCircle(colors.hudInk, 1.8.dp.toPx(), Offset(cx, cy))
        // Heading: a small caret and the degrees, above the reticle.
        val label = when {
            heading.isNaN() -> caption(DASH, dim = !fresh)
            fresh -> centreHeading[HudMath.wrap360(heading).roundToInt() % 360]
            else -> centreHeadingDim[HudMath.wrap360(heading).roundToInt() % 360]
        }
        val top = cy - HEADING_READOUT_LIFT.toPx()
        // On a panel plate, so the degrees read over any picture, however bright.
        val padX = 5.dp.toPx()
        val padY = 1.dp.toPx()
        drawRoundRect(
            plate,
            Offset(cx - label.size.width / 2f - padX, top - label.size.height - padY),
            Size(label.size.width + padX * 2, label.size.height + padY * 2),
            androidx.compose.ui.geometry.CornerRadius(plateRadius.toPx()),
        )
        drawText(label, topLeft = Offset(cx - label.size.width / 2f, top - label.size.height))
        path.rewind()
        path.moveTo(cx, top + 6.dp.toPx())
        path.lineTo(cx - 4.dp.toPx(), top + 1.dp.toPx())
        path.lineTo(cx + 4.dp.toPx(), top + 1.dp.toPx())
        path.close()
        drawPath(path, ink)
    }

    /** [heading] and [homeBearing] are NaN when unknown. */
    private fun DrawScope.headingTape(heading: Float, homeBearing: Float, stroke: Float) {
        val width = size.width * 0.5f
        val left = (size.width - width) / 2
        val top = 8.dp.toPx()
        val height = 30.dp.toPx()
        val pxPerDeg = width / 60f
        val cx = size.width / 2
        val known = !heading.isNaN()
        drawRoundRect(colors.hudGlass, Offset(left, top), Size(width, height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        if (known) {
            clipRect(left, top, left + width, top + height) {
                val start = floor((heading - 30f) / 5f).toInt() * 5
                var d = start
                while (d <= heading + 30f) {
                    val x = cx + (d - heading) * pxPerDeg
                    val major = d % 15 == 0
                    drawLine(colors.hudInk2, Offset(x, top + height), Offset(x, top + height - if (major) 9.dp.toPx() else 5.dp.toPx()), stroke)
                    if (major) {
                        val label = headingTicks[((d % 360) + 360) % 360]
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, top + 2.dp.toPx()))
                    }
                    d += 5
                }
                if (!homeBearing.isNaN()) {
                    val delta = HudMath.wrap180(homeBearing - heading).coerceIn(-29f, 29f)
                    val x = cx + delta * pxPerDeg
                    path.rewind()
                    path.moveTo(x, top + height - 2f)
                    path.lineTo(x - 5.dp.toPx(), top + height + 6.dp.toPx())
                    path.lineTo(x + 5.dp.toPx(), top + height + 6.dp.toPx())
                    path.close()
                    drawPath(path, colors.hudLock)
                }
            }
        }
        val value = if (known) headingValue[heading.roundToInt() % 360] else dashMedium
        val boxW = value.size.width + 12.dp.toPx()
        drawRoundRect(colors.hudGlassStrong, Offset(cx - boxW / 2, top + height + 6.dp.toPx()), Size(boxW, value.size.height + 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawText(value, topLeft = Offset(cx - value.size.width / 2f, top + height + 8.dp.toPx()))
    }

    /** [value] is NaN when unknown. */
    private fun DrawScope.verticalTape(
        left: Float, top: Float, width: Float, height: Float, value: Float,
        span: Float, minor: Float, major: Float, label: String, alignRight: Boolean, stroke: Float,
    ) {
        val known = !value.isNaN()
        drawRoundRect(colors.hudGlass, Offset(left, top), Size(width, height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        val mid = top + height / 2
        val pxPerUnit = height / span
        if (known) {
            clipRect(left, top, left + width, top + height) {
                var v = ceil((value - span / 2) / minor) * minor
                while (v <= value + span / 2) {
                    val y = mid - (v - value) * pxPerUnit
                    val isMajor = abs(v % major) < 1e-3f || abs(abs(v % major) - major) < 1e-3f
                    val len = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
                    val x0 = if (alignRight) left else left + width
                    val x1 = if (alignRight) left + len else left + width - len
                    drawLine(colors.hudInk2, Offset(x0, y), Offset(x1, y), stroke)
                    if (isMajor) {
                        val t = numbers[v.roundToInt()]
                        val tx = if (alignRight) left + len + 4.dp.toPx() else left + width - len - 4.dp.toPx() - t.size.width
                        drawText(t, topLeft = Offset(tx, y - t.size.height / 2f))
                    }
                    v += minor
                }
            }
        }
        val text = if (known) tapeValue[(value * 10f).roundToInt()] else dashMedium
        val boxH = text.size.height + 6.dp.toPx()
        drawRoundRect(colors.hudGlassStrong, Offset(left, mid - boxH / 2), Size(width, boxH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawRoundRect(colors.hudPrimary, Offset(left, mid - boxH / 2), Size(width, boxH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = outline(stroke))
        drawText(text, topLeft = Offset(left + (width - text.size.width) / 2, mid - text.size.height / 2f))
        val caption = caption(label, dim = !known)
        drawText(caption, topLeft = Offset(left + (width - caption.size.width) / 2, top - caption.size.height - 2.dp.toPx()))
    }

    /** [climb] is NaN when unknown. */
    private fun DrawScope.vsiBar(left: Float, top: Float, height: Float, climb: Float, stroke: Float) {
        val w = 6.dp.toPx()
        val mid = top + height / 2
        drawRect(colors.hudHair, Offset(left, top), Size(w, height))
        drawLine(colors.hudInk2, Offset(left - 2f, mid), Offset(left + w + 2f, mid), stroke)
        if (climb.isNaN()) return
        val frac = (climb / HudMath.VSI_FULL_SCALE_MS).coerceIn(-1f, 1f)
        val barH = abs(frac) * height / 2
        val y = if (frac >= 0) mid - barH else mid
        drawRect(if (abs(climb) > HudMath.VSI_FULL_SCALE_MS * 0.6f) colors.hudWarn else colors.hudGood, Offset(left, y), Size(w, barH))
    }
}

/** Where the speed (left) and altitude (right) tapes sit in a HUD of a given size; [dp] is px per dp. */
internal object TapeGeometry {
    fun top(hudHeight: Float): Float = hudHeight * 0.28f
    fun height(hudHeight: Float): Float = hudHeight * 0.44f
    fun width(dp: Float): Float = 56f * dp
    fun speedLeft(dp: Float): Float = 12f * dp
    fun altitudeLeft(hudWidth: Float, dp: Float): Float = hudWidth - width(dp) - 24f * dp
}

/** Pure HUD geometry, unit-tested. */
internal object HudMath {
    /** Instruments older than this dim and read a dash. */
    const val STALE_MS = 2_000L
    const val VISIBLE_PITCH_SPAN_DEG = 40f
    const val VSI_FULL_SCALE_MS = 5f
    val ROLL_TICKS = floatArrayOf(-60f, -45f, -30f, -20f, -10f, 0f, 10f, 20f, 30f, 45f, 60f)

    fun isFresh(tMs: Long, now: Long) = now - tMs <= STALE_MS

    fun wrap360(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    fun wrap180(deg: Float): Float {
        val w = wrap360(deg)
        return if (w > 180f) w - 360f else w
    }

    /** The lowest and highest ladder rung considered at [pitchDeg] (the draw skips 0 and anything past ±90). */
    fun ladderLow(pitchDeg: Float): Int = ((pitchDeg - 25f) / 5f).toInt() * 5
    fun ladderHigh(pitchDeg: Float): Int = ((pitchDeg + 25f) / 5f).toInt() * 5

    /** Ladder rungs every 5° within ±25° of the current pitch, excluding the horizon. */
    fun ladderRungs(pitchDeg: Float): List<Int> =
        (ladderLow(pitchDeg)..ladderHigh(pitchDeg) step 5).filter { it != 0 && it in -90..90 }

    /** The flight-path marker shows from 1 m/s, horizontal or vertical. */
    fun flightPathShown(vx: Float, vy: Float, vz: Float): Boolean = hypot(vx, vy) >= 1f || abs(vz) >= 1f

    /** The marker's x offset from the boresight in degrees: drift, track minus heading (0 below 1 m/s ground speed). */
    fun flightPathDriftDeg(vx: Float, vy: Float, headingDeg: Float): Float =
        if (hypot(vx, vy) < 1f) 0f else wrap180(Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat() - headingDeg)

    /** The marker's y offset from the boresight in degrees: flight-path angle minus pitch. */
    fun flightPathClimbDeg(vx: Float, vy: Float, vz: Float, pitchDeg: Float): Float =
        Math.toDegrees(atan2(-vz.toDouble(), hypot(vx, vy).toDouble())).toFloat() - pitchDeg

    /**
     * Offset of the flight-path marker from the boresight in degrees: x = drift
     * (track minus heading), y = flight-path angle minus pitch. Null below 1 m/s.
     */
    fun flightPathOffsetDeg(vx: Float, vy: Float, vz: Float, pitchDeg: Float, headingDeg: Float): Pair<Float, Float>? {
        if (!flightPathShown(vx, vy, vz)) return null
        return flightPathDriftDeg(vx, vy, headingDeg) to flightPathClimbDeg(vx, vy, vz, pitchDeg)
    }

    fun headingLabel(deg: Int): String = when (((deg % 360) + 360) % 360) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> (((deg % 360) + 360) % 360).toString()
    }
}
