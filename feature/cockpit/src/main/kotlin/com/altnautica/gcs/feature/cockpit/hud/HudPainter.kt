package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.cockpit.Geo
import com.altnautica.gcs.feature.cockpit.settings.AltitudeReference
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
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

/** Draws CS-3 instruments from the current telemetry values. Allocation-light: one instance per theme. */
internal class HudPainter(
    private val measurer: TextMeasurer,
    private val colors: AdosColorRoles,
    small: TextStyle,
    medium: TextStyle,
) {
    private val smallStyle = small.copy(color = colors.hudInk)
    private val mediumStyle = medium.copy(color = colors.hudInk)
    private val dimSmall = small.copy(color = colors.hudMuted)
    private val dimMedium = medium.copy(color = colors.hudMuted)
    private val path = Path()

    fun DrawScope.paint(t: VehicleTelemetry?, altRef: AltitudeReference, density: CockpitDensity, now: Long) {
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

        val rollDeg = att?.let { Math.toDegrees(it.rollRad.toDouble()).toFloat() } ?: 0f
        val pitchDeg = att?.let { Math.toDegrees(it.pitchRad.toDouble()).toFloat() } ?: 0f
        val headingDeg = vfr?.headingDeg ?: att?.let { HudMath.wrap360(Math.toDegrees(it.yawRad.toDouble()).toFloat()) }

        // Horizon and pitch ladder.
        val lineColor = if (att != null) colors.hudPrimary else colors.hudMuted
        clipRect(0f, size.height * 0.12f, size.width, size.height * 0.88f) {
            rotate(-rollDeg, Offset(cx, cy)) {
                translate(0f, pitchDeg * ppd) {
                    drawLine(lineColor, Offset(cx - size.width, cy), Offset(cx - size.width * 0.08f, cy), stroke * 1.5f)
                    drawLine(lineColor, Offset(cx + size.width * 0.08f, cy), Offset(cx + size.width, cy), stroke * 1.5f)
                    if (att != null && density != CockpitDensity.Min) {
                        for (rung in HudMath.ladderRungs(pitchDeg)) {
                            val y = cy - rung * ppd
                            val major = rung % 10 == 0
                            val half = if (major) size.width * 0.09f else size.width * 0.045f
                            val effect = if (rung < 0) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                            drawLine(colors.hudPrimary, Offset(cx - half, y), Offset(cx + half, y), stroke, pathEffect = effect)
                            if (major) {
                                val label = measurer.measure(abs(rung).toString(), smallStyle)
                                drawText(label, topLeft = Offset(cx + half + 6f, y - label.size.height / 2f))
                                drawText(label, topLeft = Offset(cx - half - 6f - label.size.width, y - label.size.height / 2f))
                            }
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
            path.reset()
            path.moveTo(cx, cy - radius + 2f)
            path.lineTo(cx - 7.dp.toPx(), cy - radius + 12.dp.toPx())
            path.lineTo(cx + 7.dp.toPx(), cy - radius + 12.dp.toPx())
            path.close()
            drawPath(path, lineColor)
        }

        // Flight-path marker from the velocity vector, in the roll-stabilised frame.
        if (posFresh && att != null && headingDeg != null) {
            val fpm = HudMath.flightPathOffsetDeg(pos!!.vxMs, pos.vyMs, pos.vzMs, pitchDeg, headingDeg)
            if (fpm != null) {
                rotate(-rollDeg, Offset(cx, cy)) {
                    val x = cx + fpm.first.coerceIn(-15f, 15f) * ppd
                    val y = cy - fpm.second.coerceIn(-15f, 15f) * ppd
                    val r = 7.dp.toPx()
                    drawCircle(colors.hudGood, r, Offset(x, y), style = Stroke(stroke))
                    drawLine(colors.hudGood, Offset(x - r * 2.2f, y), Offset(x - r, y), stroke)
                    drawLine(colors.hudGood, Offset(x + r, y), Offset(x + r * 2.2f, y), stroke)
                    drawLine(colors.hudGood, Offset(x, y - r), Offset(x, y - r * 1.8f), stroke)
                }
            }
        }

        // Centre crosshair (fixed boresight).
        val cross = 10.dp.toPx()
        drawLine(colors.hudInk, Offset(cx - cross * 2, cy), Offset(cx - cross * 0.6f, cy), stroke * 1.5f, StrokeCap.Round)
        drawLine(colors.hudInk, Offset(cx + cross * 0.6f, cy), Offset(cx + cross * 2, cy), stroke * 1.5f, StrokeCap.Round)
        drawCircle(colors.hudInk, 2.dp.toPx(), Offset(cx, cy))

        // Heading tape with the home-bearing caret.
        val homeBearing = if (pos != null && home != null) Geo.distanceBearing(pos.latDeg, pos.lonDeg, home.latDeg, home.lonDeg).second else null
        headingTape(headingDeg, homeBearing, stroke)

        // Speed tape (left): airspeed on fixed-wing when reported, else groundspeed.
        val fixedWing = hb?.vehicleType == MAV_TYPE_FIXED_WING
        val useAir = fixedWing && vfr != null && vfr.airspeedMs > 0f
        val speed = vfr?.let { if (useAir) it.airspeedMs else it.groundspeedMs }
            ?: pos?.takeIf { posFresh }?.let { hypot(it.vxMs, it.vyMs) }
        val tapeTop = size.height * 0.28f
        val tapeHeight = size.height * 0.44f
        val tapeWidth = 56.dp.toPx()
        verticalTape(
            left = 12.dp.toPx(), top = tapeTop, width = tapeWidth, height = tapeHeight, value = speed,
            span = 20f, minor = 1f, major = 5f, label = if (useAir) "AS" else "GS", alignRight = false, stroke = stroke,
        )

        // Altitude tape (right) with REL/MSL reference and the vertical-speed bar.
        val altitude = pos?.takeIf { posFresh }?.let { if (altRef == AltitudeReference.Rel) it.altRelM else it.altMslM }
        val altLeft = size.width - tapeWidth - 24.dp.toPx()
        verticalTape(
            left = altLeft, top = tapeTop, width = tapeWidth, height = tapeHeight, value = altitude,
            span = 40f, minor = 2f, major = 10f, label = if (altRef == AltitudeReference.Rel) "REL" else "MSL", alignRight = true, stroke = stroke,
        )
        if (density != CockpitDensity.Min) {
            val climb = vfr?.climbMs ?: pos?.takeIf { posFresh }?.let { -it.vzMs }
            vsiBar(altLeft + tapeWidth + 6.dp.toPx(), tapeTop, tapeHeight, climb, stroke)
        }

        // Wind arrow and speed, relative to the nose.
        val wind = t?.wind?.value?.takeIf { HudMath.isFresh(it.tMs, now) }
        if (wind != null && headingDeg != null && density != CockpitDensity.Min) {
            val origin = Offset(12.dp.toPx() + tapeWidth / 2, tapeTop + tapeHeight + 40.dp.toPx())
            // Wind direction is where it blows from; the arrow points where it blows to.
            val toward = HudMath.wrap360(wind.directionDeg + 180f - headingDeg)
            rotate(toward, origin) {
                val len = 14.dp.toPx()
                drawLine(colors.hudInk, Offset(origin.x, origin.y + len), Offset(origin.x, origin.y - len), stroke)
                path.reset()
                path.moveTo(origin.x, origin.y - len - 4f)
                path.lineTo(origin.x - 5.dp.toPx(), origin.y - len + 6.dp.toPx())
                path.lineTo(origin.x + 5.dp.toPx(), origin.y - len + 6.dp.toPx())
                path.close()
                drawPath(path, colors.hudInk)
            }
            val text = measurer.measure("${wind.speedMs.roundToInt()} m/s", smallStyle)
            drawText(text, topLeft = Offset(origin.x - text.size.width / 2f, origin.y + 18.dp.toPx()))
        }
    }

    private fun DrawScope.headingTape(heading: Float?, homeBearing: Float?, stroke: Float) {
        val width = size.width * 0.5f
        val left = (size.width - width) / 2
        val top = 8.dp.toPx()
        val height = 30.dp.toPx()
        val pxPerDeg = width / 60f
        val cx = size.width / 2
        drawRoundRect(colors.hudGlass, Offset(left, top), Size(width, height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        if (heading != null) {
            clipRect(left, top, left + width, top + height) {
                val start = floor((heading - 30f) / 5f).toInt() * 5
                var d = start
                while (d <= heading + 30f) {
                    val x = cx + (d - heading) * pxPerDeg
                    val major = d % 15 == 0
                    drawLine(colors.hudInk2, Offset(x, top + height), Offset(x, top + height - if (major) 9.dp.toPx() else 5.dp.toPx()), stroke)
                    if (major) {
                        val label = measurer.measure(HudMath.headingLabel(d), smallStyle)
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, top + 2.dp.toPx()))
                    }
                    d += 5
                }
                if (homeBearing != null) {
                    val delta = HudMath.wrap180(homeBearing - heading).coerceIn(-29f, 29f)
                    val x = cx + delta * pxPerDeg
                    path.reset()
                    path.moveTo(x, top + height - 2f)
                    path.lineTo(x - 5.dp.toPx(), top + height + 6.dp.toPx())
                    path.lineTo(x + 5.dp.toPx(), top + height + 6.dp.toPx())
                    path.close()
                    drawPath(path, colors.hudLock)
                }
            }
        }
        val value = measurer.measure(heading?.let { "%03d".format(it.roundToInt() % 360) } ?: DASH, if (heading != null) mediumStyle else dimMedium)
        val boxW = value.size.width + 12.dp.toPx()
        drawRoundRect(colors.hudGlassStrong, Offset(cx - boxW / 2, top + height + 6.dp.toPx()), Size(boxW, value.size.height + 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawText(value, topLeft = Offset(cx - value.size.width / 2f, top + height + 8.dp.toPx()))
    }

    private fun DrawScope.verticalTape(
        left: Float, top: Float, width: Float, height: Float, value: Float?,
        span: Float, minor: Float, major: Float, label: String, alignRight: Boolean, stroke: Float,
    ) {
        drawRoundRect(colors.hudGlass, Offset(left, top), Size(width, height), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        val mid = top + height / 2
        val pxPerUnit = height / span
        if (value != null) {
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
                        val t = measurer.measure(v.roundToInt().toString(), smallStyle)
                        val tx = if (alignRight) left + len + 4.dp.toPx() else left + width - len - 4.dp.toPx() - t.size.width
                        drawText(t, topLeft = Offset(tx, y - t.size.height / 2f))
                    }
                    v += minor
                }
            }
        }
        val text = measurer.measure(value?.let { "%.1f".format(it) } ?: DASH, if (value != null) mediumStyle else dimMedium)
        val boxH = text.size.height + 6.dp.toPx()
        drawRoundRect(colors.hudGlassStrong, Offset(left, mid - boxH / 2), Size(width, boxH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawRoundRect(colors.hudPrimary, Offset(left, mid - boxH / 2), Size(width, boxH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(stroke))
        drawText(text, topLeft = Offset(left + (width - text.size.width) / 2, mid - text.size.height / 2f))
        val caption = measurer.measure(label, if (value != null) smallStyle else dimSmall)
        drawText(caption, topLeft = Offset(left + (width - caption.size.width) / 2, top - caption.size.height - 2.dp.toPx()))
    }

    private fun DrawScope.vsiBar(left: Float, top: Float, height: Float, climb: Float?, stroke: Float) {
        val w = 6.dp.toPx()
        val mid = top + height / 2
        drawRect(colors.hudHair, Offset(left, top), Size(w, height))
        drawLine(colors.hudInk2, Offset(left - 2f, mid), Offset(left + w + 2f, mid), stroke)
        if (climb == null) return
        val frac = (climb / HudMath.VSI_FULL_SCALE_MS).coerceIn(-1f, 1f)
        val barH = abs(frac) * height / 2
        val y = if (frac >= 0) mid - barH else mid
        drawRect(if (abs(climb) > HudMath.VSI_FULL_SCALE_MS * 0.6f) colors.hudWarn else colors.hudGood, Offset(left, y), Size(w, barH))
    }
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

    /** Ladder rungs every 5° within ±25° of the current pitch, excluding the horizon. */
    fun ladderRungs(pitchDeg: Float): List<Int> {
        val lo = ((pitchDeg - 25f) / 5f).toInt() * 5
        val hi = ((pitchDeg + 25f) / 5f).toInt() * 5
        return (lo..hi step 5).filter { it != 0 && it in -90..90 }
    }

    /**
     * Offset of the flight-path marker from the boresight in degrees: x = drift
     * (track minus heading), y = flight-path angle minus pitch. Null below 1 m/s.
     */
    fun flightPathOffsetDeg(vx: Float, vy: Float, vz: Float, pitchDeg: Float, headingDeg: Float): Pair<Float, Float>? {
        val gs = hypot(vx, vy)
        if (gs < 1f && abs(vz) < 1f) return null
        val gamma = Math.toDegrees(atan2(-vz.toDouble(), gs.toDouble())).toFloat()
        val drift = if (gs < 1f) 0f else wrap180(Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat() - headingDeg)
        return drift to (gamma - pitchDeg)
    }

    fun headingLabel(deg: Int): String = when (((deg % 360) + 360) % 360) {
        0 -> "N"
        90 -> "E"
        180 -> "S"
        270 -> "W"
        else -> (((deg % 360) + 360) % 360).toString()
    }
}
