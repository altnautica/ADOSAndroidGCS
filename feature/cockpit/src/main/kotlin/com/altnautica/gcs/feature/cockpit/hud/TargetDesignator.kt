package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.feature.cockpit.flow.specText
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.extensions.skills.ExtensionTargetActionSkill
import com.altnautica.gcs.feature.cockpit.SlotUi
import com.altnautica.gcs.feature.cockpit.autonomy.DrawItem
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.skillbar.reasonText
import com.altnautica.gcs.feature.cockpit.skillbar.skillIcon
import com.altnautica.gcs.feature.cockpit.skillbar.skillLabel
import kotlin.math.max
import kotlin.math.min

internal object TargetBox {
    /** Smallest drawn box, as a fraction of the frame width or height, that counts as a designation. */
    const val MIN_FRACTION = 0.01f

    /**
     * A drag rectangle in surface px → frame px (x, y, width, height), given the
     * letterboxed frame at ([left], [top]) scaled by [scale]. Null when too small.
     */
    fun toFrame(a: Offset, b: Offset, left: Float, top: Float, scale: Float, frameW: Float, frameH: Float): Map<String, Float>? {
        if (frameW <= 0f || frameH <= 0f || scale <= 0f) return null
        fun fx(v: Float) = ((v - left) / scale).coerceIn(0f, frameW)
        fun fy(v: Float) = ((v - top) / scale).coerceIn(0f, frameH)
        val x0 = fx(min(a.x, b.x))
        val x1 = fx(max(a.x, b.x))
        val y0 = fy(min(a.y, b.y))
        val y1 = fy(max(a.y, b.y))
        if (x1 - x0 < MIN_FRACTION * frameW || y1 - y0 < MIN_FRACTION * frameH) return null
        return mapOf("x" to x0, "y" to y0, "width" to x1 - x0, "height" to y1 - y0)
    }
}

/** The target-action menu request: where it opens, the box (frame px) and the item tapped, if any. */
@Stable
internal class DesignatorState {
    var anchor by mutableStateOf<Offset?>(null)
    var box by mutableStateOf<Map<String, Float>?>(null)
    var item by mutableStateOf<DrawItem?>(null)

    fun open(at: Offset, box: Map<String, Float>, item: DrawItem?) {
        anchor = at
        this.box = box
        this.item = item
    }

    fun close() {
        anchor = null
        box = null
        item = null
    }
}

/**
 * Target designation over the video. Tapping a drawn target (smallest box wins, each
 * inflated to 48 dp) or long-pressing and dragging a box opens the target-action menu:
 * Lock (vision designate) for a detection, then the extension target actions.
 */
@Composable
internal fun TargetDesignator(
    layer: TargetLayerState,
    state: DesignatorState,
    actions: List<SlotUi>,
    onLock: (DrawItem) -> Unit,
    onAction: (ExtensionTargetActionSkill, Map<String, Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val minHit = with(density) { 48.dp.toPx() }
    val menu = rememberAdosMenuState()
    LaunchedEffect(state.anchor) { menu.expanded = state.anchor != null }
    LaunchedEffect(menu.expanded) {
        if (!menu.expanded) {
            state.close()
            start = null
            end = null
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(layer) {
                // A double tap is reserved, so a single tap waits out the double-tap interval.
                detectTapGestures(onDoubleTap = {}) { p ->
                    val (left, top, scale) = layer.fit(size.width.toFloat(), size.height.toFloat())
                    val drawn = layer.items.filter { it.rect != null && it.detection != null }
                    val boxes = drawn.map { it.rect!! }
                    val hit = HudGeometry.hitTest(boxes, (p.x - left) / scale, (p.y - top) / scale, minHit / scale)
                    if (hit >= 0) {
                        val b = boxes[hit]
                        state.open(p, mapOf("x" to b.x, "y" to b.y, "width" to b.w, "height" to b.h), drawn[hit])
                    }
                }
            }
            .pointerInput(layer) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        start = p
                        end = p
                    },
                    onDrag = { change, _ -> end = change.position },
                    onDragEnd = {
                        val a = start
                        val b = end
                        val (left, top, scale) = layer.fit(size.width.toFloat(), size.height.toFloat())
                        val box = if (a != null && b != null) TargetBox.toFrame(a, b, left, top, scale, layer.frameW, layer.frameH) else null
                        if (box != null && b != null) state.open(b, box, null) else {
                            start = null
                            end = null
                        }
                    },
                    onDragCancel = {
                        start = null
                        end = null
                    },
                )
            }
            .drawBehind {
                val a = start ?: return@drawBehind
                val b = end ?: return@drawBehind
                val rect = Rect(Offset(min(a.x, b.x), min(a.y, b.y)), Offset(max(a.x, b.x), max(a.y, b.y)))
                drawRect(colors.hudInk.copy(alpha = 0.12f), rect.topLeft, rect.size)
                drawRect(colors.hudInk, rect.topLeft, rect.size, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            },
    ) {
        val anchor = state.anchor
        val box = state.box
        if (anchor != null && box != null) {
            Box(
                Modifier
                    .offset(with(density) { anchor.x.toDp() }, with(density) { anchor.y.toDp() })
                    .size(1.dp)
                    .adosMenuAnchor(menu),
            )
            val lockLabel = specText("target.lock")
            val tapped = state.item
            val lock = tapped?.let { item ->
                AdosMenuItem(label = lockLabel, icon = Icons.Outlined.CenterFocusStrong, onClick = { onLock(item) })
            }
            val extension = actions.mapNotNull { slot ->
                val skill = slot.skill as? ExtensionTargetActionSkill ?: return@mapNotNull null
                val st = slot.state
                val enabled = st?.enabled ?: false
                AdosMenuItem(
                    label = skillLabel(skill) + (st?.reasonKey?.takeIf { !enabled }?.let { " · " + reasonText(it) } ?: ""),
                    icon = skillIcon(skill.icon),
                    enabled = enabled,
                    onClick = { onAction(skill, box) },
                )
            }
            AdosMenu(menu, listOfNotNull(lock) + extension)
        }
    }
}
