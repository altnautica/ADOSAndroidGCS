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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
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
import com.altnautica.gcs.feature.cockpit.chrome.rememberRedrawBeat
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    var scale by remember { mutableStateOf(RadarScale.Auto) }
    var stale by remember { mutableStateOf(false) }
    var displayM by remember { mutableFloatStateOf(6f) }
    // What the dial shows, sampled on the flight screen's beat (10 Hz) outside the draw pass;
    // the dial redraws, in the HUD's window frame, only when the sample changed.
    val shown = remember { RadarShown() }
    val frame = remember { mutableLongStateOf(0L) }
    val now by rememberUpdatedState(nowMs)
    val beat = rememberRedrawBeat()
    LaunchedEffect(telemetry, scanFlow, navFlow, beat) {
        beat.ticks.collect {
            val next = RadarFrame.sample(telemetry?.obstacles?.value, scanFlow.value, navFlow.value, now(), SystemClock.elapsedRealtime(), scale)
            if (next.stale != stale) stale = next.stale
            if (next.rangeM != displayM) displayM = next.rangeM
            if (next != shown.frame) {
                shown.frame = next
                frame.longValue++
            }
        }
    }
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
    // Only the rounded obstacle rate shows, so the nav stream recomposes the header only when it changes.
    val rateFlow = remember(navFlow) { navFlow.map { it?.value?.obstacleRateHz?.let { hz -> Math.round(hz) } }.distinctUntilChanged() }
    val rateNow = remember(navFlow) { navFlow.value?.value?.obstacleRateHz?.let { Math.round(it) } }
    val rate by rateFlow.collectAsState(rateNow)
    val headerText = rate?.let { specText("radar.header", "hz" to it.toString()).upperFirstWord() } ?: scanNone
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.alpha(if (stale) 0.5f else 1f)) {
        Spacer(
            Modifier
                .size(diameter)
                .semantics { contentDescription = description }
                .clickable(onClickLabel = cycleLabel, role = Role.Button) { scale = LocalFrame.nextScale(scale) }
                // Its own render node: a beat's redraw re-records only the dial.
                .graphicsLayer()
                .drawWithCache {
                    val hair = Stroke(1.dp.toPx())
                    val edge = Stroke(2.dp.toPx())
                    onDrawBehind {
                        frame.longValue
                        val f = shown.frame ?: return@onDrawBehind
                        val sec = f.sectors
                        val scan = f.scan
                        val range = f.rangeM
                        val center = Offset(size.width / 2, size.height / 2)
                        val outer = size.minDimension / 2 - 4.dp.toPx()
                        drawCircle(colors.hudGlass, outer, center)
                        for (ring in RINGS) {
                            drawCircle(colors.hudHair, outer * ring, center, style = hair)
                            val label = measurer.measure(HudGeometry.oneDecimal(range * ring).removeSuffix(".0"), ringStyle)
                            val p = outer * ring * 0.7071f
                            drawText(label, topLeft = Offset(center.x + p, center.y - p - label.size.height))
                        }
                        drawLine(colors.hudHair, Offset(center.x, center.y - outer), Offset(center.x, center.y + outer))
                        drawLine(colors.hudHair, Offset(center.x - outer, center.y), Offset(center.x + outer, center.y))
                        if (sec != null) {
                            sec.distancesCm.forEachIndexed { i, cm ->
                                if (!RadarMath.valid(cm, sec) || cm > LocalFrame.CAUTION_M * 100) return@forEachIndexed
                                val color = if (cm < LocalFrame.DANGER_M * 100) colors.hudCrit else colors.hudWarn
                                val r = (cm / 100f / range).coerceAtMost(1f) * outer
                                val start = RadarMath.sectorStart(i, sec) - 90f
                                drawArc(color.copy(alpha = 0.35f), start, sec.incrementDeg, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
                                drawArc(color, start, sec.incrementDeg, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = edge)
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
                        val top = measurer.measure(if (f.northAligned && scan == null) "N" else "FWD", style)
                        drawText(top, topLeft = Offset(center.x - top.size.width / 2f, center.y - outer + 2.dp.toPx()))
                        val nearest = f.nearestM
                        val readout = if (nearest != null && !f.stale) "${HudGeometry.oneDecimal(nearest)} m ${words.getValue(LocalFrame.proximityWord(nearest))}" else "—"
                        val label = measurer.measure(readout, style)
                        drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y + outer / 3))
                        val h = measurer.measure(headerText, ringStyle)
                        drawText(h, topLeft = Offset(center.x - h.size.width / 2f, center.y - outer / 3 - h.size.height))
                    }
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

/** The dial's three range rings, as fractions of the display range. */
private val RINGS = floatArrayOf(1f / 3, 2f / 3, 1f)

/** The last [RadarFrame] sampled on the beat; read by the draw pass only. */
private class RadarShown {
    var frame: RadarFrame? = null
}

/**
 * One beat's sample of what the dial shows: the fresh sectors and scan (null when stale),
 * the nearest distance, the display range and whether both sources are stale.
 */
internal data class RadarFrame(
    val sectors: ObstacleSectors?,
    val northAligned: Boolean,
    val scan: ScanFrame?,
    val nearestM: Float?,
    val rangeM: Float,
    val stale: Boolean,
) {
    companion object {
        /** [nowMs] is the autonomy clock (scan, nav); [elapsedMs] the clock the sectors are stamped with. */
        fun sample(s: ObstacleSectors?, scanNow: ScanFrame?, navNow: Stamped<NavState>?, nowMs: Long, elapsedMs: Long, scale: RadarScale): RadarFrame {
            val sectors = s?.takeIf { elapsedMs - it.tMs <= OBSTACLE_STALE_MS }
            val scan = scanNow?.takeIf { nowMs - it.receivedAtMs <= LocalFrame.SCAN_STALE_MS }
            val nav = navNow?.takeIf { nowMs - it.atMs <= OBSTACLE_STALE_MS }?.value
            val sectorNearest = sectors?.let { RadarMath.nearestCm(it) }?.let { it / 100f }
            val nearest = listOfNotNull(sectorNearest, scan?.let(LocalFrame::nearestScanM), nav?.minObstacleM).minOrNull()
            return RadarFrame(sectors, s?.northAligned == true, scan, nearest, LocalFrame.displayRange(scale, nearest), stale = sectors == null && scan == null)
        }
    }
}
