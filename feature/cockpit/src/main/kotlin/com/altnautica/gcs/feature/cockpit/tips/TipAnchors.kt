package com.altnautica.gcs.feature.cockpit.tips

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import com.altnautica.gcs.core.skills.tips.TipRect

/**
 * Where each tip anchor and never-cover zone sits on the flight screen, in root px. The
 * elements report themselves through [tipAnchor]; the tip layer reads them to decide which
 * tips can show and where. Entries are snapshot state, so a read in composition or layout
 * follows them.
 */
@Stable
class TipAnchorRegistry {
    private class Entry(val owner: Any, val rect: Rect)

    private val entries = mutableStateMapOf<String, Entry>()

    /** Told when the pilot taps an anchor; the tip layer sets it while it is on screen. */
    internal var onAnchorTap: ((String) -> Unit)? = null

    /** [id]'s bounds in root px while it is laid out and visible, else null. */
    fun rect(id: String): Rect? = entries[id]?.rect

    /** Whether [id] is laid out, visible and has a non-empty area on screen. */
    fun isVisible(id: String): Boolean = entries[id]?.rect?.let { !it.isEmpty } == true

    /** [id]'s visible bounds as a tip rect, or null when it is absent or empty. */
    fun tipRect(id: String): TipRect? = rect(id)?.takeIf { !it.isEmpty }?.let { TipRect(it.left, it.top, it.width, it.height) }

    /** [owner]'s element carrying [id] is at [bounds]. */
    fun report(id: String, owner: Any, bounds: Rect) {
        val cur = entries[id]
        if (cur == null || cur.owner !== owner || cur.rect != bounds) entries[id] = Entry(owner, bounds)
    }

    /** [owner]'s element carrying [id] left the screen or was hidden; another owner's entry stays. */
    fun remove(id: String, owner: Any) {
        if (entries[id]?.owner === owner) entries.remove(id)
    }

    internal fun anchorTapped(id: String) {
        onAnchorTap?.invoke(id)
    }
}

/** The flight screen's anchor registry; null outside it, where [tipAnchor] only tags. */
val LocalTipAnchors = staticCompositionLocalOf<TipAnchorRegistry?> { null }

/**
 * Marks this element as the tip anchor or never-cover zone [id] (`status_pill`,
 * `flight_state_chip`, `layer_button`, `stop`, `skill_picker_chip`, `bottom_left_card`,
 * `gimbal_pad`, `left_column`, `top_bar`, `alert_stack`): tags it `tip-anchor-<id>` and reports
 * its bounds in root to [LocalTipAnchors] while it is laid out and [visible]. A touch going down on it is reported
 * too, without consuming it, so the element still gets its own tap or drag.
 */
fun Modifier.tipAnchor(id: String, visible: Boolean = true): Modifier = this.composed {
    val registry = LocalTipAnchors.current
    val tagged = Modifier.testTag("tip-anchor-$id")
    if (registry == null) return@composed tagged
    val owner = remember { Any() }
    DisposableEffect(registry, id) { onDispose { registry.remove(id, owner) } }
    SideEffect { if (!visible) registry.remove(id, owner) }
    tagged
        .pointerInput(registry, id) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                registry.anchorTapped(id)
            }
        }
        .onGloballyPositioned { if (visible) registry.report(id, owner, it.boundsInRoot()) else registry.remove(id, owner) }
}
