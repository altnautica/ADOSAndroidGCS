package com.altnautica.gcs.feature.settings.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
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
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(vbW / vbH)) {
        val density = LocalDensity.current
        val scale = with(density) { maxWidth.toPx() } / vbW
        Canvas(Modifier.fillMaxSize()) {
            val p = pad.value
            translate(-vbX * scale, -vbY * scale) {
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
                    // Conflicts outlined red; leader lines to every label anchor.
                    for ((id, spot) in hotspots.hotspots) {
                        val b = bounds[id] ?: continue
                        if (id in conflicts) {
                            drawRoundRect(colors.statusError, Offset(b.left - 6f, b.top - 6f), Size(b.width + 12f, b.height + 12f), CornerRadius(10f), style = Stroke(5f))
                        }
                        if (labels[id] != null) {
                            val anchor = Offset(spot.labelAnchor[0], spot.labelAnchor[1])
                            drawLine(colors.borderStrong, b.center, anchor, strokeWidth = 2f)
                        }
                    }
                }
            }
        }
        // Tappable hotspots with their labels, positioned in the same scaled frame.
        for ((id, spot) in hotspots.hotspots) {
            val b = bounds[id] ?: continue
            val x = with(density) { ((b.left - vbX) * scale).toDp() }
            val y = with(density) { ((b.top - vbY) * scale).toDp() }
            val w = with(density) { (b.width * scale).toDp() }.coerceAtLeast(24.dp)
            val h = with(density) { (b.height * scale).toDp() }.coerceAtLeast(24.dp)
            val label = labels[id].orEmpty()
            val state = buildList {
                if (isPressed(id, pad.value, bitfield, triggerPressed)) add(pressedLabel)
                if (id in conflicts) add(conflictLabel)
            }.joinToString(", ")
            Box(
                Modifier
                    .offset(x, y)
                    .size(w, h)
                    .semantics {
                        contentDescription = "$id: $label"
                        stateDescription = state
                    }
                    .testTag("hotspot-$id")
                    .clickable(role = Role.Button) { onHotspot(id) },
            )
            if (label.isNotEmpty()) {
                val ax = with(density) { ((spot.labelAnchor[0] - vbX) * scale).toDp() }
                val ay = with(density) { ((spot.labelAnchor[1] - vbY) * scale).toDp() }
                val boxW = 96.dp
                val (lx, ly) = when (spot.labelSide) {
                    LabelSide.Left -> (ax - boxW) to (ay - 8.dp)
                    LabelSide.Right -> ax to (ay - 8.dp)
                    LabelSide.Top -> (ax - boxW / 2) to (ay - 20.dp)
                    LabelSide.Bottom -> (ax - boxW / 2) to ay
                }
                androidx.compose.foundation.layout.Row(
                    Modifier.offset(lx, ly).widthIn(max = boxW).testTag("label-$id"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    if (id in locked) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = colors.fgSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (id in conflicts) colors.statusError else colors.fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = when (spot.labelSide) {
                            LabelSide.Left -> TextAlign.End
                            LabelSide.Right -> TextAlign.Start
                            else -> TextAlign.Center
                        },
                    )
                }
            }
        }
    }
}

/** A plain colour from `#RRGGBB[AA]`. */
internal fun specColor(hex: String): Color {
    val digits = hex.removePrefix("#")
    val rgb = digits.substring(0, 6).toLong(16)
    val a = if (digits.length >= 8) digits.substring(6, 8).toLong(16) else 0xFF
    return Color(((a shl 24) or rgb).toInt())
}
