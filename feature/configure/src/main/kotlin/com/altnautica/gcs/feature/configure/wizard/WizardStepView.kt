package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.configure.R
import kotlinx.serialization.json.JsonObject

/** How long a hold-confirm action must be held. */
const val SETUP_HOLD_MS = 1_500

/** The step that ends the ground wizard by offering the onboard pilot. */
const val ADOS_INTRO_STEP = "ados_intro"

/** The engine sends bare live and result keys (`pose`, `gains_after`); their labels live under these prefixes. */
private const val LIVE_PREFIX = "setup.live."
private const val RESULT_PREFIX = "setup.result."

/**
 * One setup step rendered from its schema: title, body, illustration, progress ring,
 * live values, blockers, result and its actions. Action inputs come from
 * [SetupInputs]; [onAction] receives the action id and its arguments.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WizardStepView(
    step: SetupStep,
    busy: Boolean,
    onAction: (action: String, args: JsonObject?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    val args = step.args.textArgs()
    val inputs = rememberSetupInputs(step)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
            ProgressRing(step)
            Column(Modifier.weight(1f)) {
                Text(text.format(step.titleKey, args), style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary)
                Text(stateLabel(step.state), style = MaterialTheme.typography.labelMedium, color = stateColor(step.state))
            }
        }
        step.bodyKey?.let { key ->
            val body = text.format(key, args)
            if (step.step == ADOS_INTRO_STEP) {
                // Three short lines: what the pilot flies, what the flight controller still does, how to take over.
                body.lines().filter { it.isNotBlank() }.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
                }
            } else {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            }
        }
        step.image?.let { key ->
            SetupImage(key, text.format(step.titleKey, args), colors.fgPrimary, Modifier.widthIn(max = 360.dp))
        }
        if (step.live.isNotEmpty()) {
            LiveTable(step.live.entries.associate { (k, v) -> text.label(k, LIVE_PREFIX, args) to text.value(v, args) })
        }
        step.blockers.forEach { key ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Icon(Icons.Outlined.Block, contentDescription = null, tint = colors.statusError, modifier = Modifier.size(18.dp))
                Text(text.format(key, args), color = colors.statusError, style = MaterialTheme.typography.bodyMedium)
            }
        }
        step.result?.takeIf { it.isNotEmpty() }?.let { ResultCard(it) }
        SetupInputFields(inputs)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            val blocked = step.blockers.isNotEmpty()
            step.actions.forEach { action ->
                ActionControl(
                    action = action,
                    label = text.format(action.labelKey, args),
                    enabled = !busy && !blocked && (action.style != SetupActionStyle.Primary || inputs.ready),
                    onFire = { onAction(action.id, if (inputs.ready) inputs.args() else null) },
                )
            }
        }
    }
}

@Composable
private fun ActionControl(action: SetupAction, label: String, enabled: Boolean, onFire: () -> Unit) {
    val variant = when (action.style) {
        SetupActionStyle.Primary -> AdosButtonVariant.Primary
        SetupActionStyle.Secondary -> AdosButtonVariant.Secondary
        SetupActionStyle.Danger -> AdosButtonVariant.Danger
    }
    val tag = Modifier.testTag("setup-action-${action.id}")
    when (action.confirm) {
        SetupConfirm.None -> AdosButton(label, onClick = onFire, modifier = tag, variant = variant, enabled = enabled)
        SetupConfirm.Hold -> HoldToConfirmButton(
            label = stringResource(R.string.cfg_setup_hold_to, label),
            holdMs = SETUP_HOLD_MS,
            onConfirmed = onFire,
            modifier = tag,
            enabled = enabled,
            danger = action.style == SetupActionStyle.Danger,
        )
        SetupConfirm.HoldPropsOff -> Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
            HoldToConfirmButton(
                label = LocalSetupText.current.format(KEY_PROPS_OFF, emptyMap()),
                holdMs = SETUP_HOLD_MS,
                onConfirmed = onFire,
                modifier = tag,
                enabled = enabled,
                danger = true,
            )
        }
    }
}

@Composable
private fun ProgressRing(step: SetupStep) {
    val colors = AdosTheme.colors
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        when {
            step.state == SetupStepState.Done -> Icon(Icons.Outlined.CheckCircle, null, tint = colors.statusSuccess, modifier = Modifier.size(40.dp))
            step.state == SetupStepState.Failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.statusError, modifier = Modifier.size(40.dp))
            step.progress != null -> {
                CircularProgressIndicator(progress = { step.progress!!.coerceIn(0f, 1f) }, modifier = Modifier.size(44.dp), color = colors.accentPrimary)
                Text("${(step.progress!! * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.fgPrimary)
            }
            step.state == SetupStepState.Running -> CircularProgressIndicator(modifier = Modifier.size(44.dp), color = colors.accentPrimary)
        }
    }
}

@Composable
private fun LiveTable(rows: Map<String, String>) {
    val colors = AdosTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(AdosRadius.Lg)).padding(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, Modifier.weight(1f), color = colors.fgSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(value, color = colors.fgPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A step's or wizard's stored result: each value under its setup-string label. */
@Composable
fun ResultCard(result: JsonObject, modifier: Modifier = Modifier) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    Column(
        modifier.fillMaxWidth().background(colors.bgSurface, RoundedCornerShape(AdosRadius.Lg)).padding(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        Text(stringResource(R.string.cfg_setup_results), style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary)
        result.forEach { (key, value) ->
            Row(Modifier.fillMaxWidth()) {
                Text(text.label(key, RESULT_PREFIX), Modifier.weight(1f), color = colors.fgSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(value.displayText(), color = colors.fgPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun stateLabel(state: SetupStepState): String = stringResource(
    when (state) {
        SetupStepState.Pending -> R.string.cfg_setup_state_pending
        SetupStepState.Running -> R.string.cfg_setup_state_running
        SetupStepState.WaitingUser -> R.string.cfg_setup_state_waiting
        SetupStepState.Done -> R.string.cfg_setup_state_done
        SetupStepState.Failed -> R.string.cfg_setup_state_failed
        SetupStepState.Skipped -> R.string.cfg_setup_state_skipped
    },
)

@Composable
private fun stateColor(state: SetupStepState) = when (state) {
    SetupStepState.Done -> AdosTheme.colors.statusSuccess
    SetupStepState.Failed -> AdosTheme.colors.statusError
    SetupStepState.WaitingUser -> AdosTheme.colors.statusWarning
    else -> AdosTheme.colors.fgSecondary
}
