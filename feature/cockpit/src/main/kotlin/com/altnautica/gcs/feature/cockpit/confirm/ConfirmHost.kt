package com.altnautica.gcs.feature.cockpit.confirm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.SlideToConfirm
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.skills.builtin.TakeoffAltitude
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.preflight.preflightItemLabel
import com.altnautica.gcs.feature.cockpit.skillbar.confirmMessage
import com.altnautica.gcs.feature.cockpit.skillbar.confirmTitle
import com.altnautica.gcs.feature.cockpit.skillbar.skillLabel
import kotlin.math.roundToInt

/** Callbacks of the confirm sheet; [ConfirmController] implements every one. */
interface ConfirmActions {
    fun onTouchHold(pressed: Boolean)
    fun onSlideCompleted()
    fun setAltitude(meters: Float)
    fun setOverride(on: Boolean)
    fun cancel()
}

/** The CS-6 confirm sheet: hold, slide or guarded, with altitude stepper and checklist override when they apply. */
@Composable
fun ConfirmHost(state: ConfirmUiState?, actions: ConfirmActions) {
    state ?: return
    val policy = state.request.policy
    val skill = state.request.skill
    ConfirmSheet(
        title = confirmTitle(policy.titleKey, skillLabel(skill)),
        message = confirmMessage(policy.messageKey),
        onDismiss = actions::cancel,
        danger = policy.danger,
    ) {
        state.altitudeM?.let { alt -> AltitudeStepper(alt, actions::setAltitude) }

        if (state.failingChecks.isNotEmpty()) {
            Text(stringResource(R.string.cockpit_confirm_checklist_failing), style = MaterialTheme.typography.labelLarge, color = AdosTheme.colors.statusWarning)
            for (check in state.failingChecks) {
                Text("• " + preflightItemLabel(check.item, check.known), style = MaterialTheme.typography.bodyMedium)
            }
            SwitchSettingRow(
                title = stringResource(R.string.cockpit_confirm_override),
                checked = state.overrideChecklist,
                onCheckedChange = actions::setOverride,
            )
        }

        when (state.gesture) {
            ConfirmGesture.Slide -> {
                SlideToConfirm(
                    label = stringResource(R.string.cockpit_confirm_slide),
                    onConfirmed = actions::onSlideCompleted,
                    enabled = state.gestureEnabled,
                )
                Text(
                    stringResource(R.string.cockpit_confirm_slide_hint, policy.holdMs / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = AdosTheme.colors.fgSecondary,
                )
                if (state.progress > 0f) {
                    CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(AdosSize.IconLg))
                }
            }
            ConfirmGesture.Guarded -> {
                val left = state.guardRemainingMs ?: 0L
                Text(
                    stringResource(R.string.cockpit_confirm_guard, (left / 100f).roundToInt() / 10f),
                    style = AdosTypography.numeric.medium,
                    color = AdosTheme.colors.statusError,
                )
                HoldRing(stringResource(R.string.cockpit_confirm_hold, policy.holdMs / 1000f), state.progress, state.gestureEnabled, true, actions::onTouchHold)
            }
            ConfirmGesture.Hold, ConfirmGesture.Tap -> {
                HoldRing(stringResource(R.string.cockpit_confirm_hold, policy.holdMs / 1000f), state.progress, state.gestureEnabled, policy.danger, actions::onTouchHold)
            }
        }
        Text(stringResource(R.string.cockpit_confirm_input_hint), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
    }
}

@Composable
private fun HoldRing(label: String, progress: Float, enabled: Boolean, danger: Boolean, onHold: (Boolean) -> Unit) {
    val colors = AdosTheme.colors
    val accent = if (danger) colors.statusError else colors.accentPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AdosTheme.touchMin * 1.5f)
            .border(BorderStroke(1.dp, if (enabled) accent else colors.borderDefault), RoundedCornerShape(AdosRadius.Lg))
            .background(accent.copy(alpha = if (enabled) 0.12f else 0.04f), RoundedCornerShape(AdosRadius.Lg))
            .semantics { role = Role.Button }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    onHold(true)
                    tryAwaitRelease()
                    onHold(false)
                })
            }
            .padding(AdosSpacing.S3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(AdosSize.IconXl),
            color = accent,
            trackColor = colors.borderDefault,
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.width(AdosSpacing.S3))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (enabled) colors.fgPrimary else colors.fgTertiary)
    }
}

@Composable
private fun AltitudeStepper(altitude: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.cockpit_confirm_altitude), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(altitude - TakeoffAltitude.STEP_M) }, enabled = altitude > TakeoffAltitude.MIN_M) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.cockpit_confirm_altitude_down))
        }
        Text(stringResource(R.string.cockpit_altitude_m, altitude.roundToInt()), style = AdosTypography.numeric.large)
        IconButton(onClick = { onChange(altitude + TakeoffAltitude.STEP_M) }, enabled = altitude < TakeoffAltitude.MAX_M) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.cockpit_confirm_altitude_up))
        }
    }
}
