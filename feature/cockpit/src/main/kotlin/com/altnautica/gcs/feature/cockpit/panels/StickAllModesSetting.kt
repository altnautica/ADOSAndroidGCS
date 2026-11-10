package com.altnautica.gcs.feature.cockpit.panels

import com.altnautica.gcs.feature.cockpit.flow.specText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet

/** Hold time to allow stick control in every mode (the standard hold tier). */
private const val ALL_MODES_HOLD_MS = 800

/**
 * "Stick control in all modes" for the flown vehicle. Turning it on asks for a held
 * confirmation behind a danger warning, because a self-centring throttle stick is not a
 * hover outside altitude-holding modes; turning it off applies at once.
 */
@Composable
internal fun StickAllModesSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    SwitchSettingRow(
        title = specText("cockpit.qs.stick_all_modes"),
        checked = enabled,
        onCheckedChange = { on -> if (on) confirming = true else onChange(false) },
        summary = specText("cockpit.qs.stick_all_modes.summary"),
    )
    if (confirming) {
        AdosConfirmSheet(
            title = specText("cockpit.qs.all_modes.title"),
            message = specText("cockpit.qs.all_modes.warning"),
            onDismiss = { confirming = false },
            danger = true,
        ) {
            HoldToConfirmButton(
                label = specText("cockpit.qs.all_modes.confirm"),
                holdMs = ALL_MODES_HOLD_MS,
                onConfirmed = {
                    confirming = false
                    onChange(true)
                },
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
