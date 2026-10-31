package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuState
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.map.AdosMap
import com.altnautica.gcs.core.map.AdosMapState
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.map.MapVehicle
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.feature.plan.model.FenceZone
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Share of the pane width the bottom overlays may use, leaving the map attribution clear. */
private const val BOTTOM_OVERLAY_WIDTH = 0.72f

/** Screen-owned sheets and dialogs the map chrome opens. */
internal class PlannerSheetCallbacks(
    val onVehicle: () -> Unit,
    val onLibrary: () -> Unit,
    val onRename: () -> Unit,
    val onItemActions: (Long) -> Unit,
)

/** What a long press landed on; the menu opens at [point]. */
private sealed interface MapLongPress {
    val point: GeoPoint

    data class Empty(override val point: GeoPoint) : MapLongPress

    data class Rally(val id: Long, override val point: GeoPoint) : MapLongPress

    data class Fence(val zoneId: Long, override val point: GeoPoint) : MapLongPress
}

/**
 * The planner map with its glass chrome: title chip, map actions, stats strip and
 * altitude profile. The map itself is a focus target: while it holds focus and a
 * controller is in use, a crosshair marks the cursor, the left stick pans, the triggers
 * zoom and ✕ acts as a tap at the crosshair.
 */
@Composable
internal fun PlannerMapPane(
    state: PlannerUiState,
    actions: PlannerActions,
    mapState: AdosMapState,
    axes: StateFlow<GamepadAxes>,
    mapFocus: FocusRequester,
    overflow: AdosMenuState,
    sheets: PlannerSheetCallbacks,
    onToggleInspector: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val link = state.link as? VehicleLink.Connected
    val vehicle = link?.position?.let { MapVehicle(it, link.headingDeg, stale = !link.linkFresh) }
    val overlays = remember(state.displayPlan, state.selection, state.survey, state.surveyPreview, state.fenceDraft, state.link) { state.toOverlays() }

    // Frame the plan once it has content, else the vehicle.
    val framePoints = remember(state.savedId, state.name, state.plan.items.isEmpty()) {
        state.plan.items.mapNotNull { it.position } + state.plan.rally.map { it.position }
    }
    LaunchedEffect(framePoints) { if (framePoints.isNotEmpty()) mapState.fitTo(framePoints) }
    val hasVehicleFix = vehicle != null
    LaunchedEffect(hasVehicleFix) {
        if (hasVehicleFix && framePoints.isEmpty()) vehicle?.let { mapState.centerOn(it.position, 16.0) }
    }

    val controllerActive = LocalControllerUi.current.active
    var mapFocused by remember { mutableStateOf(false) }
    val cursor = remember { MapCursor() }
    var longPress by remember { mutableStateOf<MapLongPress?>(null) }
    val current by rememberUpdatedState(state)
    val description = stringResource(R.string.plan_map_description)

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .consoleFocus(RectangleShape)
                .focusRequester(mapFocus)
                .onFocusChanged { mapFocused = it.isFocused }
                .onPreviewKeyEvent { mapConfirmKey(it, mapState) }
                .focusable(),
        ) {
            AdosMap(
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
                state = mapState,
                basemap = state.basemap,
                vehicle = vehicle,
                home = state.homeMarker(),
                overlays = overlays,
                onMapClick = actions.onMapTap,
                onMapLongClick = { point -> longPress = MapLongPress.Empty(point) },
                onFeatureClick = actions.onFeatureClick,
                onFeatureLongClick = { ref -> longPress = featureLongPress(ref, current, actions, sheets) },
                onFeatureDrag = actions.onFeatureDrag,
            )
            if (mapFocused && controllerActive) MapCrosshair(Modifier.align(Alignment.Center))
        }
        // One row so the title chip yields width to the actions chip on narrow maps.
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(AdosSpacing.S2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            PlanTitleChip(
                state = state,
                onBack = actions.onLeave,
                onVehicle = sheets.onVehicle,
                onRename = sheets.onRename,
                modifier = Modifier.weight(1f, fill = false).padding(end = AdosSpacing.S2),
            )
            MapActionsChip(
                state = state,
                actions = actions,
                overflow = overflow,
                onLibrary = sheets.onLibrary,
                onToggleInspector = onToggleInspector,
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth(BOTTOM_OVERLAY_WIDTH).padding(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            PlanStatsStrip(state)
            AltitudeProfileOverlay(state)
        }
        LongPressMenu(longPress, state, actions, mapState, onClosed = { longPress = null })
    }

    MapCursorLoop(active = mapFocused && controllerActive, axes = axes, mapState = mapState, cursor = cursor, onGrab = actions.onGrab)
    MapControllerCommands(
        enabled = mapFocused,
        state = state,
        mapState = mapState,
        cursor = cursor,
        actions = actions,
        onItemActions = sheets.onItemActions,
        onOpenMenu = { overflow.expanded = true },
    )
    LaunchedEffect(mapFocused) { actions.onStickNavigationSuppressed(mapFocused) }
    DisposableEffect(actions) { onDispose { actions.onStickNavigationSuppressed(false) } }
}

