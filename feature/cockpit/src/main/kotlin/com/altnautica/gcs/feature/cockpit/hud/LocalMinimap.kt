package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.theme.AdosFonts
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosHudTarget
import androidx.compose.ui.platform.LocalDensity
import com.altnautica.gcs.feature.cockpit.autonomy.AutonomyFeed
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.LocalFrame
import com.altnautica.gcs.feature.cockpit.autonomy.OccupancyLayer
import com.altnautica.gcs.feature.cockpit.autonomy.PoseFrame
import com.altnautica.gcs.feature.cockpit.autonomy.PoseHealth
import com.altnautica.gcs.feature.cockpit.autonomy.ScanFrame
import com.altnautica.gcs.feature.cockpit.autonomy.TrailPoint
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MIN_PX_PER_M = 2f
private const val MAX_PX_PER_M = 80f
private const val RECENTRE_MS = 10_000L

/** What the local minimap draws, sampled by the caller. */
internal data class LocalMapInputs(
    val pose: PoseFrame?,
    val fcPosition: LocalPosition?,
    val attitude: Attitude?,
    val trail: List<TrailPoint>,
    val scan: ScanFrame?,
    val occupancy: OccupancyLayer?,
    val follow: FollowState?,
    val nowMs: Long,
)

/**
 * Minimap local frame (north up, east right, metres): grid, live occupancy tiles, the
 * SLAM trail (dashed while degraded, broken across lost), scan returns, the origin, the
 * followed target and the drone. The header carries `LOCAL · N/E m`, `FC POS` when the
 * drone falls back to LOCAL_POSITION_NED, the AUTO | LOCAL | MAP pin and a scale bar.
 */
