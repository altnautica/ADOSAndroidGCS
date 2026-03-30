package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SuccessGreen
import kotlin.math.cos
import kotlin.math.sin

private val GridColor = Color(0xFF1E1E1E)
private val GridColorLight = Color(0xFF2A2A2A)
private val CompassRing = Color(0xFF333333)
private val CardinalColor = Color(0xFF666666)

/**
 * Simplified dark-themed map placeholder with compass rose, drone position indicator,
 * home position indicator, and heading line. Ready for full Mapbox integration later.
 */
@Composable
fun DroneMapView(
    dronePosition: PositionState,
    homePosition: PositionState?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(DeepBlack),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = minOf(size.width, size.height) * 0.35f

            // Grid lines
            val gridSpacing = 40.dp.toPx()
            val gridColor = GridColor
            var x = gridSpacing
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
                x += gridSpacing
            }
            var y = gridSpacing
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
                y += gridSpacing
            }

            // Compass rose rings
            drawCircle(CompassRing, radius, Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawCircle(CompassRing, radius * 0.6f, Offset(cx, cy), style = Stroke(0.5.dp.toPx()))
            drawCircle(CompassRing, radius * 0.3f, Offset(cx, cy), style = Stroke(0.5.dp.toPx()))

            // Cardinal direction ticks and labels
            val canvas = drawContext.canvas.nativeCanvas
            val cardinals = listOf(0f to "N", 90f to "E", 180f to "S", 270f to "W")
            val intermediates = listOf(45f, 135f, 225f, 315f)

            for ((angle, label) in cardinals) {
                val rad = Math.toRadians((angle - 90).toDouble())
                val outerX = cx + (radius * cos(rad)).toFloat()
                val outerY = cy + (radius * sin(rad)).toFloat()
                val innerX = cx + ((radius - 12.dp.toPx()) * cos(rad)).toFloat()
                val innerY = cy + ((radius - 12.dp.toPx()) * sin(rad)).toFloat()
                drawLine(CardinalColor, Offset(innerX, innerY), Offset(outerX, outerY), 2.dp.toPx())

                val labelX = cx + ((radius + 16.dp.toPx()) * cos(rad)).toFloat()
                val labelY = cy + ((radius + 16.dp.toPx()) * sin(rad)).toFloat()
                canvas.drawText(
                    label, labelX, labelY + 5.dp.toPx(),
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.argb(180, 160, 160, 160)
                        textSize = 12.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    },
                )
            }

            for (angle in intermediates) {
                val rad = Math.toRadians((angle - 90).toDouble())
                val outerX = cx + (radius * cos(rad)).toFloat()
                val outerY = cy + (radius * sin(rad)).toFloat()
                val innerX = cx + ((radius - 6.dp.toPx()) * cos(rad)).toFloat()
                val innerY = cy + ((radius - 6.dp.toPx()) * sin(rad)).toFloat()
                drawLine(GridColorLight, Offset(innerX, innerY), Offset(outerX, outerY), 1.dp.toPx())
            }

            // Home dot (relative position, green) if available
            if (homePosition != null && (homePosition.lat != 0.0 || homePosition.lon != 0.0)) {
                val dLat = homePosition.lat - dronePosition.lat
                val dLon = homePosition.lon - dronePosition.lon
                // Scale: 1 degree ~ radius pixels (arbitrary for placeholder)
                val scale = radius * 10000.0
                val homeX = cx + (dLon * scale).toFloat().coerceIn(-radius, radius)
                val homeY = cy - (dLat * scale).toFloat().coerceIn(-radius, radius)
                drawCircle(
                    color = SuccessGreen,
                    radius = 6.dp.toPx(),
                    center = Offset(homeX, homeY),
                )
                drawCircle(
                    color = SuccessGreen.copy(alpha = 0.3f),
                    radius = 12.dp.toPx(),
                    center = Offset(homeX, homeY),
                )
            }

            // Heading line from drone
            val headingRad = Math.toRadians((dronePosition.heading - 90).toDouble())
            val headingLen = radius * 0.25f
            drawLine(
                color = ElectricBlue.copy(alpha = 0.7f),
                start = Offset(cx, cy),
                end = Offset(
                    cx + (headingLen * cos(headingRad)).toFloat(),
                    cy + (headingLen * sin(headingRad)).toFloat(),
                ),
                strokeWidth = 2.dp.toPx(),
            )

            // Drone dot (center, blue) with direction triangle
            drawCircle(
                color = ElectricBlue,
                radius = 5.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = ElectricBlue.copy(alpha = 0.25f),
                radius = 14.dp.toPx(),
                center = Offset(cx, cy),
            )
        }

        // Coordinate display (bottom-center)
        val latStr = "%.6f".format(dronePosition.lat)
        val lonStr = "%.6f".format(dronePosition.lon)
        val hdgStr = "%03d°".format(dronePosition.heading)

        Text(
            text = "$latStr, $lonStr  HDG $hdgStr",
            color = Color(0xFFA0A0A0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )

        // "Map placeholder" label (top-left)
        Text(
            text = "MAP",
            color = Color(0xFF444444),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
    }
}
