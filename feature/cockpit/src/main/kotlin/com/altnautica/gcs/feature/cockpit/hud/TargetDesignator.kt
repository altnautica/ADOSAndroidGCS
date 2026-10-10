package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.extensions.skills.ExtensionTargetActionSkill
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.SlotUi
import com.altnautica.gcs.feature.cockpit.skillbar.reasonText
import com.altnautica.gcs.feature.cockpit.skillbar.skillIcon
import com.altnautica.gcs.feature.cockpit.skillbar.skillLabel
import kotlin.math.max
import kotlin.math.min

internal object TargetBox {
    /** Smallest box, as a fraction of the frame, that counts as a designation. */
    const val MIN_FRACTION = 0.02f

    /** Normalizes a drag rectangle to the frame: x, y, width, height in 0..1. Null when too small. */
    fun normalize(a: Offset, b: Offset, frame: IntSize): Map<String, Float>? {
        if (frame.width <= 0 || frame.height <= 0) return null
        val left = (min(a.x, b.x) / frame.width).coerceIn(0f, 1f)
        val top = (min(a.y, b.y) / frame.height).coerceIn(0f, 1f)
        val right = (max(a.x, b.x) / frame.width).coerceIn(0f, 1f)
        val bottom = (max(a.y, b.y) / frame.height).coerceIn(0f, 1f)
        if (right - left < MIN_FRACTION || bottom - top < MIN_FRACTION) return null
        return mapOf("x" to left, "y" to top, "width" to right - left, "height" to bottom - top)
    }
}

/**
 * Target designation over the video: long-press and drag draws a box, then the
 * extension target actions that apply are offered for it. Mounted only when an
 * extension contributes target actions.
 */
@Composable
fun TargetDesignator(
    actions: List<SlotUi>,
    onAction: (ExtensionTargetActionSkill, Map<String, Float>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    val colors = AdosTheme.colors
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var box by remember { mutableStateOf<Map<String, Float>?>(null) }
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { frame = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        start = p
                        end = p
                        box = null
                    },
                    onDrag = { change, _ -> end = change.position },
                    onDragEnd = {
                        val a = start
                        val b = end
                        box = if (a != null && b != null) TargetBox.normalize(a, b, frame) else null
                        if (box == null) {
                            start = null
                            end = null
                        }
                    },
                    onDragCancel = {
                        start = null
                        end = null
                        box = null
                    },
                )
            }
            .drawBehind {
                val a = start ?: return@drawBehind
                val b = end ?: return@drawBehind
                val rect = Rect(Offset(min(a.x, b.x), min(a.y, b.y)), Offset(max(a.x, b.x), max(a.y, b.y)))
                drawRect(colors.hudLock, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
            },
    ) {
        val anchor = end
        if (box != null && anchor != null) {
            Box(Modifier.offset(with(density) { anchor.x.toDp() }, with(density) { anchor.y.toDp() })) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = {
                        box = null
                        start = null
                        end = null
                    },
                ) {
                    Text(
                        stringResource(R.string.cockpit_target_actions),
                        modifier = Modifier.offset(x = 12.dp),
                        color = colors.fgSecondary,
                    )
                    for (slot in actions) {
                        val skill = slot.skill as? ExtensionTargetActionSkill ?: continue
                        val state = slot.state
                        val enabled = state?.enabled ?: false
                        DropdownMenuItem(
                            text = {
                                Text(
                                    skillLabel(skill) + (state?.reasonKey?.takeIf { !enabled }?.let { " · " + reasonText(it) } ?: ""),
                                )
                            },
                            leadingIcon = { Icon(skillIcon(skill.icon), contentDescription = null) },
                            enabled = enabled,
                            onClick = {
                                val b = box
                                box = null
                                start = null
                                end = null
                                if (b != null) onAction(skill, b)
                            },
                        )
                    }
                }
            }
        }
    }
}
