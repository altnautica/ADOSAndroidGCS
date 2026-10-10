package com.altnautica.gcs.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import com.altnautica.gcs.core.link.direct.DirectEndpoint

@Composable
fun SettingsRoute(
    onOpenSkillBindings: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenPhoneRadio: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel, onOpenSkillBindings, onOpenOfflineMaps, onOpenLicences, onOpenCloud, onOpenPhoneRadio, versionName, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    vm: SettingsViewModel,
    onOpenSkillBindings: () -> Unit,
    onOpenOfflineMaps: () -> Unit,
    onOpenLicences: () -> Unit,
    onOpenCloud: () -> Unit,
    onOpenPhoneRadio: () -> Unit,
    versionName: String,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (!state.loaded) {
            LoadingState(stringResource(R.string.settings_loading), Modifier.padding(padding))
            return@Scaffold
        }
        val context = LocalContext.current
        val language = remember { AppLocales.current(context) }
        val themeLabels = AdosThemeId.entries.associateWith { themeLabel(it) }
        val systemLanguage = stringResource(R.string.settings_language_system)
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = AdosSpacing.S8)) {
            item { SectionHeader(stringResource(R.string.settings_appearance)) }
            item {
                SelectSettingRow(
                    title = stringResource(R.string.settings_theme),
                    options = AdosThemeId.entries,
                    selected = state.appearance.theme,
                    optionLabel = { themeLabels.getValue(it) },
                    onSelect = vm::setTheme,
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_glove),
                    checked = state.appearance.gloveMode,
                    onCheckedChange = vm::setGloveMode,
                    summary = stringResource(R.string.settings_glove_summary),
                )
            }
            item {
                SelectSettingRow(
                    title = stringResource(R.string.settings_language),
                    options = AppLocales.SUPPORTED,
                    selected = language.takeIf { it in AppLocales.SUPPORTED } ?: "",
                    optionLabel = { if (it.isEmpty()) systemLanguage else AppLocales.displayName(it) },
                    onSelect = { tag -> context.findActivity()?.let { AppLocales.apply(it, tag) } },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_cockpit)) }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_max_brightness),
                    checked = state.cockpit.maxBrightnessDefault,
                    onCheckedChange = vm::setMaxBrightnessDefault,
                    summary = stringResource(R.string.settings_max_brightness_summary),
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_audio_callouts),
                    checked = state.cockpit.audioCallouts,
                    onCheckedChange = vm::setAudioCallouts,
                    summary = stringResource(R.string.settings_audio_callouts_summary),
                )
            }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_mode_callouts),
                    checked = state.cockpit.modeCallouts,
                    onCheckedChange = vm::setModeCallouts,
                    enabled = state.cockpit.audioCallouts,
                )
            }
            item {
                NumberSettingRow(
                    title = stringResource(R.string.settings_battery_low),
                    value = state.cockpit.batteryBands.lowPct,
                    onValueChange = vm::setBatteryLow,
                    range = BATTERY_LOW_MIN..BATTERY_LOW_MAX,
                    step = BATTERY_STEP,
                    decrementDescription = stringResource(R.string.settings_decrease),
                    incrementDescription = stringResource(R.string.settings_increase),
                    summary = stringResource(R.string.settings_battery_low_summary),
                    format = { "$it %" },
                )
            }
            item {
                NumberSettingRow(
                    title = stringResource(R.string.settings_battery_critical),
                    value = state.cockpit.batteryBands.criticalPct,
                    onValueChange = vm::setBatteryCritical,
                    range = 1..(state.cockpit.batteryBands.lowPct - 1).coerceAtLeast(1),
                    step = BATTERY_STEP,
                    decrementDescription = stringResource(R.string.settings_decrease),
                    incrementDescription = stringResource(R.string.settings_increase),
                    summary = stringResource(R.string.settings_battery_critical_summary),
                    format = { "$it %" },
                )
            }
            item { LinkRow(stringResource(R.string.settings_bindings), stringResource(R.string.settings_bindings_summary), onOpenSkillBindings) }

            item { SectionHeader(stringResource(R.string.settings_maps)) }
            item { LinkRow(stringResource(R.string.settings_offline_maps), stringResource(R.string.settings_offline_maps_summary), onOpenOfflineMaps) }

            item { SectionHeader(stringResource(R.string.settings_connections)) }
            item { LinkRow(stringResource(R.string.settings_cloud), stringResource(R.string.settings_cloud_summary), onOpenCloud) }
            item { LinkRow(stringResource(R.string.settings_phone_radio), stringResource(R.string.settings_phone_radio_summary), onOpenPhoneRadio) }
            item {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_demo),
                    checked = state.demoEnabled,
                    onCheckedChange = vm::setDemoEnabled,
                    summary = stringResource(R.string.settings_demo_summary),
                )
            }
            if (state.endpoints.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_endpoints_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AdosTheme.colors.fgSecondary,
                        modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
                    )
                }
            }
            items(state.endpoints, key = { it.nodeId.value }) { e -> EndpointRow(e) { vm.removeEndpoint(e) } }

            item { SectionHeader(stringResource(R.string.settings_about)) }
            item { LinkRow(stringResource(R.string.settings_version), versionName, onClick = null) }
            item { LinkRow(stringResource(R.string.settings_licences), stringResource(R.string.settings_licences_summary), onOpenLicences) }
        }
    }
}

private const val BATTERY_LOW_MIN = 10
private const val BATTERY_LOW_MAX = 60
private const val BATTERY_STEP = 5

@Composable
private fun themeLabel(theme: AdosThemeId): String = stringResource(
    when (theme) {
        AdosThemeId.BrandDark -> R.string.settings_theme_dark
        AdosThemeId.BrandLight -> R.string.settings_theme_light
        AdosThemeId.Nvg -> R.string.settings_theme_nvg
    },
)

@Composable
private fun LinkRow(title: String, summary: String?, onClick: (() -> Unit)?) {
    val colors = AdosTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AdosTheme.touchMin)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary) }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.fgSecondary)
    }
}

@Composable
private fun EndpointRow(endpoint: DirectEndpoint, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = AdosSpacing.S4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(endpoint.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_endpoint_remove, endpoint.label))
        }
    }
}
