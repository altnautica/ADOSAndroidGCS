package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.feature.cockpit.flow.specText
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.model.ObstacleSectors
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.LocalFrame
import com.altnautica.gcs.feature.cockpit.autonomy.NavState
import com.altnautica.gcs.feature.cockpit.autonomy.ProximityWord
import com.altnautica.gcs.feature.cockpit.autonomy.RadarScale
import com.altnautica.gcs.feature.cockpit.autonomy.ScanFrame
import com.altnautica.gcs.feature.cockpit.autonomy.Stamped
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** Obstacle data older than this is not drawn: a dead link must never paint a stale danger arc. */
private const val OBSTACLE_STALE_MS = 2_000L
private const val MAX_DOTS = 360
private const val MAX_DOTS_LOW_POWER = 120

internal object RadarMath {
    /** Sector start angle in degrees clockwise from the dial top. */
    fun sectorStart(index: Int, s: ObstacleSectors): Float = s.angleOffsetDeg + index * s.incrementDeg - s.incrementDeg / 2

    /** True when a reading is a real distance (0 and above max mean no reading). */
    fun valid(cm: Int, s: ObstacleSectors) = cm > 0 && cm >= s.minCm && cm <= s.maxCm

    fun nearestCm(s: ObstacleSectors): Int? = s.distancesCm.filter { valid(it, s) }.minOrNull()
}

/**
 * Proximity radar: OBSTACLE_DISTANCE sectors (caution and danger wedges with an arc at
 * the measured distance) plus the companion's planar scan as dots, on a 3/6/12 m or
 * automatic scale that a tap cycles. The dial top reads FWD for body data and N for
 * north-aligned sectors. Hidden until either source has arrived; both stale dims it.
 */
