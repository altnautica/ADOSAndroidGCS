package com.altnautica.gcs.feature.settings.controller

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.settings.R
import com.altnautica.gcs.feature.settings.controller.bindings.BindMessage
import com.altnautica.gcs.feature.settings.controller.bindings.BindingsActions
import com.altnautica.gcs.feature.settings.controller.bindings.BindingsUiState
import com.altnautica.gcs.feature.settings.controller.bindings.SkillBindingsViewModel
import com.altnautica.gcs.feature.settings.controller.bindings.actions
import com.altnautica.gcs.feature.settings.controller.bindings.skillBindingsItems

private enum class ControllerTab(val title: Int) {
    Status(R.string.settings_controller_tab_status),
    Buttons(R.string.settings_controller_tab_buttons),
    Test(R.string.settings_controller_tab_test),
}

/** The controller surface: status and preferences, button maps with the Skill Bar bindings, and the live input test. */
@Composable
fun ControllerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerViewModel = hiltViewModel(),
    bindingsViewModel: SkillBindingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bindings by bindingsViewModel.state.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = ControllerTab.entries[tabIndex]
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val back by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) { viewModel.leave.collect { back() } }
    LaunchedEffect(bindingsViewModel) {
        bindingsViewModel.messages.collect { m ->
            val res = if (m == BindMessage.Reserved) R.string.settings_bindings_reserved else R.string.settings_bindings_out_of_range
            snackbar.showSnackbar(resources.getString(res))
        }
    }

    val accent = AdosTheme.colors.accentPrimary.toArgb()
    Box(modifier.fillMaxSize()) {
        ConsoleScaffold(
            title = stringResource(R.string.settings_controller_title),
            onBack = onBack,
            tabs = ControllerTab.entries.map { stringResource(it.title) },
            selectedTab = tabIndex,
            onSelectTab = { tabIndex = it },
            hints = hints(tab),
        ) { padding ->
            when (tab) {
                ControllerTab.Status -> ControllerStatusTab(
                    state = state,
                    actions = StatusActions(
                        onRumble = viewModel::setRumble,
                        onLightbar = viewModel::setLightbar,
                        onDeadZone = viewModel::setDeadZone,
                        onExpo = viewModel::setExpo,
                        onRemoveAllModes = viewModel::removeAllModes,
                    ),
                    padding = padding,
                )
                ControllerTab.Buttons -> ButtonsTab(bindings, bindingsViewModel.actions(), padding)
                ControllerTab.Test -> ControllerTestTab(
                    state = state,
                    buttons = viewModel.buttons,
                    axes = viewModel.axes,
                    onActive = viewModel::setTestActive,
                    onTestRumble = viewModel::testRumble,
                    onCycleLightbar = { viewModel.cycleLightbar(accent) },
                    onDone = onBack,
                    padding = padding,
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = AdosSpacing.S10))
    }
}

/** Read-only button maps beside the editable Skill Bar bindings; one list in portrait. */
@Composable
private fun ButtonsTab(bindings: BindingsUiState, actions: BindingsActions, padding: PaddingValues) {
    // A pending capture must not outlive the tab: it holds the controller's buttons.
    val cancel by rememberUpdatedState(actions.onCancelCapture)
    DisposableEffect(Unit) { onDispose { cancel() } }
    val contentPadding = PaddingValues(bottom = AdosSpacing.S8)
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = contentPadding) { buttonMapItems() }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = contentPadding) { skillBindingsItems(bindings, actions) }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding) {
            buttonMapItems()
            skillBindingsItems(bindings, actions)
        }
    }
}

@Composable
private fun hints(tab: ControllerTab): List<Hint> = when (tab) {
    ControllerTab.Test -> listOf(Hint(ConsoleButton.Back, stringResource(R.string.settings_controller_hint_hold_leave)))
    else -> listOf(
        Hint(ConsoleButton.Confirm, stringResource(R.string.settings_hint_select)),
        Hint(ConsoleButton.Back, stringResource(R.string.settings_nav_back)),
        Hint(ConsoleButton.PrevTab, ""),
        Hint(ConsoleButton.NextTab, stringResource(R.string.settings_controller_hint_tabs)),
    )
}
