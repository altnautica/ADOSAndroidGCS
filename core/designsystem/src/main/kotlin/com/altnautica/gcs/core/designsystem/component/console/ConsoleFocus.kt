package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.overlay.LocalOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion

private const val FOCUS_SCALE = 1.06f
private const val GLOW_ALPHA = 0.28f
private val RingWidth = 2.dp
private val GlowWidth = 6.dp

/**
 * The single focus indication of the app: a 2 dp focus-ring border on [shape] with a
 * soft outer glow while the element below this modifier holds focus. With
 * [scaleOnFocus] the element also grows slightly. Place it before the focus target
 * (`clickable`, `focusable`) in the chain so it observes that target. The element
 * also tells the overlay host it held focus, so closing an overlay can return there.
 */
fun Modifier.consoleFocus(shape: Shape, scaleOnFocus: Boolean = false): Modifier = composed(
    inspectorInfo = debugInspectorInfo {
        name = "consoleFocus"
        properties["shape"] = shape
        properties["scaleOnFocus"] = scaleOnFocus
    },
) {
    var focused by remember { mutableStateOf(false) }
    val host = LocalOverlayHost.current
    val requester = remember { FocusRequester() }
    val ring = AdosTheme.colors.focusRing
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (scaleOnFocus && focused) FOCUS_SCALE else 1f,
        animationSpec = if (reduced) snap() else tween(AdosMotion.QuickMs, easing = AdosMotion.Standard),
        label = "focusScale",
    )
    focusRequester(requester)
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) host?.focusedOutsideOverlay(requester)
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val ringStroke = Stroke(RingWidth.toPx())
            val glowStroke = Stroke(GlowWidth.toPx())
            val glow = ring.copy(alpha = GLOW_ALPHA)
            onDrawWithContent {
                if (focused) drawOutline(outline, glow, style = glowStroke)
                drawContent()
                if (focused) drawOutline(outline, ring, style = ringStroke)
            }
        }
}
