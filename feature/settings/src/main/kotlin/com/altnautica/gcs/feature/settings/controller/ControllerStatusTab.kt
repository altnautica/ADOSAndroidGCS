package com.altnautica.gcs.feature.settings.controller

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.console.isLandscape
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.input.controller.ControllerFamily
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.isDualShock4
import com.altnautica.gcs.feature.settings.R

private const val PCT_STEP = 5
private const val SEPARATOR = " · "

/** Preference changes of the Status tab. */
internal class StatusActions(
    val onRumble: (Boolean) -> Unit,
    val onLightbar: (Boolean) -> Unit,
    val onDeadZone: (Int) -> Unit,
    val onExpo: (Int) -> Unit,
    val onRemoveAllModes: (DeviceId) -> Unit,
)

/** The controller (or the pairing guide) beside its preferences; stacked in portrait. */
@Composable
internal fun ControllerStatusTab(state: ControllerUiState, actions: StatusActions, padding: PaddingValues) {
    val device: LazyListScope.() -> Unit = {
        item(key = "device") {
            val controller = state.controller
            if (controller != null) ControllerCard(controller) else PairingGuide()
        }
    }
    val prefs: LazyListScope.() -> Unit = { preferenceItems(state, actions) }
    val contentPadding = PaddingValues(bottom = AdosSpacing.S8)
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = contentPadding, content = device)
            LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = contentPadding, content = prefs)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding) {
            device()
            prefs()
        }
    }
}

private fun LazyListScope.preferenceItems(state: ControllerUiState, actions: StatusActions) {
    val s = state.settings
    item(key = "feedback") { SectionHeader(stringResource(R.string.settings_controller_feedback)) }
    item(key = "rumble") {
        SwitchSettingRow(stringResource(R.string.settings_controller_rumble), s.rumble, actions.onRumble, summary = stringResource(R.string.settings_controller_rumble_summary))
    }
    item(key = "lightbar") {
        SwitchSettingRow(stringResource(R.string.settings_controller_lightbar), s.lightbar, actions.onLightbar, summary = stringResource(R.string.settings_controller_lightbar_summary))
    }
    item(key = "sticks") { SectionHeader(stringResource(R.string.settings_controller_sticks)) }
    item(key = "deadzone") {
        PercentRow(R.string.settings_controller_dead_zone, R.string.settings_controller_dead_zone_summary, s.deadZonePct, ControllerSettingsRepository.MAX_DEAD_ZONE_PCT, actions.onDeadZone)
    }
    item(key = "expo") {
        PercentRow(R.string.settings_controller_expo, R.string.settings_controller_expo_summary, s.expoPct, ControllerSettingsRepository.MAX_EXPO_PCT, actions.onExpo)
    }
    item(key = "allmodes") { SectionHeader(stringResource(R.string.settings_controller_all_modes)) }
    if (state.allModesVehicles.isEmpty()) {
        item(key = "allmodes-none") {
            Text(
                stringResource(R.string.settings_controller_all_modes_none),
                style = MaterialTheme.typography.bodyMedium,
                color = AdosTheme.colors.fgSecondary,
                modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
            )
        }
    }
    items(state.allModesVehicles, key = { "allmodes-${it.id.value}" }) { vehicle ->
        AdosListItem(
            headline = vehicle.name ?: stringResource(R.string.settings_controller_vehicle_unlisted),
            supporting = vehicle.id.value,
            onClick = null,
            trailing = {
                AdosButton(
                    label = stringResource(R.string.settings_controller_remove),
                    onClick = { actions.onRemoveAllModes(vehicle.id) },
                    variant = AdosButtonVariant.Secondary,
                )
            },
        )
    }
}

@Composable
private fun PercentRow(title: Int, summary: Int, value: Int, max: Int, onChange: (Int) -> Unit) {
    val resources = LocalResources.current
    NumberSettingRow(
        title = stringResource(title),
        value = value,
        onValueChange = onChange,
        range = 0..max,
        decrementDescription = stringResource(R.string.settings_controller_decrease, stringResource(title)),
        incrementDescription = stringResource(R.string.settings_controller_increase, stringResource(title)),
        step = PCT_STEP,
        summary = stringResource(summary),
        format = { resources.getString(R.string.settings_controller_percent, it) },
    )
}

@Composable
private fun ControllerCard(controller: ControllerInfo) {
    val colors = AdosTheme.colors
    val model = when {
        controller.isDualShock4() -> stringResource(R.string.settings_controller_model_ds4)
        controller.family == ControllerFamily.PlayStation -> stringResource(R.string.settings_controller_family_playstation)
        else -> stringResource(R.string.settings_controller_family_generic)
    }
    val pct = controller.batteryPct
    val battery = if (pct != null) stringResource(R.string.settings_controller_battery, pct) else stringResource(R.string.settings_controller_battery_unknown)
    val charging = if (controller.charging) SEPARATOR + stringResource(R.string.settings_controller_charging) else ""
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = colors.accentPrimary, modifier = Modifier.size(AdosSize.IconLg))
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(controller.name.ifBlank { model }, style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
                Text(model, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
                Text(battery + charging, style = AdosTypography.numeric.small, color = colors.fgSecondary)
            }
        }
    }
}

@Composable
private fun PairingGuide() {
    val colors = AdosTheme.colors
    val context = LocalContext.current
    val steps = listOf(
        R.string.settings_controller_pair_step1,
        R.string.settings_controller_pair_step2,
        R.string.settings_controller_pair_step3,
        R.string.settings_controller_pair_step4,
    )
    Card {
        Text(stringResource(R.string.settings_controller_pair_title), style = MaterialTheme.typography.titleMedium, color = colors.fgPrimary)
        Text(stringResource(R.string.settings_controller_pair_intro), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        steps.forEachIndexed { i, step ->
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Text(stringResource(R.string.settings_controller_pair_number, i + 1), style = MaterialTheme.typography.bodyMedium, color = colors.accentPrimary)
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
            }
        }
        AdosButton(
            label = stringResource(R.string.settings_controller_open_bluetooth),
            icon = Icons.Outlined.Bluetooth,
            onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(AdosSpacing.S4)
            .clip(RoundedCornerShape(AdosRadius.Xl))
            .background(AdosTheme.colors.bgRaised)
            .padding(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) { content() }
}
