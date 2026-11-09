package com.altnautica.gcs.feature.home.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCard
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.HintBar
import com.altnautica.gcs.core.designsystem.component.overlay.Overlay
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.home.HomeSurface
import com.altnautica.gcs.feature.home.R
import com.altnautica.gcs.feature.home.icon
import com.altnautica.gcs.feature.home.label
import com.altnautica.gcs.feature.home.nodeArgument

private const val SCRIM_ALPHA = 0.72f

/**
 * The app Quick menu: a full-window overlay over whatever is running (live video stays
 * visible through the scrim) with status cards for the selected vehicle, the controller
 * and the open cockpit, and launch cards for every surface. Back, a scrim tap or the
 * Quick menu button close it; choosing a card calls [onNavigate] only, and the caller
 * closes the menu and navigates.
 */
@Composable
fun QuickMenu(
    onDismiss: () -> Unit,
    onNavigate: (QuickDestination) -> Unit,
    viewModel: QuickMenuViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Overlay {
        QuickMenuContent(state, onDismiss, onNavigate)
    }
}

@Composable
private fun QuickMenuContent(
    state: QuickMenuUiState,
    onDismiss: () -> Unit,
    onNavigate: (QuickDestination) -> Unit,
) {
    val colors = AdosTheme.colors
    val dismiss by rememberUpdatedState(onDismiss)
    val dismissLabel = stringResource(R.string.home_quick_close)
    QuickMenuBackHandler(onDismiss)
    // Screen commands underneath must not act while the menu is open; the Quick menu
    // and Home commands fall through to the app root, which closes or leaves.
    ConsoleCommandHandler { command -> command != ConsoleCommand.QuickMenu && command != ConsoleCommand.Home }

    val vehicleId = state.vehicle?.node?.id
    val fly = state.surfaces.firstOrNull { it.surface == HomeSurface.Fly && it.enabled }
    val initialFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.mediaScrim.copy(alpha = SCRIM_ALPHA))
                .pointerInput(Unit) { detectTapGestures { dismiss() } }
                .semantics {
                    onClick(label = dismissLabel) {
                        dismiss()
                        true
                    }
                },
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // Focus stays inside the menu; D-pad never reaches the screen underneath.
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(AdosSpacing.S6),
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
            ) {
                VehicleStatusCard(state.vehicle, state.cockpit)
                MissionStatusCard(state.cockpit, onClick = { onNavigate(QuickDestination.Surface(HomeSurface.Plan, vehicleId)) })
                ControllerStatusCard(state.controller, state.cockpit, onClick = { onNavigate(QuickDestination.Controller) })
                AlertsStatusCard(state.cockpit)
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(AdosSpacing.S6),
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
                verticalAlignment = Alignment.Bottom,
            ) {
                ConsoleCard(
                    title = stringResource(R.string.home_surface_home),
                    icon = Icons.Outlined.Home,
                    onClick = { onNavigate(QuickDestination.Home) },
                    focusRequester = if (fly == null) initialFocus else null,
                )
                state.surfaces.forEach { card ->
                    ConsoleCard(
                        title = stringResource(card.surface.label),
                        icon = card.surface.icon,
                        onClick = { onNavigate(QuickDestination.Surface(card.surface, card.surface.nodeArgument(vehicleId))) },
                        enabled = card.enabled,
                        disabledReason = card.reason?.let { stringResource(it) },
                        focusRequester = if (card === fly) initialFocus else null,
                    )
                }
            }
            HintBar(
                listOf(
                    Hint(ConsoleButton.Confirm, stringResource(R.string.home_quick_select)),
                    Hint(ConsoleButton.Back, stringResource(R.string.home_quick_close)),
                ),
            )
        }
    }
    LaunchedEffect(initialFocus) { initialFocus.requestFocus() }
}

/** System back and the controller back button close the menu; the newest back handler wins. */
@Composable
private fun QuickMenuBackHandler(onBack: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) return
    val back by rememberUpdatedState(onBack)
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = true,
        onBackCompleted = { back() },
    )
}
