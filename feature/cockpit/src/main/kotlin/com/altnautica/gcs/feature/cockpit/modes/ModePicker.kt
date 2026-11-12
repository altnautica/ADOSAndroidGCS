package com.altnautica.gcs.feature.cockpit.modes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.skills.builtin.modeChangeConfirm
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.skillbar.reasonText

/**
 * One row of the flight-mode picker: a mode the vehicle advertises and its confirm tier.
 * [endsStick] is true when picking it would turn the controller's stick control off.
 */
data class ModeChoice(val name: String, val current: Boolean, val gesture: ConfirmGesture, val endsStick: Boolean = false)

/** The open picker; [enabled] and [reasonKey] come from the `set-mode` skill state. */
data class ModePickerUi(val choices: List<ModeChoice>, val enabled: Boolean, val reasonKey: String?)

/**
 * Every mode the vehicle advertises, in firmware table order, with the tier its change needs.
 * [endsStick] says which modes would turn stick control off (only asked while it is on).
 */
fun modeChoices(available: List<FlightMode>, currentMode: String?, endsStick: (String) -> Boolean = { false }): List<ModeChoice> =
    available.map { mode ->
        val current = mode.name.equals(currentMode, ignoreCase = true)
        ModeChoice(
            name = mode.name,
            current = current,
            gesture = modeChangeConfirm(mode.name)?.gesture ?: ConfirmGesture.Tap,
            endsStick = !current && endsStick(mode.name),
        )
    }

/** Every firmware mode as a tile grid, the current one marked; the Mode sheet's advanced section. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FcModeGrid(state: ModePickerUi, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        when {
            state.reasonKey != null -> Text(reasonText(state.reasonKey), style = MaterialTheme.typography.bodyMedium, color = colors.statusWarning)
            state.choices.isEmpty() -> Text(specText("flow.mode.empty"), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            state.choices.forEach { choice ->
                Box(Modifier.width(132.dp)) {
                    ModeTile(choice, state.enabled) { onPick(choice.name) }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(choice: ModeChoice, enabled: Boolean, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    val tier = when (choice.gesture) {
        ConfirmGesture.Tap -> specText("flow.mode.tier.tap")
        ConfirmGesture.Hold -> specText("setup.confirm.hold")
        ConfirmGesture.Slide -> specText("confirm.slide")
        ConfirmGesture.Guarded -> specText("setup.confirm.hold")
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
                if (choice.current) specText("flow.mode.current") else tier,
                style = MaterialTheme.typography.labelSmall,
                color = colors.fgSecondary,
                maxLines = 1,
            )
            if (choice.endsStick) {
                Text(
                    specText("flow.mode.ends_stick"),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.statusWarning,
                    maxLines = 2,
                )
            }
        }
    }
}
