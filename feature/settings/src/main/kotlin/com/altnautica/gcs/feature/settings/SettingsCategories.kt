package com.altnautica.gcs.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import com.altnautica.gcs.core.link.direct.DirectEndpoint

/** Rows of one settings category. */
internal fun LazyListScope.categoryContent(
    category: SettingsCategory,
    state: SettingsUiState,
    vm: SettingsViewModel,
    links: SettingsLinks,
) {
    when (category) {
        SettingsCategory.Appearance -> appearance(state, vm)
        SettingsCategory.Cockpit -> cockpit(state, vm)
        SettingsCategory.Controller -> item {
            LinkRow(stringResource(R.string.settings_bindings), stringResource(R.string.settings_bindings_summary), links.onOpenController)
        }
        SettingsCategory.Maps -> item {
            LinkRow(stringResource(R.string.settings_offline_maps), stringResource(R.string.settings_offline_maps_summary), links.onOpenOfflineMaps)
        }
        SettingsCategory.Connections -> connections(state, vm, links)
        SettingsCategory.About -> {
            item { LinkRow(stringResource(R.string.settings_version), links.versionName, onClick = null) }
            item { LinkRow(stringResource(R.string.settings_licences), stringResource(R.string.settings_licences_summary), links.onOpenLicences) }
        }
    }
}

private fun LazyListScope.appearance(state: SettingsUiState, vm: SettingsViewModel) {
    item {
        val themeLabels = AdosThemeId.entries.associateWith { themeLabel(it) }
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
    item { LanguageRow() }
}

@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val language by remember(context) { AppLocales.observe(context) }.collectAsStateWithLifecycle()
    val systemLanguage = stringResource(R.string.settings_language_system)
    SelectSettingRow(
        title = stringResource(R.string.settings_language),
        options = AppLocales.SUPPORTED,
        selected = language.takeIf { it in AppLocales.SUPPORTED } ?: "",
        optionLabel = { if (it.isEmpty()) systemLanguage else AppLocales.displayName(it) },
        onSelect = { tag -> context.findActivity()?.let { AppLocales.apply(it, tag) } },
    )
}

private fun LazyListScope.cockpit(state: SettingsUiState, vm: SettingsViewModel) {
    val c = state.cockpit
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_max_brightness),
            checked = c.maxBrightnessDefault,
            onCheckedChange = vm::setMaxBrightnessDefault,
            summary = stringResource(R.string.settings_max_brightness_summary),
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_audio_callouts),
            checked = c.audioCallouts,
            onCheckedChange = vm::setAudioCallouts,
            summary = stringResource(R.string.settings_audio_callouts_summary),
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_mode_callouts),
            checked = c.modeCallouts,
            onCheckedChange = vm::setModeCallouts,
            enabled = c.audioCallouts,
        )
    }
    item { SectionHeader(stringResource(R.string.settings_sound)) }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_voice_prompts),
            checked = c.voicePrompts,
            onCheckedChange = vm::setVoicePrompts,
            summary = stringResource(R.string.settings_voice_prompts_summary),
        )
    }
    item {
        val labels = mapOf(
            AltitudeReference.Rel to stringResource(R.string.settings_alt_ref_rel),
            AltitudeReference.Msl to stringResource(R.string.settings_alt_ref_msl),
        )
        SelectSettingRow(
            title = stringResource(R.string.settings_alt_ref),
            options = AltitudeReference.entries,
            selected = c.altitudeReference,
            optionLabel = { labels.getValue(it) },
            onSelect = vm::setAltitudeReference,
        )
    }
    item {
        val labels = mapOf(
            CockpitDensity.Min to stringResource(R.string.settings_density_min),
            CockpitDensity.Std to stringResource(R.string.settings_density_std),
            CockpitDensity.Full to stringResource(R.string.settings_density_full),
        )
        SelectSettingRow(
            title = stringResource(R.string.settings_density),
            options = CockpitDensity.entries,
            selected = c.density,
            optionLabel = { labels.getValue(it) },
            onSelect = vm::setDensity,
        )
    }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_telemetry_pane),
            checked = c.telemetryPane,
            onCheckedChange = vm::setTelemetryPane,
            summary = stringResource(R.string.settings_telemetry_pane_summary),
        )
    }
    item {
        NumberSettingRow(
            title = stringResource(R.string.settings_battery_low),
            value = c.batteryBands.lowPct,
            onValueChange = vm::setBatteryLow,
            range = CockpitSettingsRepository.BATTERY_LOW_MIN..CockpitSettingsRepository.BATTERY_LOW_MAX,
            step = CockpitSettingsRepository.BATTERY_STEP,
            decrementDescription = stringResource(R.string.settings_decrease),
            incrementDescription = stringResource(R.string.settings_increase),
            summary = stringResource(R.string.settings_battery_low_summary),
            format = { "$it %" },
        )
    }
    item {
        NumberSettingRow(
            title = stringResource(R.string.settings_battery_critical),
            value = c.batteryBands.criticalPct,
            onValueChange = vm::setBatteryCritical,
            range = CockpitSettingsRepository.criticalRange(c.batteryBands.lowPct),
            step = CockpitSettingsRepository.BATTERY_STEP,
            decrementDescription = stringResource(R.string.settings_decrease),
            incrementDescription = stringResource(R.string.settings_increase),
            summary = stringResource(R.string.settings_battery_critical_summary),
            format = { "$it %" },
        )
    }
}

private fun LazyListScope.connections(state: SettingsUiState, vm: SettingsViewModel, links: SettingsLinks) {
    item { LinkRow(stringResource(R.string.settings_cloud), stringResource(R.string.settings_cloud_summary), links.onOpenCloud) }
    item { LinkRow(stringResource(R.string.settings_phone_radio), stringResource(R.string.settings_phone_radio_summary), links.onOpenPhoneRadio) }
    item {
        SwitchSettingRow(
            title = stringResource(R.string.settings_demo),
            checked = state.demoEnabled,
            onCheckedChange = vm::setDemoEnabled,
            summary = stringResource(R.string.settings_demo_summary),
        )
    }
    if (state.demoEnabled) {
        item {
            SwitchSettingRow(
                title = stringResource(R.string.settings_demo_stale_skill),
                checked = state.demoStaleSkill,
                onCheckedChange = vm::setDemoStaleSkill,
                summary = stringResource(R.string.settings_demo_stale_skill_summary),
            )
        }
    }
    item { SectionHeader(stringResource(R.string.settings_endpoints)) }
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
}

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
    AdosListItem(
        headline = title,
        onClick = onClick,
        supporting = summary,
        trailing = onClick?.let {
            { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = AdosTheme.colors.fgSecondary) }
        },
    )
}

@Composable
private fun EndpointRow(endpoint: DirectEndpoint, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = AdosSpacing.S4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(endpoint.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { confirming = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.settings_endpoint_remove, endpoint.label))
        }
    }
    if (confirming) {
        AdosConfirmSheet(
            title = stringResource(R.string.settings_endpoint_remove_title),
            message = stringResource(R.string.settings_endpoint_remove_message, endpoint.label),
            onDismiss = { confirming = false },
            danger = true,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(
                    stringResource(R.string.settings_cancel),
                    onClick = { confirming = false },
                    variant = AdosButtonVariant.Secondary,
                )
                AdosButton(
                    stringResource(R.string.settings_remove),
                    onClick = {
                        confirming = false
                        onRemove()
                    },
                    variant = AdosButtonVariant.Danger,
                )
            }
        }
    }
}
