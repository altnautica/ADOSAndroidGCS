package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.HintBar
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.component.overlay.AdosDialog
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.map.rememberAdosMapState
import com.altnautica.gcs.core.skills.input.GamepadAxes
import kotlinx.coroutines.flow.StateFlow

private const val INSPECTOR_WIDTH_FRACTION = 0.34f
private val InspectorMinWidth = 300.dp
private val InspectorMaxWidth = 420.dp
private const val PORTRAIT_MAP_WEIGHT = 0.55f
private const val PORTRAIT_INSPECTOR_WEIGHT = 0.45f

/**
 * Stateless mission planner. Landscape: tool rail, map, inspector side by side.
 * Portrait: map, a horizontal tool row, then the inspector.
 *
 * @param axes controller sticks and triggers; the map cursor reads them while it has focus.
 */
@Composable
fun PlannerScreen(
    state: PlannerUiState,
    actions: PlannerActions,
    axes: StateFlow<GamepadAxes>,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }

    var showVehiclePicker by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var itemActionsFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var inspectorOpen by rememberSaveable { mutableStateOf(true) }

    val mapState = rememberAdosMapState()
    val overflow = rememberAdosMenuState()
    val mapFocus = remember { FocusRequester() }
    val landscape = isLandscape()
    val controllerActive = LocalControllerUi.current.active

    // ○ deselects first; with nothing selected it leaves, asking about unsaved changes.
    val current by rememberUpdatedState(state)
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.selection != null || state.dirty,
        onBackCompleted = { if (current.selection != null) actions.onSelect(null) else actions.onLeave() },
    )
    ConsoleCommandHandler { command ->
        if (command == ConsoleCommand.Menu) {
            overflow.expanded = true
            true
        } else {
            false
        }
    }
    // The map is the initial focus target once a controller is in use.
    var focusedMap by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(controllerActive) {
        if (controllerActive && !focusedMap) {
            focusedMap = true
            mapFocus.requestFocus()
        }
    }

    val camera = RailCameraActions(
        onFit = {
            val all = current.plan.items.mapNotNull { it.position } + current.plan.rally.map { it.position } + current.survey
            if (all.isNotEmpty()) mapState.fitTo(all)
        },
        onZoomIn = { mapState.zoomBy(1.0) },
        onZoomOut = { mapState.zoomBy(-1.0) },
    )
    val sheets = PlannerSheetCallbacks(
        onVehicle = { showVehiclePicker = true },
        onLibrary = { showLibrary = true },
        onRename = { showRename = true },
        onItemActions = { itemActionsFor = it },
    )
    val hints = listOf(
        Hint(ConsoleButton.Confirm, stringResource(R.string.plan_hint_place)),
        Hint(ConsoleButton.Details, stringResource(R.string.plan_hint_actions)),
        Hint(ConsoleButton.More, stringResource(R.string.plan_hint_move)),
        Hint(ConsoleButton.PrevTab, ""),
        Hint(ConsoleButton.NextTab, stringResource(R.string.plan_hint_waypoints)),
        Hint(ConsoleButton.PrevPage, ""),
        Hint(ConsoleButton.NextPage, stringResource(R.string.plan_hint_zoom)),
        Hint(ConsoleButton.Menu, stringResource(R.string.plan_hint_menu)),
    )

    Column(
        modifier
            .fillMaxSize()
            .background(AdosTheme.colors.bgCanvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { undoRedoChord(it, actions) },
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val map: @Composable (Modifier) -> Unit = { m ->
                PlannerMapPane(
                    state = state,
                    actions = actions,
                    mapState = mapState,
                    axes = axes,
                    mapFocus = mapFocus,
                    overflow = overflow,
                    sheets = sheets,
                    onToggleInspector = if (landscape) ({ inspectorOpen = !inspectorOpen }) else null,
                    modifier = m,
                )
            }
            if (landscape) {
                val inspectorWidth = (maxWidth * INSPECTOR_WIDTH_FRACTION).coerceIn(InspectorMinWidth, InspectorMaxWidth)
                Row(Modifier.fillMaxSize()) {
                    PlannerToolRail(state, actions, camera, vertical = true)
                    map(Modifier.weight(1f).fillMaxHeight())
                    if (inspectorOpen) InspectorPane(state, actions, snackbar, Modifier.width(inspectorWidth).fillMaxHeight())
                }
                if (!inspectorOpen) SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            } else {
                Column(Modifier.fillMaxSize()) {
                    map(Modifier.weight(PORTRAIT_MAP_WEIGHT).fillMaxWidth())
                    PlannerToolRail(state, actions, camera, vertical = false)
                    InspectorPane(state, actions, snackbar, Modifier.weight(PORTRAIT_INSPECTOR_WEIGHT).fillMaxWidth())
                }
            }
        }
        HintBar(hints)
    }

    if (showVehiclePicker) {
        VehiclePickerSheet(state, onPick = { actions.onSelectVehicle(it); showVehiclePicker = false }, onDismiss = { showVehiclePicker = false })
    }
    if (showLibrary) {
        LibrarySheet(state, actions, onDismiss = { showLibrary = false })
    }
    if (showRename) {
        RenamePlanDialog(state.name, onRename = actions.onName, onDismiss = { showRename = false })
    }
    itemActionsFor?.let { id ->
        ItemActionsSheet(state, id, actions, onDismiss = { itemActionsFor = null })
    }
    DiscardConfirmSheet(state, actions)
    DeleteSavedConfirmSheet(state, actions)
    state.startSheet?.let { StartMissionSheetView(it, actions) }
    if (state.confirmReplaceWithDownload) {
        AdosDialog(
            title = stringResource(R.string.plan_download_replace_title),
            onDismiss = actions.onCancelDownload,
            text = { Text(stringResource(R.string.plan_download_replace_message)) },
            confirm = { TextButton(onClick = actions.onConfirmDownload) { Text(stringResource(R.string.plan_download_replace_confirm)) } },
            dismiss = { TextButton(onClick = actions.onCancelDownload) { Text(stringResource(R.string.plan_cancel)) } },
        )
    }
}

/**
 * Inspector: the selection or tool editor scrolls; vehicle transfer is pinned at the
 * bottom (scrolling itself if it would take more than half the height) with the
 * snackbar just above it.
 */
@Composable
private fun InspectorPane(state: PlannerUiState, actions: PlannerActions, snackbar: SnackbarHostState, modifier: Modifier) {
    Column(modifier.background(AdosTheme.colors.bgSurface)) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AdosSpacing.S3),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            PlannerPanel(state, actions)
        }
        SnackbarHost(snackbar, Modifier.fillMaxWidth())
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(AdosSpacing.S3),
        ) {
            TransferBar(state, actions)
        }
    }
}