/**
 * Long press on a feature: a waypoint opens its actions sheet; a rally point or fence
 * handle is selected (its editor opens) and gets a menu with Delete.
 */
private fun featureLongPress(
    ref: MapFeatureRef,
    state: PlannerUiState,
    actions: PlannerActions,
    sheets: PlannerSheetCallbacks,
): MapLongPress? = when (ref.type) {
    MapFeatureType.Waypoint -> {
        ref.id.toLongOrNull()?.let(sheets.onItemActions)
        null
    }
    MapFeatureType.Rally -> ref.id.toLongOrNull()?.let { id ->
        state.plan.rally.firstOrNull { it.id == id }?.let { rally ->
            actions.onSelect(Selection.Rally(id))
            MapLongPress.Rally(id, rally.position)
        }
    }
    MapFeatureType.Handle -> {
        val (zoneId, point) = when (val target = HandleTarget.parse(ref.id)) {
            is HandleTarget.FenceVertex -> target.zoneId to
                (state.plan.fence.firstOrNull { it.id == target.zoneId } as? FenceZone.Polygon)?.points?.getOrNull(target.index)
            is HandleTarget.FenceCenter -> target.zoneId to
                (state.plan.fence.firstOrNull { it.id == target.zoneId } as? FenceZone.Circle)?.center
            else -> null to null
        }
        if (zoneId != null && point != null) {
            actions.onSelect(Selection.Fence(zoneId))
            MapLongPress.Fence(zoneId, point)
        } else {
            null
        }
    }
}

/** Menu at the long-press point; [onClosed] runs once it closes. */
@Composable
private fun LongPressMenu(
    press: MapLongPress?,
    state: PlannerUiState,
    actions: PlannerActions,
    mapState: AdosMapState,
    onClosed: () -> Unit,
) {
    val menu = rememberAdosMenuState()
    LaunchedEffect(press) { menu.expanded = press != null }
    LaunchedEffect(menu.expanded) { if (!menu.expanded) onClosed() }
    press ?: return
    val at = remember(press) { mapState.screenLocation(press.point) } ?: return
    Box(
        Modifier
            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
            .size(1.dp)
            .adosMenuAnchor(menu),
    )
    val items = when (press) {
        is MapLongPress.Empty -> buildList {
            add(AdosMenuItem(stringResource(R.string.plan_longpress_add_waypoint)) { actions.onAddWaypointAt(press.point) })
            add(AdosMenuItem(stringResource(R.string.plan_longpress_add_rally)) { actions.onAddRallyAt(press.point) })
            if (state.tool == EditTool.Survey) {
                val label = if (state.survey.isEmpty()) R.string.plan_longpress_survey_start else R.string.plan_longpress_survey_corner
                add(AdosMenuItem(stringResource(label)) { actions.onAddSurveyPointAt(press.point) })
            }
        }
        is MapLongPress.Rally -> listOf(AdosMenuItem(stringResource(R.string.plan_rally_delete)) { actions.onDeleteRally(press.id) })
        is MapLongPress.Fence -> listOf(AdosMenuItem(stringResource(R.string.plan_fence_delete)) { actions.onDeleteFence(press.zoneId) })
    }
    AdosMenu(menu, items)
}
