package com.altnautica.gcs.ui.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.VfrState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val HudGreen = Color(0xFF22C55E)
private val HudAmber = Color(0xFFFBBF24)
private val HudRed = Color(0xFFEF4444)
private val HudWhite = Color(0xFFE5E5E5)
private val HudDim = Color(0x99A0A0A0)

@Composable
fun HudOverlay(
    attitude: AttitudeState,
    position: PositionState,
    battery: BatteryState,
    gps: GpsState,
    vfr: VfrState,
    flightMode: FlightMode?,
    armed: Boolean,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        drawArtificialHorizon(cx, cy, attitude)
        drawCompassTape(cx, position.heading)
        drawAltitudeLadder(cx, cy, position.altRel)
        drawSpeedLadder(cx, cy, vfr.groundspeed)
        drawBatteryInfo(battery)
        drawFlightModeInfo(cx, flightMode, armed)
        drawGpsInfo(gps)
        drawDistanceToHome(position)
    }
}

// -- Artificial Horizon --

private fun DrawScope.drawArtificialHorizon(
    cx: Float,
    cy: Float,
    attitude: AttitudeState,
) {
    val horizonRadius = size.height * 0.25f
    val pitchScale = horizonRadius / 30f // pixels per degree
    val rollDeg = Math.toDegrees(attitude.roll.toDouble()).toFloat()
    val pitchDeg = Math.toDegrees(attitude.pitch.toDouble()).toFloat()

    // Roll indicator arc
    drawArc(
        color = HudWhite,
        startAngle = -150f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = Offset(cx - horizonRadius, cy - horizonRadius),
        size = androidx.compose.ui.geometry.Size(horizonRadius * 2, horizonRadius * 2),
        style = Stroke(width = 1.5.dp.toPx()),
    )

    // Roll tick marks at -60, -45, -30, -20, -10, 0, 10, 20, 30, 45, 60
    val rollTicks = listOf(-60f, -45f, -30f, -20f, -10f, 0f, 10f, 20f, 30f, 45f, 60f)
    for (tick in rollTicks) {
        val angle = Math.toRadians((-90 + tick).toDouble())
        val outerR = horizonRadius
        val innerR = horizonRadius - if (tick % 30 == 0f.toInt().toFloat()) 12.dp.toPx() else 8.dp.toPx()
        drawLine(
            color = HudWhite,
            start = Offset(cx + (innerR * cos(angle)).toFloat(), cy + (innerR * sin(angle)).toFloat()),
            end = Offset(cx + (outerR * cos(angle)).toFloat(), cy + (outerR * sin(angle)).toFloat()),
            strokeWidth = 1.5.dp.toPx(),
        )
    }

    // Roll pointer (triangle at current roll)
    val pointerAngle = Math.toRadians((-90 + rollDeg).toDouble())
    val pointerR = horizonRadius + 4.dp.toPx()
    val pointerTip = Offset(
        cx + (pointerR * cos(pointerAngle)).toFloat(),
        cy + (pointerR * sin(pointerAngle)).toFloat(),
    )
    drawCircle(color = HudGreen, radius = 3.dp.toPx(), center = pointerTip)

    // Pitch ladder (rotated by roll)
    rotate(degrees = rollDeg, pivot = Offset(cx, cy)) {
        val pitchOffset = pitchDeg * pitchScale
        for (deg in -20..20 step 5) {
            if (deg == 0) continue
            val y = cy + pitchOffset - deg * pitchScale
            val halfWidth = if (deg % 10 == 0) 40.dp.toPx() else 20.dp.toPx()
            val lineColor = if (deg % 10 == 0) HudWhite else HudDim
            drawLine(
                color = lineColor,
                start = Offset(cx - halfWidth, y),
                end = Offset(cx + halfWidth, y),
                strokeWidth = 1.dp.toPx(),
            )
            if (deg % 10 == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    "${deg}",
                    cx - halfWidth - 24.dp.toPx(),
                    y + 4.dp.toPx(),
                    hudTextPaint(HudWhite, 10f),
                )
            }
        }

        // Center horizon line
        val horizonY = cy + pitchOffset
        drawLine(
            color = HudGreen,
            start = Offset(cx - 60.dp.toPx(), horizonY),
            end = Offset(cx - 20.dp.toPx(), horizonY),
            strokeWidth = 2.dp.toPx(),
        )
        drawLine(
            color = HudGreen,
            start = Offset(cx + 20.dp.toPx(), horizonY),
            end = Offset(cx + 60.dp.toPx(), horizonY),
            strokeWidth = 2.dp.toPx(),
        )
    }

    // Fixed aircraft reference (center crosshair)
    drawLine(HudGreen, Offset(cx - 15.dp.toPx(), cy), Offset(cx - 5.dp.toPx(), cy), 2.dp.toPx())
    drawLine(HudGreen, Offset(cx + 5.dp.toPx(), cy), Offset(cx + 15.dp.toPx(), cy), 2.dp.toPx())
    drawCircle(HudGreen, radius = 2.dp.toPx(), center = Offset(cx, cy), style = Stroke(2.dp.toPx()))
}