@Composable
internal fun LocalMinimap(
    inputs: LocalMapInputs,
    pin: String,
    onPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AdosTheme.colors
    var pxPerM by remember { mutableFloatStateOf(10f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var lastPanMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lastPanMs) {
        if (lastPanMs == 0L) return@LaunchedEffect
        delay(RECENTRE_MS)
        pan = Offset.Zero
    }
    val measurer = rememberTextMeasurer()
    val small = AdosTypography.numeric.small.copy(fontFamily = AdosFonts.Inter, fontSize = 10.sp, color = c.hudInk)
    val originLabel = specText("minimap.origin").uppercase()
    val description = specText("minimap.description")
    val poseFresh = inputs.pose?.takeIf { inputs.nowMs - it.receivedAtMs <= LocalFrame.POSE_STALE_MS }
    val fcPos = poseFresh == null
    val droneN = poseFresh?.northM ?: inputs.fcPosition?.northM ?: 0f
    val droneE = poseFresh?.eastM ?: inputs.fcPosition?.eastM ?: 0f
    val yaw = inputs.attitude?.yawRad ?: poseFresh?.yawRad ?: 0f
    val scaleM = LocalFrame.scaleBarM(pxPerM)
    Box(modifier.background(c.hudGlassStrong)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTransformGestures { _, panBy, zoom, _ ->
                        pxPerM = (pxPerM * zoom).coerceIn(MIN_PX_PER_M, MAX_PX_PER_M)
                        if (panBy != Offset.Zero) {
                            pan += panBy
                            lastPanMs = System.nanoTime()
                        }
                    }
                },
        ) {
            val sc = Offset(size.width / 2 + pan.x, size.height / 2 + pan.y)
            fun pt(n: Float, e: Float): Offset = LocalFrame.toScreen(n, e, droneN, droneE, pxPerM, sc.x, sc.y).let { Offset(it.first, it.second) }
            grid(sc, scaleM * pxPerM, c.hudHair)
            inputs.occupancy?.let { occ ->
                val dim = inputs.nowMs - (occ.lastOkMs ?: 0L) > AutonomyFeed.MAP_DIM_MS
                val span = occ.cells * occ.resM
                for ((key, img) in occ.tiles) {
                    val (n, e) = LocalFrame.tileAnchor(key.first, key.second, occ.resM, occ.cells)
                    val tl = pt(n, e)
                    val side = span * pxPerM
                    if (tl.x > size.width || tl.y > size.height || tl.x + side < 0 || tl.y + side < 0) continue
                    drawImage(
                        img,
                        dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
                        dstSize = IntSize(side.roundToInt().coerceAtLeast(1), side.roundToInt().coerceAtLeast(1)),
                        alpha = if (dim) 0.5f else 1f,
                        colorFilter = ColorFilter.tint(c.hudInk, BlendMode.SrcIn),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
            trail(inputs.trail, ::pt, c.hudPrimary)
            val scan = inputs.scan?.takeIf { inputs.nowMs - it.receivedAtMs <= LocalFrame.SCAN_STALE_MS }
            if (scan != null && poseFresh != null) {
                for (i in scan.rangesM.indices) {
                    val r = scan.rangesM[i]
                    if (r <= 0f) continue
                    val th = scan.angleStartRad + i * scan.angleIncrementRad
                    val (n, e) = LocalFrame.bodyToMap(r * cos(th), r * sin(th), poseFresh.northM, poseFresh.eastM, poseFresh.yawRad)
                    drawCircle(c.hudInk.copy(alpha = 0.6f), 1.dp.toPx(), pt(n, e))
                }
            }
            val o = pt(0f, 0f)
            val arm = 6.dp.toPx()
            drawLine(c.hudInk, Offset(o.x - arm, o.y), Offset(o.x + arm, o.y), 1.dp.toPx())
            drawLine(c.hudInk, Offset(o.x, o.y - arm), Offset(o.x, o.y + arm), 1.dp.toPx())
            drawText(measurer.measure(originLabel, small.copy(fontSize = 9.sp)), topLeft = Offset(o.x + arm, o.y + 2))
            inputs.follow?.let { target(it, ::pt, c) }
            drone(pt(droneN, droneE), yaw, c)
        }
        Column(Modifier.padding(AdosSpacing.S1)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                Text(specText("minimap.local_ne").upperFirstWord(), style = small)
                if (fcPos) Text(specText("minimap.fc_pos").uppercase(), style = small, color = c.hudWarn)
            }
            PinToggle(pin, onPin)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(AdosSpacing.S1), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = with(LocalDensity.current) { (scaleM * pxPerM).toDp() }, height = 6.dp)) {
                drawLine(c.hudInk, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
                drawLine(c.hudInk, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                drawLine(c.hudInk, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
            }
            Text(" " + specText("minimap.scale", "m" to scaleM.toString()), style = small)
        }
        Row(Modifier.align(Alignment.BottomEnd)) {
            IconButton(onClick = { pxPerM = (pxPerM / 1.5f).coerceAtLeast(MIN_PX_PER_M) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Remove, contentDescription = specText("cockpit.zoom.out"), tint = c.hudInk)
            }
            IconButton(onClick = { pxPerM = (pxPerM * 1.5f).coerceAtMost(MAX_PX_PER_M) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = specText("cockpit.zoom.in"), tint = c.hudInk)
            }
        }
    }
}

@Composable
private fun PinToggle(pin: String, onPin: (String) -> Unit) {
    val c = AdosTheme.colors
    val style = AdosTypography.numeric.small.copy(fontFamily = AdosFonts.Inter, fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in listOf(
            CockpitSettingsRepository.MINIMAP_AUTO to specText("minimap.pin.auto"),
            CockpitSettingsRepository.MINIMAP_LOCAL to specText("minimap.pin.local"),
            CockpitSettingsRepository.MINIMAP_MAP to specText("minimap.pin.map"),
        )) {
            val on = value == pin
            Text(
                label,
                style = style,
                color = if (on) c.hudInk else c.hudMuted,
                modifier = Modifier
                    .background(if (on) c.hudPrimary.copy(alpha = 0.35f) else c.hudGlass, RoundedCornerShape(4.dp))
                    .clickable { onPin(value) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun DrawScope.grid(center: Offset, step: Float, color: androidx.compose.ui.graphics.Color) {
    if (step < 4f) return
    var x = center.x % step
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = center.y % step
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

/** Polyline of the trail; degraded segments dashed, lost points break the line. */
private fun DrawScope.trail(points: List<TrailPoint>, pt: (Float, Float) -> Offset, color: androidx.compose.ui.graphics.Color) {
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        if (a.health == PoseHealth.Lost || b.health == PoseHealth.Lost) continue
        val dashed = a.health == PoseHealth.Degraded || b.health == PoseHealth.Degraded
        drawLine(
            color, pt(a.north, a.east), pt(b.north, b.east), 1.5.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
        )
    }
}

private fun DrawScope.target(f: FollowState, pt: (Float, Float) -> Offset, c: AdosColorRoles) {
    val ned = f.targetNed ?: return
    val predicted = f.phase == FollowPhase.Coasting || f.phase == FollowPhase.Searching
    val p = pt(ned.first, ned.second)
    val s = 5.dp.toPx()
    val diamond = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s, p.y); lineTo(p.x, p.y + s); lineTo(p.x - s, p.y); close() }
    val effect = if (predicted) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null
    val colour = if (predicted) c.hudCoast else c.hudTrack
    drawPath(diamond, AdosHudTarget.OutlineColor, style = Stroke(3.5.dp.toPx(), pathEffect = effect))
    drawPath(diamond, colour, style = Stroke(1.5.dp.toPx(), pathEffect = effect))
    // Velocity tick: one second of travel.
    f.targetVelNed?.let { (vn, ve) -> drawLine(colour, p, pt(ned.first + vn, ned.second + ve), 1.5.dp.toPx()) }
}

private fun DrawScope.drone(p: Offset, yaw: Float, c: AdosColorRoles) {
    val s = 7.dp.toPx()
    rotate(Math.toDegrees(yaw.toDouble()).toFloat(), p) {
        val arrow = Path().apply { moveTo(p.x, p.y - s); lineTo(p.x + s * 0.7f, p.y + s); lineTo(p.x, p.y + s * 0.5f); lineTo(p.x - s * 0.7f, p.y + s); close() }
        drawPath(arrow, AdosHudTarget.OutlineColor, style = Stroke(2.dp.toPx()))
        drawPath(arrow, c.hudInk)
    }
}
