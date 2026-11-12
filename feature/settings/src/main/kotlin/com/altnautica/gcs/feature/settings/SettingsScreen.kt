package com.altnautica.gcs.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ConsoleScaffold
import com.altnautica.gcs.core.designsystem.component.console.ConsoleTwoPane
import com.altnautica.gcs.core.designsystem.component.console.Hint
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** The settings categories, in list order. */
internal enum class SettingsCategory(val titleRes: Int, val icon: ImageVector) {
    Appearance(R.string.settings_appearance, Icons.Outlined.Palette),
    Cockpit(R.string.settings_cockpit, Icons.Outlined.Flight),
    Controller(R.string.settings_controller, Icons.Outlined.SportsEsports),
    Maps(R.string.settings_maps, Icons.Outlined.Map),
    Connections(R.string.settings_connections, Icons.Outlined.Cable),
    About(R.string.settings_about, Icons.Outlined.Info),
}

/** Navigation out of the settings surface. */
internal class SettingsLinks(
    val onOpenController: () -> Unit,
    val onOpenOfflineMaps: () -> Unit,
    val onOpenLicences: () -> Unit,
    val onOpenCloud: () -> Unit,
    val onOpenPhoneRadio: () -> Unit,
    val versionName: String,
)

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenController: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenPhoneRadio: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val links = SettingsLinks(onOpenController, onOpenOfflineMaps, onOpenLicences, onOpenCloud, onOpenPhoneRadio, versionName)
    SettingsScreen(state, viewModel, links, onBack, modifier)
}

@Composable
private fun SettingsScreen(state: SettingsUiState, vm: SettingsViewModel, links: SettingsLinks, onBack: () -> Unit, modifier: Modifier) {
    val landscape = isLandscape()
    // Portrait shows the list until a category is opened; landscape always shows a detail.
    var opened by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    val selected = opened ?: SettingsCategory.Appearance
    val inPortraitDetail = !landscape && opened != null

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = inPortraitDetail,
        onBackCompleted = { opened = null },
    )

    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            snackbar.showSnackbar(resources.getString(settingsMessageText(m)))
        }
    }

    val title = if (inPortraitDetail) stringResource(selected.titleRes) else stringResource(R.string.settings_title)
    Box(modifier.fillMaxSize()) {
        ConsoleScaffold(
            title = title,
            onBack = if (inPortraitDetail) ({ opened = null }) else onBack,
            modifier = Modifier.fillMaxSize(),
            hints = listOf(
                Hint(ConsoleButton.Confirm, stringResource(R.string.settings_hint_select)),
                Hint(ConsoleButton.Back, stringResource(R.string.settings_nav_back)),
            ),
        ) { padding ->
            if (!state.loaded) {
                LoadingState(stringResource(R.string.settings_loading), Modifier.padding(padding))
                return@ConsoleScaffold
            }
            ConsoleTwoPane(
                list = { CategoryList(selected.takeIf { landscape }, onOpen = { opened = it }) },
                detail = {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
                        categoryContent(selected, state, vm, links)
                    }
                },
                showDetailInPortrait = opened != null,
                modifier = Modifier.padding(padding),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = AdosSpacing.S10))
    }
}

/** The confirmation text for [message]; the spec has no key for these yet. */
internal fun settingsMessageText(message: SettingsMessage): Int = when (message) {
    SettingsMessage.TourReplayed -> R.string.settings_tour_replay_done
    SettingsMessage.TipsReset -> R.string.settings_tips_reset_done
}

@Composable
private fun CategoryList(selected: SettingsCategory?, onOpen: (SettingsCategory) -> Unit) {
    val colors = AdosTheme.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = AdosSpacing.S2)) {
        items(SettingsCategory.entries) { category ->
            AdosListItem(
                headline = stringResource(category.titleRes),
                onClick = { onOpen(category) },
                selected = category == selected,
                leading = { Icon(category.icon, contentDescription = null, tint = colors.fgSecondary) },
                trailing = if (selected == null) {
                    { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.fgSecondary) }
                } else {
                    null
                },
            )
        }
    }
}
