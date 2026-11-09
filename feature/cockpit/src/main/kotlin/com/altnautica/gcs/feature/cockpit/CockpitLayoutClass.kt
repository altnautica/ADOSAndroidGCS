package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.skills.spec.LayoutClassSpec
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevel
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerPresentation
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** The cockpit's layout class (`layout.json` `classes`): the width class in landscape, `portrait` otherwise. */
internal val LocalLayoutClass = staticCompositionLocalOf<LayoutClassSpec?> { null }

internal fun layoutClassId(width: WidthClass, landscape: Boolean): String = if (landscape) width.id else PORTRAIT

/** How the class shapes the data layers. */
internal fun LayoutClassSpec?.layerPresentation(): LayerPresentation =
    LayerPresentation(fpsCap = this?.overlayFpsCap, distanceOnly = this?.labels == LABELS_DISTANCE_ONLY)

/** The layer level a vehicle starts at on this class before the operator picks one. */
internal fun LayoutClassSpec?.defaultLayerLevel(): LayerLevel = LayerLevel.of(this?.defaultLayerLevel) ?: LayerLevel.Full

/** The class shows distances only (`labels: distance_only`). */
internal val LayoutClassSpec?.distanceOnly: Boolean get() = this?.labels == LABELS_DISTANCE_ONLY

/** The class draws a ring on whatever holds focus, however it got there (`focus_rings: always`). */
internal val LayoutClassSpec?.focusRingsAlways: Boolean get() = this?.focusRings == FOCUS_RINGS_ALWAYS

/**
 * With [on], draws a [color] ring around the focused element anywhere below this
 * modifier, so D-pad navigation on a television is always visible.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.focusRings(on: Boolean, color: Color): Modifier = if (!on) this else composed {
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var focused by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { root = it }
        .onFocusedBoundsChanged { focused = it }
        .drawWithContent {
            drawContent()
            val r = root ?: return@drawWithContent
            val f = focused?.takeIf { it.isAttached } ?: return@drawWithContent
            val box = r.localBoundingBoxOf(f, clipBounds = false)
            // The stroke straddles its rect, so inflating by half its width puts it just outside the control.
            val w = RING_WIDTH.toPx()
            val half = w / 2
            drawRoundRect(
                color,
                topLeft = box.topLeft.copy(x = box.left - half, y = box.top - half),
                size = box.size.copy(width = box.width + w, height = box.height + w),
                cornerRadius = CornerRadius(RING_RADIUS.toPx()),
                style = Stroke(w),
            )
        }
}

private const val PORTRAIT = "portrait"
private const val LABELS_DISTANCE_ONLY = "distance_only"
private const val FOCUS_RINGS_ALWAYS = "always"
private val RING_WIDTH = 3.dp
private val RING_RADIUS = 6.dp
