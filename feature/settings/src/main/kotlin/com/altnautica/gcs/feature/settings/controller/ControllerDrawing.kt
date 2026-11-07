package com.altnautica.gcs.feature.settings.controller

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.spec.ArtRole
import com.altnautica.gcs.core.skills.spec.ControllerArtSpec
import com.altnautica.gcs.core.skills.spec.ControllerHotspotsSpec
import com.altnautica.gcs.core.skills.spec.HotspotShape
import com.altnautica.gcs.core.skills.spec.HotspotSpec
import com.altnautica.gcs.core.skills.spec.LabelSide

/** One art path parsed once: its control (for the pressed fill) and role. */
private class ParsedPath(val path: Path, val role: ArtRole, val control: String?)

/** The hotspot bounds in viewBox units. */
internal fun HotspotSpec.bounds(): Rect = when (shape) {
    HotspotShape.Circle -> {
        val cx = geom.cx ?: 0f
        val cy = geom.cy ?: 0f
        val r = geom.r ?: 0f
        Rect(cx - r, cy - r, cx + r, cy + r)
    }
    HotspotShape.Rect -> Rect(geom.x ?: 0f, geom.y ?: 0f, (geom.x ?: 0f) + (geom.w ?: 0f), (geom.y ?: 0f) + (geom.h ?: 0f))
    HotspotShape.Path -> PathParser.createPathFromPathData(geom.d.orEmpty()).asComposePath().getBounds()
}

/** True when [control] is pressed in [pad]: its bit in the stream bitfield, or a trigger past [triggerPressed]. */
internal fun isPressed(control: String, pad: PadSnapshot, bitfield: Map<String, Int>, triggerPressed: Float): Boolean = when (control) {
    "l2" -> pad.axes.leftTrigger >= triggerPressed || bit(pad, bitfield, control)
    "r2" -> pad.axes.rightTrigger >= triggerPressed || bit(pad, bitfield, control)
    else -> bit(pad, bitfield, control)
}

private fun bit(pad: PadSnapshot, bitfield: Map<String, Int>, control: String): Boolean =
    bitfield[control]?.let { pad.buttons and (1 shl it) != 0 } ?: false

/**
 * The controller drawing: stroke-only art from the cockpit spec, pressed controls filled
 * in the accent colour, stick dots inside their rings with the dead-zone ring, trigger
 * fill bars, touchpad touch points, conflict outlines in red, and a leader-line label
 * beside every hotspot. Each hotspot is also a tappable, labelled element (tag
 * `hotspot-<id>`, state "pressed" while held). [pad] is read in the draw pass only, so
 * live input redraws without recomposition.
 */
