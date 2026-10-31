package com.altnautica.gcs.feature.configure.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.feature.configure.R

/**
 * The Setup surface for [nodeId]: the section list beside the open section in landscape;
 * in portrait the list, then the section with back to the list. L1/R1 step through the
 * sections. MSP flight controllers get an explanation instead.
 */
@Composable
fun SetupSurfaceRoute(nodeId: DeviceId, onBack: () -> Unit) {
    val vm = hiltViewModel<SetupSurfaceViewModel, SetupSurfaceViewModel.Factory>(
        key = "setup-surface-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val ready = state as? SurfaceNodeState.Ready
    val configurable = ready != null && ready.fcVariant != FcVariant.Msp
    var section by rememberSaveable { mutableIntStateOf(0) }
    var showDetail by rememberSaveable { mutableStateOf(false) }
    val sections = SetupSection.entries

    ConsoleCommandHandler(enabled = configurable) { command ->
        when (command) {
            ConsoleCommand.PrevTab -> {
                section = (section - 1).mod(sections.size)
                true
            }
            ConsoleCommand.NextTab -> {
                section = (section + 1).mod(sections.size)
                true
            }
            else -> false
        }
    }
    // Portrait detail: back returns to the section list before it leaves the surface.
    if (configurable && showDetail && !isLandscape()) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = { showDetail = false },
        )
    }

    ConsoleScaffold(
        title = stringResource(R.string.cfg_setup_surface_title, ready?.name ?: nodeId.value),
        onBack = onBack,
        hints = listOf(
            Hint(ConsoleButton.Confirm, stringResource(R.string.cfg_hint_select)),
            Hint(ConsoleButton.Back, stringResource(R.string.cfg_back)),
            Hint(ConsoleButton.NextTab, stringResource(R.string.cfg_hint_section)),
        ),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ready == null -> LoadingState(stringResource(R.string.cfg_surface_loading), Modifier.align(Alignment.Center))
                ready.fcVariant == FcVariant.Msp -> EmptyState(
                    icon = Icons.Outlined.DesktopWindows,
                    title = stringResource(R.string.cfg_msp_title),
                    message = stringResource(R.string.cfg_msp_message),
                )
                else -> SetupPanes(
                    nodeId = nodeId,
                    fcVariant = ready.fcVariant,
                    section = sections[section],
                    showDetailInPortrait = showDetail,
                    onSelect = {
                        section = it.ordinal
                        showDetail = true
                    },
                )
            }
        }
    }
}