// -- Compass Tape --

private fun DrawScope.drawCompassTape(cx: Float, heading: Int) {
    val tapeY = 28.dp.toPx()
    val tapeWidth = size.width * 0.4f
    val halfWidth = tapeWidth / 2f
    val degreesVisible = 60f
    val pxPerDeg = tapeWidth / degreesVisible

    // Background strip
    drawRect(
        color = Color(0x44000000),
        topLeft = Offset(cx - halfWidth - 10.dp.toPx(), tapeY - 14.dp.toPx()),
        size = androidx.compose.ui.geometry.Size(tapeWidth + 20.dp.toPx(), 28.dp.toPx()),
    )

    val canvas = drawContext.canvas.nativeCanvas
    val labels = mapOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")

    for (offset in -35..35) {
        val deg = ((heading + offset) % 360 + 360) % 360
        val x = cx + offset * pxPerDeg
        if (x < cx - halfWidth || x > cx + halfWidth) continue

        if (deg % 10 == 0) {
            val tickH = if (deg % 30 == 0) 8.dp.toPx() else 4.dp.toPx()
            drawLine(HudWhite, Offset(x, tapeY - tickH), Offset(x, tapeY), 1.dp.toPx())
        }
        if (deg % 30 == 0) {
            val label = labels[deg] ?: "${deg}"
            canvas.drawText(label, x, tapeY + 14.dp.toPx(), hudTextPaint(HudWhite, 10f).apply {
                textAlign = android.graphics.Paint.Align.CENTER
            })
        }
    }

    // Center marker
    drawLine(HudGreen, Offset(cx, tapeY - 12.dp.toPx()), Offset(cx, tapeY + 2.dp.toPx()), 2.dp.toPx())

    // Heading readout
    canvas.drawText(
        "${heading}°",
        cx,
        tapeY - 18.dp.toPx(),
        hudTextPaint(HudGreen, 13f).apply { textAlign = android.graphics.Paint.Align.CENTER },
    )
}

// -- Altitude Ladder (left side) --

private fun DrawScope.drawAltitudeLadder(cx: Float, cy: Float, altRel: Float) {
    val ladderX = size.width - 60.dp.toPx()
    val ladderHeight = size.height * 0.5f
    val halfHeight = ladderHeight / 2f
    val metersVisible = 40f
    val pxPerMeter = ladderHeight / metersVisible
    val canvas = drawContext.canvas.nativeCanvas

    // Background strip
    drawRect(
        color = Color(0x44000000),
        topLeft = Offset(ladderX - 8.dp.toPx(), cy - halfHeight),
        size = androidx.compose.ui.geometry.Size(52.dp.toPx(), ladderHeight),
    )

    val altInt = altRel.roundToInt()
    for (offset in -20..20) {
        val alt = altInt + offset
        val y = cy - offset * pxPerMeter + (altRel - altInt) * pxPerMeter
        if (y < cy - halfHeight || y > cy + halfHeight) continue

        if (alt % 5 == 0) {
            val tickW = if (alt % 10 == 0) 10.dp.toPx() else 5.dp.toPx()
            drawLine(HudWhite, Offset(ladderX, y), Offset(ladderX + tickW, y), 1.dp.toPx())
            if (alt % 10 == 0) {
                canvas.drawText(
                    "${alt}",
                    ladderX + 14.dp.toPx(),
                    y + 4.dp.toPx(),
                    hudTextPaint(HudWhite, 10f),
                )
            }
        }
    }

    // Current altitude readout box
    drawRect(
        color = Color(0xAA000000),
        topLeft = Offset(ladderX - 2.dp.toPx(), cy - 10.dp.toPx()),
        size = androidx.compose.ui.geometry.Size(50.dp.toPx(), 20.dp.toPx()),
    )
    canvas.drawText(
        "%.1f".format(altRel),
        ladderX + 4.dp.toPx(),
        cy + 5.dp.toPx(),
        hudTextPaint(HudGreen, 12f),
    )

    // Label
    canvas.drawText(
        "ALT m",
        ladderX + 4.dp.toPx(),
        cy - halfHeight - 6.dp.toPx(),
        hudTextPaint(HudDim, 9f),
    )
}

// -- Speed Ladder (right side) --