@Composable
fun ControllerDrawing(
    art: ControllerArtSpec,
    hotspots: ControllerHotspotsSpec,
    pad: State<PadSnapshot>,
    bitfield: Map<String, Int>,
    triggerPressed: Float,
    deadzone: Float,
    labels: Map<String, String>,
    conflicts: Set<String>,
    locked: Set<String>,
    pressedLabel: String,
    conflictLabel: String,
    onHotspot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val vbX = art.viewBox[0]
    val vbY = art.viewBox[1]
    val vbW = art.viewBox[2]
    val vbH = art.viewBox[3]
    val parsed = remember(art) {
        art.paths.map { ParsedPath(PathParser.createPathFromPathData(it.d).asComposePath(), it.role, it.control) }
    }
    val bounds = remember(hotspots) { hotspots.hotspots.mapValues { it.value.bounds() } }
    // Written by the measure pass, read by the draw pass: where the art sits and every leader line.
    var frame by remember { mutableStateOf<ControllerLabelFrame?>(null) }
    val density = LocalDensity.current
    Layout(
        content = {
            for ((id, _) in hotspots.hotspots) {
                if (bounds[id] == null) continue
                val label = labels[id].orEmpty()
                val state = buildList {
                    if (isPressed(id, pad.value, bitfield, triggerPressed)) add(pressedLabel)
                    if (id in conflicts) add(conflictLabel)
                }.joinToString(", ")
                Box(
                    Modifier
                        .layoutId(HotspotId(id))
                        .semantics {
                            contentDescription = "$id: $label"
                            stateDescription = state
                        }
                        .testTag("hotspot-$id")
                        .clickable(role = Role.Button) { onHotspot(id) },
                )
            }
            for ((id, spot) in hotspots.hotspots) {
                val label = labels[id] ?: continue
                if (label.isEmpty()) continue
                Row(
                    Modifier.layoutId(LabelId(id)).testTag("label-$id"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (id in locked) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.fgSecondary, modifier = Modifier.size(12.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (id in conflicts) colors.statusError else colors.fgPrimary,
                        textAlign = when (spot.labelSide) {
                            LabelSide.Left -> TextAlign.End
                            LabelSide.Right -> TextAlign.Start
                            else -> TextAlign.Center
                        },
                    )
                }
            }
        },
        modifier = modifier.fillMaxWidth().drawBehind {
            val f = frame ?: return@drawBehind
            val p = pad.value
            val scale = f.scale
            translate(f.art.left - vbX * scale, f.art.top - vbY * scale) {
                scale(scale, pivot = Offset.Zero) {
                    val stroke = Stroke(width = art.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    for (path in parsed) {
                        val control = path.control
                        if (control != null && isPressed(control, p, bitfield, triggerPressed)) drawPath(path.path, colors.accentPrimary)
                        drawPath(path.path, if (path.role == ArtRole.Detail) colors.fgSecondary else colors.fgPrimary, style = stroke)
                    }
                    // Sticks: the dead-zone ring and the live dot.
                    for ((id, axes) in listOf("stick_l" to (p.axes.leftX to p.axes.leftY), "stick_r" to (p.axes.rightX to p.axes.rightY))) {
                        val b = bounds[id] ?: continue
                        val r = b.width / 2f
                        drawCircle(colors.fgTertiary, r * deadzone, b.center, style = Stroke(2f))
                        drawCircle(colors.accentPrimary, r * 0.22f, b.center + Offset(axes.first * r * 0.7f, axes.second * r * 0.7f))
                    }
                    // Triggers: fill bars proportional to travel.
                    for ((id, v) in listOf("l2" to p.axes.leftTrigger, "r2" to p.axes.rightTrigger)) {
                        val b = bounds[id] ?: continue
                        if (v > 0f) drawRect(colors.accentPrimary.copy(alpha = 0.6f), Offset(b.left, b.top), Size(b.width * v.coerceIn(0f, 1f), b.height))
                    }
                    // Touchpad touch points (normalised 0..1 across the pad).
                    bounds["touchpad"]?.let { b ->
                        for (t in p.touches) drawCircle(colors.accentPrimary, 10f, Offset(b.left + t.x * b.width, b.top + t.y * b.height))
                    }
                    // Conflicts outlined red.
                    for (id in conflicts) {
                        val b = bounds[id] ?: continue
                        drawRoundRect(colors.statusError, Offset(b.left - 6f, b.top - 6f), Size(b.width + 12f, b.height + 12f), CornerRadius(10f), style = Stroke(5f))
                    }
                }
            }
            // Leader lines from each control's anchor out to its label, with a dot on the control.
            val leaderWidth = 1.dp.toPx()
            for ((_, placed) in f.labels) {
                drawLine(colors.borderStrong, placed.leaderFrom, placed.leaderTo, strokeWidth = leaderWidth)
                drawCircle(colors.borderStrong, 2.dp.toPx(), placed.leaderFrom)
            }
        },
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth.toFloat()
        val gutter = minOf(with(density) { LABEL_GUTTER_MAX.toPx() }, totalWidth * GUTTER_FRACTION)
        val gap = with(density) { LABEL_GAP.toPx() }
        val spacing = with(density) { LABEL_SPACING.toPx() }
        val sideMax = (gutter - gap).toInt().coerceAtLeast(1)
        val bandMax = with(density) { LABEL_BAND_MAX.roundToPx() }
        val labelPlaceables = measurables.mapNotNull { m ->
            val id = (m.layoutId as? LabelId)?.id ?: return@mapNotNull null
            val spot = hotspots.hotspots.getValue(id)
            val max = if (spot.labelSide == LabelSide.Left || spot.labelSide == LabelSide.Right) sideMax else bandMax
            id to m.measure(Constraints(maxWidth = max))
        }.toMap()
        val requests = labelPlaceables.map { (id, placeable) ->
            val spot = hotspots.hotspots.getValue(id)
            LabelRequest(
                id = id,
                side = spot.labelSide,
                anchor = Offset(spot.labelAnchor[0] - vbX, spot.labelAnchor[1] - vbY),
                width = placeable.width.toFloat(),
                height = placeable.height.toFloat(),
            )
        }
        val f = layoutControllerLabels(requests, totalWidth, gutter, vbW, vbH, gap, spacing)
        frame = f
        val minTouch = with(density) { 24.dp.roundToPx() }
        val hotspotPlaceables = measurables.mapNotNull { m ->
            val id = (m.layoutId as? HotspotId)?.id ?: return@mapNotNull null
            val b = bounds.getValue(id)
            val w = (b.width * f.scale).roundToInt().coerceAtLeast(minTouch)
            val h = (b.height * f.scale).roundToInt().coerceAtLeast(minTouch)
            Triple(b, m.measure(Constraints.fixed(w, h)), id)
        }
        layout(constraints.maxWidth, ceil(f.height).toInt()) {
            for ((b, placeable, _) in hotspotPlaceables) {
                val cx = f.art.left + (b.center.x - vbX) * f.scale
                val cy = f.art.top + (b.center.y - vbY) * f.scale
                placeable.place((cx - placeable.width / 2f).roundToInt(), (cy - placeable.height / 2f).roundToInt())
            }
            for ((id, placeable) in labelPlaceables) {
                val rect = f.labels.getValue(id).rect
                placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
            }
        }
    }
}

private data class HotspotId(val id: String)

private data class LabelId(val id: String)

private val LABEL_GUTTER_MAX = 128.dp
private val LABEL_BAND_MAX = 112.dp
private val LABEL_GAP = 12.dp
private val LABEL_SPACING = 4.dp
private const val GUTTER_FRACTION = 0.2f

/** A plain colour from `#RRGGBB[AA]`. */
internal fun specColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.substring(0, 6).toLong(16)
    val a = if (digits.length >= 8) digits.substring(6, 8).toLong(16) else 0xFF
    return Color(((a shl 24) or rgb).toInt())
}
