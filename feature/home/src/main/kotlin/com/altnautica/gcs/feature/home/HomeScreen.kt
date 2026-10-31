package com.altnautica.gcs.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.HintBar
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenu
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosMenuState
import com.altnautica.gcs.core.designsystem.component.overlay.adosMenuAnchor
import com.altnautica.gcs.core.designsystem.component.overlay.rememberAdosMenuState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.flow.Flow

/** Console Home: the app root. Picks a vehicle and launches the surfaces that act on it. */
@Composable
fun HomeRoute(
    onOpenSurface: (surface: HomeSurface, nodeId: DeviceId?) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenController: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        thumbnails = viewModel::thumbnails,
        onSelect = viewModel::select,
        onSelectNext = viewModel::selectNext,
        onEnableDemo = viewModel::enableDemo,
        onRetrySavedNodes = viewModel::retrySavedNodes,
        onOpenSurface = onOpenSurface,
        onAddVehicle = onAddVehicle,
        onOpenController = onOpenController,
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    thumbnails: (DeviceId) -> Flow<ByteArray?>,
    onSelect: (DeviceId) -> Unit,
    onSelectNext: (Int) -> Unit,
    onEnableDemo: () -> Unit,
    onRetrySavedNodes: () -> Unit,
    onOpenSurface: (HomeSurface, DeviceId?) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenController: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = state.selected
    val selectedId = selected?.node?.id
    val cards = remember(selected) { surfaces(selected) }
    val open: (HomeSurface) -> Unit = { s -> onOpenSurface(s, s.nodeArgument(selectedId)) }
    val controllerUi = LocalControllerUi.current
    val vehicleMenu = rememberAdosMenuState()
    val homeMenu = rememberAdosMenuState()

    // Fly when it can launch, otherwise the first launchable card (Plan always can). Focus
    // moves there when a controller takes over and whenever the selected vehicle changes;
    // touch users get no focus ring.
    val focusSurface = (cards.firstOrNull { it.surface == HomeSurface.Fly && it.enabled } ?: cards.firstOrNull { it.enabled })?.surface
    val initialFocus = remember { FocusRequester() }
    // Rotation swaps the surface row for the grid without recreating the activity, so the
    // orientation is a key too: the focused card leaves the tree with the old layout.
    val landscape = isLandscape()
    LaunchedEffect(selectedId, controllerUi.active, focusSurface, landscape) {
        if (!controllerUi.active) return@LaunchedEffect
        // The lazy surface row composes its cards during the first layout pass.
        withFrameNanos { }
        initialFocus.requestFocus()
    }

    ConsoleCommandHandler { command ->
        when (command) {
            ConsoleCommand.PrevTab -> true.also { onSelectNext(-1) }
            ConsoleCommand.NextTab -> true.also { onSelectNext(1) }
            ConsoleCommand.Details -> (selected != null).also { if (it) open(HomeSurface.Vehicle) }
            ConsoleCommand.More -> (selected != null).also { if (it) vehicleMenu.expanded = true }
            ConsoleCommand.Menu -> true.also { homeMenu.expanded = true }
            else -> false
        }
    }

    val hero: @Composable (Modifier) -> Unit = { m ->
        HeroContent(state, cards, onAddVehicle, onEnableDemo, onRetrySavedNodes, vehicleMenu, isLandscape(), m)
    }
    val hints = homeHints()
    val topStrip: @Composable () -> Unit = {
        HomeTopStrip(trailing = { Box(Modifier.size(1.dp).adosMenuAnchor(homeMenu)) })
    }
    val vehicleRow: @Composable () -> Unit = {
        VehicleRow(state.vehicles, selectedId, onSelect, onAddVehicle)
    }

    if (isLandscape()) {
        Box(modifier.fillMaxSize()) {
            HeroBackground(selected, thumbnails)
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                topStrip()
                // With no vehicles the hero carries both add actions; the row would only repeat one.
                if (state.vehicles.isNotEmpty()) vehicleRow()
                hero(Modifier.weight(1f))
                SurfaceRow(cards, focusSurface, initialFocus, open)
                HintBar(hints)
            }
        }
    } else {
        Column(modifier.fillMaxSize().background(AdosTheme.colors.bgCanvas).windowInsetsPadding(WindowInsets.safeDrawing)) {
            topStrip()
            vehicleRow()
            Box(Modifier.fillMaxWidth().weight(0.4f)) {
                HeroBackground(selected, thumbnails)
                hero(Modifier)
            }
            SurfaceGrid(cards, focusSurface, initialFocus, open, Modifier.weight(0.6f))
            HintBar(hints)
        }
    }

    VehicleMenu(vehicleMenu, selected, onOpenSurface)
    HomeMenu(homeMenu, state.demoEnabled, onAddVehicle, onEnableDemo, onOpenController, onOpenSettings = { open(HomeSurface.Settings) })
}

