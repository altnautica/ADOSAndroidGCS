package com.altnautica.gcs.feature.cockpit.hud

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.model.ObstacleSectors
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.cockpit.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

/** Under 2 m is danger, 2-5 m caution. */
private const val DANGER_CM = 200
private const val CAUTION_CM = 500

/** Obstacle data older than this is not drawn: a dead link must never paint a stale danger arc. */
private const val OBSTACLE_STALE_MS = 2_000L

internal object RadarMath {
    /** Sector start angle in degrees clockwise from the dial top. */
    fun sectorStart(index: Int, s: ObstacleSectors): Float = s.angleOffsetDeg + index * s.incrementDeg - s.incrementDeg / 2

    /** True when a reading is a real distance (0 and above max mean no reading). */
    fun valid(cm: Int, s: ObstacleSectors) = cm > 0 && cm >= s.minCm && cm <= s.maxCm

    fun nearestCm(s: ObstacleSectors): Int? = s.distancesCm.filter { valid(it, s) }.minOrNull()
}

/**
 * Proximity radar from OBSTACLE_DISTANCE: amber caution and red danger sectors, the
 * dial top labelled N for north-aligned data and FWD for body data, the nearest
 * range below. Hidden when the vehicle reports no proximity sensor.
 */
@Composable
fun ProximityRadar(telemetry: VehicleTelemetry?, modifier: Modifier = Modifier, diameter: Dp = 120.dp) {
    telemetry ?: return
    val present by produceState(false, telemetry) {
        telemetry.obstacles.map { it != null }.collect { value = it }
    }
    if (!present) return
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(telemetry) {
        launchTicker(telemetry, frame)
    }
    val colors = AdosTheme.colors
    val measurer = rememberTextMeasurer()
    val style = AdosTypography.numeric.small.copy(color = colors.hudInk)
    val description = stringResource(R.string.cockpit_radar_description)
    Spacer(
        modifier
            .size(diameter)
            .semantics { contentDescription = description }
            .drawBehind {
                frame.longValue
                val s = telemetry.obstacles.value
                val center = Offset(size.width / 2, size.height / 2)
                val outer = size.minDimension / 2 - 4.dp.toPx()
                val inner = outer * 0.3f
                drawCircle(colors.hudGlass, outer, center)
                drawCircle(colors.hudHair, outer, center, style = Stroke(1.dp.toPx()))
                drawCircle(colors.hudHair, outer * 0.65f, center, style = Stroke(1.dp.toPx()))
                drawCircle(colors.hudHair, inner, center, style = Stroke(1.dp.toPx()))
                drawLine(colors.hudHair, Offset(center.x, center.y - outer), Offset(center.x, center.y + outer))
                drawLine(colors.hudHair, Offset(center.x - outer, center.y), Offset(center.x + outer, center.y))
                val fresh = s != null && SystemClock.elapsedRealtime() - s.tMs <= OBSTACLE_STALE_MS
                if (s != null && fresh) {
                    s.distancesCm.forEachIndexed { i, cm ->
                        if (!RadarMath.valid(cm, s) || cm > CAUTION_CM) return@forEachIndexed
                        val color = if (cm < DANGER_CM) colors.hudCrit else colors.hudWarn
                        drawArc(
                            color.copy(alpha = 0.45f),
                            startAngle = RadarMath.sectorStart(i, s) - 90f,
                            sweepAngle = s.incrementDeg,
                            useCenter = true,
                            topLeft = Offset(center.x - outer, center.y - outer),
                            size = Size(outer * 2, outer * 2),
                        )
                    }
                    drawCircle(colors.hudGlassStrong, inner, center)
                }
                val top = measurer.measure(if (s?.northAligned == true) "N" else "FWD", style)
                drawText(top, topLeft = Offset(center.x - top.size.width / 2f, center.y - outer + 2.dp.toPx()))
                val nearest = if (s != null && fresh) RadarMath.nearestCm(s) else null
                val label = measurer.measure(nearest?.let { "%.1f m".format(it / 100f) } ?: "—", style)
                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
            },
    )
}

private suspend fun launchTicker(telemetry: VehicleTelemetry, frame: androidx.compose.runtime.MutableLongState) {
    coroutineScope {
        launch { telemetry.obstacles.collect { frame.longValue++ } }
        while (true) {
            delay(500)
            frame.longValue++
        }
    }
}