private fun DrawScope.drawSpeedLadder(cx: Float, cy: Float, groundspeed: Float) {
    val ladderX = 60.dp.toPx()
    val ladderHeight = size.height * 0.5f
    val halfHeight = ladderHeight / 2f
    val msVisible = 20f
    val pxPerMs = ladderHeight / msVisible
    val canvas = drawContext.canvas.nativeCanvas

    // Background strip
    drawRect(
        color = Color(0x44000000),
        topLeft = Offset(ladderX - 44.dp.toPx(), cy - halfHeight),
        size = androidx.compose.ui.geometry.Size(52.dp.toPx(), ladderHeight),
    )

    val spdInt = groundspeed.roundToInt()
    for (offset in -10..10) {
        val spd = spdInt + offset
        if (spd < 0) continue
        val y = cy - offset * pxPerMs + (groundspeed - spdInt) * pxPerMs
        if (y < cy - halfHeight || y > cy + halfHeight) continue

        if (spd % 2 == 0) {
            val tickW = if (spd % 5 == 0) 10.dp.toPx() else 5.dp.toPx()
            drawLine(HudWhite, Offset(ladderX - tickW, y), Offset(ladderX, y), 1.dp.toPx())
            if (spd % 5 == 0) {
                canvas.drawText(
                    "${spd}",
                    ladderX - 14.dp.toPx(),
                    y + 4.dp.toPx(),
                    hudTextPaint(HudWhite, 10f).apply {
                        textAlign = android.graphics.Paint.Align.RIGHT
                    },
                )
            }
        }
    }

    // Current speed readout box
    drawRect(
        color = Color(0xAA000000),
        topLeft = Offset(ladderX - 48.dp.toPx(), cy - 10.dp.toPx()),
        size = androidx.compose.ui.geometry.Size(50.dp.toPx(), 20.dp.toPx()),
    )
    canvas.drawText(
        "%.1f".format(groundspeed),
        ladderX - 44.dp.toPx(),
        cy + 5.dp.toPx(),
        hudTextPaint(HudGreen, 12f),
    )

    // Label
    canvas.drawText(
        "GS m/s",
        ladderX - 44.dp.toPx(),
        cy - halfHeight - 6.dp.toPx(),
        hudTextPaint(HudDim, 9f),
    )
}

// -- Battery Info (bottom-left) --

private fun DrawScope.drawBatteryInfo(battery: BatteryState) {
    val canvas = drawContext.canvas.nativeCanvas
    val x = 16.dp.toPx()
    val y = size.height - 20.dp.toPx()

    val pct = battery.remaining
    val color = when {
        pct < 0 -> HudDim
        pct <= 20 -> HudRed
        pct <= 40 -> HudAmber
        else -> HudGreen
    }
    val pctText = if (pct >= 0) "${pct}%" else "--%"
    val voltText = "%.1fV".format(battery.voltage)

    canvas.drawText(pctText, x, y, hudTextPaint(color, 14f))
    canvas.drawText(voltText, x + 56.dp.toPx(), y, hudTextPaint(HudDim, 11f))
}

// -- Flight Mode + Armed (bottom-center) --

private fun DrawScope.drawFlightModeInfo(cx: Float, flightMode: FlightMode?, armed: Boolean) {
    val canvas = drawContext.canvas.nativeCanvas
    val y = size.height - 20.dp.toPx()

    val modeText = flightMode?.label ?: "---"
    val armedText = if (armed) "ARMED" else "DISARMED"
    val armedColor = if (armed) HudRed else HudGreen

    canvas.drawText(modeText, cx - 40.dp.toPx(), y, hudTextPaint(HudWhite, 14f))
    canvas.drawText(armedText, cx + 20.dp.toPx(), y, hudTextPaint(armedColor, 12f))
}

// -- GPS Info (bottom-right) --

private fun DrawScope.drawGpsInfo(gps: GpsState) {
    val canvas = drawContext.canvas.nativeCanvas
    val x = size.width - 120.dp.toPx()
    val y = size.height - 20.dp.toPx()

    val fixLabel = when (gps.fixType) {
        0, 1 -> "No Fix"
        2 -> "2D"
        3 -> "3D"
        4 -> "DGPS"
        5 -> "RTK Float"
        6 -> "RTK Fix"
        else -> "Fix:${gps.fixType}"
    }
    val fixColor = when (gps.fixType) {
        0, 1 -> HudRed
        2 -> HudAmber
        else -> HudGreen
    }
    canvas.drawText(fixLabel, x, y, hudTextPaint(fixColor, 11f))
    canvas.drawText("${gps.satellites} sats", x + 52.dp.toPx(), y, hudTextPaint(HudDim, 11f))
}

// -- Distance to Home (top-right, below mode indicator) --

private fun DrawScope.drawDistanceToHome(position: PositionState) {
    val canvas = drawContext.canvas.nativeCanvas
    val x = size.width - 80.dp.toPx()
    val y = 56.dp.toPx()

    // Simple placeholder: show lat/lon. Real distance calc needs home position.
    val latText = "%.5f".format(position.lat)
    val lonText = "%.5f".format(position.lon)
    canvas.drawText(latText, x, y, hudTextPaint(HudDim, 9f))
    canvas.drawText(lonText, x, y + 14.dp.toPx(), hudTextPaint(HudDim, 9f))
}

// -- Paint helper --

private fun hudTextPaint(color: Color, sizeSp: Float): android.graphics.Paint {
    return android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        textSize = sizeSp * 2.5f // approximate sp to px for canvas
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
    }
}