/** Loading, empty or the selected vehicle, with the unreadable-keys banner over it. */
@Composable
private fun HeroContent(
    state: HomeUiState,
    cards: List<SurfaceCard>,
    onAddVehicle: () -> Unit,
    onEnableDemo: () -> Unit,
    onRetrySavedNodes: () -> Unit,
    vehicleMenu: AdosMenuState,
    landscape: Boolean,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize()) {
        HeroArea(landscape) {
            val selected = state.selected
            when {
                state.loading -> LoadingState(stringResource(R.string.home_loading))
                // No vehicle listed because the saved ones cannot be read is not "no vehicles".
                selected == null && state.savedNodesUnreadable -> Unit
                selected == null -> HeroEmpty(onAddVehicle, onEnableDemo, compact = landscape)
                else -> HeroInfo(selected, cards.firstOrNull { it.surface == HomeSurface.Fly }, Modifier.adosMenuAnchor(vehicleMenu))
            }
        }
        if (state.savedNodesUnreadable) {
            ErrorState(
                reason = stringResource(R.string.home_saved_nodes_unreadable),
                retryLabel = stringResource(R.string.home_retry),
                onRetry = onRetrySavedNodes,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(if (landscape) HERO_WIDTH else 1f)
                    .background(AdosTheme.colors.bgSurface),
            )
        }
    }
}

@Composable
private fun homeHints(): List<Hint> {
    val switch = stringResource(R.string.home_hint_switch)
    return listOf(
        Hint(ConsoleButton.Confirm, stringResource(R.string.home_hint_open)),
        Hint(ConsoleButton.Details, stringResource(R.string.home_hint_details)),
        Hint(ConsoleButton.More, stringResource(R.string.home_hint_more)),
        Hint(ConsoleButton.PrevTab, ""),
        Hint(ConsoleButton.NextTab, switch),
        Hint(ConsoleButton.Menu, stringResource(R.string.home_hint_menu)),
    )
}

/** The More menu: actions on the selected vehicle. */
@Composable
private fun VehicleMenu(state: AdosMenuState, selected: FleetCard?, onOpenSurface: (HomeSurface, DeviceId?) -> Unit) {
    val card = selected ?: return
    val id = card.node.id
    val items = buildList {
        add(AdosMenuItem(stringResource(R.string.home_menu_vehicle_details), Icons.Outlined.Info) { onOpenSurface(HomeSurface.Vehicle, id) })
        add(AdosMenuItem(stringResource(R.string.home_menu_plan_for), Icons.Outlined.Map) { onOpenSurface(HomeSurface.Plan, id) })
        // Unpairing is confirmed on the Vehicle surface, which owns the agent session.
        if (card.hasAgentLink) {
            add(AdosMenuItem(stringResource(R.string.home_menu_unpair), Icons.Outlined.LinkOff) { onOpenSurface(HomeSurface.Vehicle, id) })
        }
    }
    AdosMenu(state, items)
}

/** Options menu: app-level actions. */
@Composable
private fun HomeMenu(
    state: AdosMenuState,
    demoEnabled: Boolean,
    onAddVehicle: () -> Unit,
    onEnableDemo: () -> Unit,
    onOpenController: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val items = buildList {
        add(AdosMenuItem(stringResource(R.string.home_add_vehicle), Icons.Outlined.Add, onClick = onAddVehicle))
        if (!demoEnabled) add(AdosMenuItem(stringResource(R.string.home_demo_vehicle), Icons.Outlined.Science, onClick = onEnableDemo))
        add(AdosMenuItem(stringResource(R.string.home_quick_controller), Icons.Outlined.SportsEsports, onClick = onOpenController))
        add(AdosMenuItem(stringResource(R.string.home_surface_settings), Icons.Outlined.Settings, onClick = onOpenSettings))
    }
    AdosMenu(state, items)
}
