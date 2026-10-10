package com.altnautica.gcs.feature.cockpit.modes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.InWindowSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.skills.builtin.modeChangeConfirm
import com.altnautica.gcs.feature.cockpit.SheetBackHandler
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.skillbar.reasonText

/** One row of the flight-mode picker: a mode the vehicle advertises and its CS-6 confirm tier. */
data class ModeChoice(val name: String, val current: Boolean, val gesture: ConfirmGesture)

/** The open picker; [enabled] and [reasonKey] come from the `set-mode` skill state. */
data class ModePickerUi(val choices: List<ModeChoice>, val enabled: Boolean, val reasonKey: String?)

/** Every mode the vehicle advertises, in firmware table order, with the tier its change needs. */
fun modeChoices(available: List<FlightMode>, currentMode: String?): List<ModeChoice> = available.map { mode ->
    ModeChoice(
        name = mode.name,
        current = mode.name.equals(currentMode, ignoreCase = true),
        gesture = modeChangeConfirm(mode.name)?.gesture ?: ConfirmGesture.Tap,
    )
}

/** Flight-mode picker opened from the band's mode chip and the drawer's Set mode entry; place it in a full-size box. */
@Composable
fun ModePickerSheet(state: ModePickerUi, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    SheetBackHandler(onDismiss)
    InWindowSheet(onDismiss) {
        Column(Modifier.padding(horizontal = AdosSpacing.S6), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
            Text(stringResource(R.string.cockpit_mode_picker_title), style = MaterialTheme.typography.titleMedium)
            when {
                state.reasonKey != null -> Text(reasonText(state.reasonKey), style = MaterialTheme.typography.bodyMedium, color = colors.statusWarning)
                state.choices.isEmpty() -> Text(stringResource(R.string.cockpit_mode_picker_empty), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.fillMaxWidth().padding(AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            items(state.choices, key = { it.name }) { choice ->
                ModeTile(choice, state.enabled) { onPick(choice.name) }
            }
        }
    }
}

@Composable
private fun ModeTile(choice: ModeChoice, enabled: Boolean, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    val tier = when (choice.gesture) {
        ConfirmGesture.Tap -> stringResource(R.string.cockpit_mode_tier_tap)
        ConfirmGesture.Hold -> stringResource(R.string.cockpit_mode_tier_hold)
        ConfirmGesture.Slide -> stringResource(R.string.cockpit_mode_tier_slide)
        ConfirmGesture.Guarded -> stringResource(R.string.cockpit_mode_tier_hold)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AdosTheme.touchMin)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { selected = choice.current }
            .clickable(enabled = enabled && !choice.current, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(AdosRadius.Lg),
        color = if (choice.current) colors.accentPrimary.copy(alpha = 0.16f) else colors.bgRaised,
        border = BorderStroke(if (choice.current) 2.dp else 1.dp, if (choice.current) colors.accentPrimary else colors.borderDefault),
    ) {
        Column(Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S2), horizontalAlignment = Alignment.Start) {
            Text(choice.name, style = AdosTypography.numeric.medium, maxLines = 1)
            Text(
                if (choice.current) stringResource(R.string.cockpit_mode_current) else tier,
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
                maxLines = 1,
            )
        }
    }
}
