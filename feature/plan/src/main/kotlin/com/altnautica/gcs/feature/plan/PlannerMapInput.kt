package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.map.AdosMapState
import com.altnautica.gcs.core.map.DragPhase
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.skills.input.GamepadAxes
import kotlinx.coroutines.flow.StateFlow

/** Stick pan speed at 60 frames per second. */
private const val PAN_DP_PER_FRAME = 18f

/** Zoom levels per frame at full trigger travel. */
private const val ZOOM_PER_FRAME = 0.04

private const val NANOS_PER_FRAME = 1_000_000_000.0 / 60.0

/** A dropped frame or a resume never jumps the camera by more than this many frames. */
private const val MAX_FRAMES = 4f

private val CrosshairSize = 24.dp
private val CrosshairStroke = 2.dp
private val CrosshairOutline = 1.dp

private val ConfirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

/** Controller state of the map cursor; plain fields, read and written on the main thread. */
internal class MapCursor {
    /** □ is held with a selection: the next stick movement starts moving it. */
    var grabHeld = false
    var grabStarted = false
    var lastTarget: GeoPoint? = null

    /** The pad reports analog triggers, so the digital L2/R2 press does not also step the zoom. */
    var analogTriggers = false

    fun endGrab(onGrab: (GeoPoint, DragPhase) -> Unit) {
        val target = lastTarget
        if (grabStarted && target != null) onGrab(target, DragPhase.End)
        grabHeld = false
        grabStarted = false
        lastTarget = null
    }
}

/**
 * While [active] (the map holds focus and a controller was the last input) the left
 * stick pans and the analog triggers zoom every frame. With □ held on a selection the
 * selected feature follows the crosshair.
 */
@Composable
internal fun MapCursorLoop(
    active: Boolean,
    axes: StateFlow<GamepadAxes>,
    mapState: AdosMapState,
    cursor: MapCursor,
    onGrab: (GeoPoint, DragPhase) -> Unit,
) {
    val grab by rememberUpdatedState(onGrab)
    val panPx = with(LocalDensity.current) { PAN_DP_PER_FRAME.dp.toPx() }
    LaunchedEffect(active, axes, mapState) {
        if (!active) return@LaunchedEffect
        try {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val frames = ((now - last) / NANOS_PER_FRAME).toFloat().coerceIn(0f, MAX_FRAMES)
                last = now
                val a = axes.value
                if (a.leftTrigger > 0f || a.rightTrigger > 0f) cursor.analogTriggers = true
                if (a.leftX != 0f || a.leftY != 0f) {
                    // Stick up is positive Y; screen Y grows downwards.
                    mapState.panBy(a.leftX * panPx * frames, -a.leftY * panPx * frames)
                    if (cursor.grabHeld) {
                        mapState.cameraTarget?.let { target ->
                            if (!cursor.grabStarted) {
                                cursor.grabStarted = true
                                grab(target, DragPhase.Start)
                            }
                            cursor.lastTarget = target
                            grab(target, DragPhase.Move)
                        }
                    }
                }
                val zoom = a.rightTrigger - a.leftTrigger
                if (zoom != 0f) mapState.zoomBy(ZOOM_PER_FRAME * zoom * frames, animate = false)
            }
        } finally {
            // Focus left the map or the pad went quiet mid-grab: drop the feature where it is.
            cursor.endGrab(grab)
        }
    }
}

/**
 * Controller buttons on the focused map: △ item actions, hold □ to move the selection,
 * a short □ without a selection opens the plan menu, L1/R1 step through waypoints and
 * the digital L2/R2 step the zoom.
 */
@Composable
internal fun MapControllerCommands(
    enabled: Boolean,
    state: PlannerUiState,
    mapState: AdosMapState,
    cursor: MapCursor,
    actions: PlannerActions,
    onItemActions: (Long) -> Unit,
    onOpenMenu: () -> Unit,
) {
    ConsoleCommandHandler(enabled = enabled) { command ->
        when (command) {
            ConsoleCommand.Details -> {
                val atCursor = mapState.featureAtCenter()?.takeIf { it.type == MapFeatureType.Waypoint }?.id?.toLongOrNull()
                (atCursor ?: (state.selection as? Selection.Item)?.id)?.let(onItemActions)
                true
            }
            ConsoleCommand.MoreDown -> {
                if (state.selection != null) {
                    cursor.grabHeld = true
                    cursor.grabStarted = false
                }
                true
            }
            ConsoleCommand.MoreUp -> {
                cursor.endGrab(actions.onGrab)
                true
            }
            ConsoleCommand.More -> {
                if (state.selection == null) onOpenMenu()
                true
            }
            ConsoleCommand.PrevTab -> {
                stepWaypoint(state, -1, mapState, actions)
                true
            }
            ConsoleCommand.NextTab -> {
                stepWaypoint(state, 1, mapState, actions)
                true
            }
            ConsoleCommand.PrevPage -> {
                if (!cursor.analogTriggers) mapState.zoomBy(-1.0)
                true
            }
            ConsoleCommand.NextPage -> {
                if (!cursor.analogTriggers) mapState.zoomBy(1.0)
                true
            }
            else -> false
        }
    }
}

/** Selects the previous or next positional item, wrapping, and centres the map on it. */
private fun stepWaypoint(state: PlannerUiState, delta: Int, mapState: AdosMapState, actions: PlannerActions) {
    val positional = state.plan.items.filter { it.position != null }
    if (positional.isEmpty()) return
    val current = (state.selection as? Selection.Item)?.id?.let { id -> positional.indexOfFirst { it.id == id } } ?: -1
    val next = when {
        current >= 0 -> (current + delta).mod(positional.size)
        delta > 0 -> 0
        else -> positional.lastIndex
    }
    val item = positional[next]
    actions.onSelect(Selection.Item(item.id))
    item.position?.let { mapState.centerOn(it) }
}

/** ✕ (rewritten to the centre key) or Enter on the focused map acts as a tap at the crosshair. */
internal fun mapConfirmKey(event: KeyEvent, mapState: AdosMapState): Boolean {
    if (event.key !in ConfirmKeys) return false
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) mapState.tapCenter()
    return true
}

/** ctrl+z undoes, ctrl+shift+z redoes. */
internal fun undoRedoChord(event: KeyEvent, actions: PlannerActions): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.key != Key.Z) return false
    if (event.isShiftPressed) actions.onRedo() else actions.onUndo()
    return true
}

/** Centre crosshair drawn while the controller drives the map. */
@Composable
internal fun MapCrosshair(modifier: Modifier = Modifier) {
    val ink = AdosTheme.colors.fgPrimary
    val outline = AdosTheme.colors.bgCanvas
    Canvas(modifier.size(CrosshairSize)) {
        val stroke = CrosshairStroke.toPx()
        val halo = stroke + 2 * CrosshairOutline.toPx()
        val c = center
        val r = size.minDimension / 2f
        val gap = r * 0.3f
        val arms = listOf(
            Offset(c.x - r, c.y) to Offset(c.x - gap, c.y),
            Offset(c.x + gap, c.y) to Offset(c.x + r, c.y),
            Offset(c.x, c.y - r) to Offset(c.x, c.y - gap),
            Offset(c.x, c.y + gap) to Offset(c.x, c.y + r),
        )
        arms.forEach { (a, b) -> drawLine(outline, a, b, strokeWidth = halo, cap = StrokeCap.Round) }
        arms.forEach { (a, b) -> drawLine(ink, a, b, strokeWidth = stroke, cap = StrokeCap.Round) }
    }
}