@Composable
fun ProximityRadar(
    telemetry: VehicleTelemetry?,
    scanFlow: StateFlow<ScanFrame?>,
    navFlow: StateFlow<Stamped<NavState>?>,
    nowMs: () -> Long,
    modifier: Modifier = Modifier,
    diameter: Dp = 120.dp,
    lowPower: Boolean = false,
) {
    val hasSectors by produceState(false, telemetry) {
        telemetry?.obstacles?.map { it != null }?.collect { value = value || it }
    }
    val hasScan by produceState(false, scanFlow) { scanFlow.collect { if (it != null) value = true } }
    if (!hasSectors && !hasScan) return
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(telemetry, scanFlow) {
        while (true) {
            frame.longValue++
            delay(100)
        }
    }
    var scale by remember { mutableStateOf(RadarScale.Auto) }
    val colors = AdosTheme.colors
    val measurer = rememberTextMeasurer()
    val style = AdosTypography.numeric.small.copy(color = colors.hudInk)
    val ringStyle = AdosTypography.numeric.small.copy(color = colors.hudMuted, fontSize = 9.sp)
    val description = specText("cockpit.radar.aria")
    val cycleLabel = specText("radar.cycle")
    val words = mapOf(
        ProximityWord.Clear to specText("skill.avoid.clear").uppercase(),
        ProximityWord.Caution to specText("radar.caution").uppercase(),
        ProximityWord.Danger to specText("radar.danger").uppercase(),
    )
    val scanNone = specText("radar.scan_none").uppercase()
    val navNow by navFlow.collectAsState()
    val rate = navNow?.value?.obstacleRateHz
    val headerText = rate?.let { specText("radar.header", "hz" to Math.round(it).toString()).upperFirstWord() } ?: scanNone
    var stale by remember { mutableStateOf(false) }
    var displayM by remember { mutableFloatStateOf(6f) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.alpha(if (stale) 0.5f else 1f)) {
        Spacer(
            Modifier
                .size(diameter)
                .semantics { contentDescription = description }
                .clickable(onClickLabel = cycleLabel, role = Role.Button) { scale = LocalFrame.nextScale(scale) }
                .drawBehind {
                    frame.longValue
                    val now = nowMs()
                    val s = telemetry?.obstacles?.value
                    val sectorsFresh = s != null && SystemClock.elapsedRealtime() - s.tMs <= OBSTACLE_STALE_MS
                    val scan = scanFlow.value?.takeIf { now - it.receivedAtMs <= LocalFrame.SCAN_STALE_MS }
                    val nav = navFlow.value?.takeIf { now - it.atMs <= OBSTACLE_STALE_MS }?.value
                    stale = !sectorsFresh && scan == null
                    val sectorNearest = if (sectorsFresh) RadarMath.nearestCm(s!!)?.let { it / 100f } else null
                    val nearest = listOfNotNull(sectorNearest, scan?.let(LocalFrame::nearestScanM), nav?.minObstacleM).minOrNull()
                    val range = LocalFrame.displayRange(scale, nearest)
                    displayM = range
                    val center = Offset(size.width / 2, size.height / 2)
                    val outer = size.minDimension / 2 - 4.dp.toPx()
                    drawCircle(colors.hudGlass, outer, center)
                    for (f in listOf(1f / 3, 2f / 3, 1f)) {
                        drawCircle(colors.hudHair, outer * f, center, style = Stroke(1.dp.toPx()))
                        val label = measurer.measure(HudGeometry.oneDecimal(range * f).removeSuffix(".0"), ringStyle)
                        val p = outer * f * 0.7071f
                        drawText(label, topLeft = Offset(center.x + p, center.y - p - label.size.height))
                    }
                    drawLine(colors.hudHair, Offset(center.x, center.y - outer), Offset(center.x, center.y + outer))
                    drawLine(colors.hudHair, Offset(center.x - outer, center.y), Offset(center.x + outer, center.y))
                    if (sectorsFresh) {
                        val sec = s!!
                        sec.distancesCm.forEachIndexed { i, cm ->
                            if (!RadarMath.valid(cm, sec) || cm > LocalFrame.CAUTION_M * 100) return@forEachIndexed
                            val color = if (cm < LocalFrame.DANGER_M * 100) colors.hudCrit else colors.hudWarn
                            val r = (cm / 100f / range).coerceAtMost(1f) * outer
                            val start = RadarMath.sectorStart(i, sec) - 90f
                            drawArc(color.copy(alpha = 0.35f), start, sec.incrementDeg, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
                            drawArc(color, start, sec.incrementDeg, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(2.dp.toPx()))
                        }
                    }
                    if (scan != null) {
                        val dots = LocalFrame.scanDots(scan, range, center.x, center.y, outer, if (lowPower) MAX_DOTS_LOW_POWER else MAX_DOTS)
                        val r = 1.5.dp.toPx()
                        var k = 0
                        while (k < dots.size) {
                            drawCircle(colors.hudInk.copy(alpha = 0.75f), r, Offset(dots[k], dots[k + 1]))
                            k += 2
                        }
                    }
                    val top = measurer.measure(if (s?.northAligned == true && scan == null) "N" else "FWD", style)
                    drawText(top, topLeft = Offset(center.x - top.size.width / 2f, center.y - outer + 2.dp.toPx()))
                    val readout = nearest?.takeIf { !stale }?.let { "${HudGeometry.oneDecimal(it)} m ${words.getValue(LocalFrame.proximityWord(it))}" } ?: "—"
                    val label = measurer.measure(readout, style)
                    drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + outer / 3))
                    val header = headerText
                    val h = measurer.measure(header, ringStyle)
                    drawText(h, topLeft = Offset(center.x - h.size.width / 2f, center.y - outer / 3 - h.size.height))
                },
        )
        val scaleLabel = if (scale.rangeM == null) {
            specText("radar.scale_auto", "m" to displayM.toInt().toString()).upperFirstWord()
        } else {
            specText("radar.scale", "m" to displayM.toInt().toString())
        }
        Text(scaleLabel, style = ringStyle)
    }
}
