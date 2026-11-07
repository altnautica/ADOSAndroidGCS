package com.altnautica.gcs.feature.configure.motor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.commandResultText

@Composable
fun MotorTestRoute(nodeId: DeviceId, motorCountHint: Int?, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<MotorTestViewModel, MotorTestViewModel.Factory>(
        key = "motor-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    LaunchedEffect(motorCountHint) { motorCountHint?.let(viewModel::setMotorCount) }
    // Leaving the page withdraws the propellers-off acknowledgement and stops any held motor.
    DisposableEffect(viewModel) { onDispose { viewModel.resetAcknowledgement() } }
    val agentTest = agent
    if (agentTest != null) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.altnautica.gcs.feature.configure.wizard.LocalSetupText provides viewModel.text,
        ) {
            AgentMotorTestScreen(
                test = agentTest,
                onRun = viewModel::runAgentTest,
                onAction = viewModel::agentAction,
                onAbort = viewModel::abortAgentTest,
                modifier = modifier,
            )
        }
        return
    }
    MotorTestScreen(
        state = state,
        frameKnown = motorCountHint != null,
        onAcknowledge = viewModel::setAcknowledged,
        onThrottle = viewModel::setThrottle,
        onMotorCount = viewModel::setMotorCount,
        onHoldStart = viewModel::startHold,
        onHoldEnd = viewModel::endHold,
        modifier = modifier,
    )
}

/** The motor test run by the vehicle's computer: its `outputs` step, rendered from the step schema. */
@Composable
fun AgentMotorTestScreen(
    test: AgentMotorTest,
    onRun: () -> Unit,
    onAction: (String, kotlinx.serialization.json.JsonObject?) -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = test.step?.state == com.altnautica.gcs.core.agent.fcsetup.SetupStepState.Running ||
        test.step?.state == com.altnautica.gcs.core.agent.fcsetup.SetupStepState.WaitingUser
    Column(modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        SectionHeader(stringResource(R.string.cfg_motor_title))
        Text(stringResource(R.string.cfg_setup_agent_running), color = AdosTheme.colors.fgSecondary)
        test.problem?.key?.let {
            Text(com.altnautica.gcs.feature.configure.wizard.LocalSetupText.current.format(it, emptyMap()), color = AdosTheme.colors.statusError)
        }
        val step = test.step
        if (step != null && running) {
            com.altnautica.gcs.feature.configure.wizard.WizardStepView(step, busy = test.busy, onAction = onAction)
            com.altnautica.gcs.core.designsystem.component.AdosButton(
                stringResource(R.string.cfg_cancel),
                onClick = onAbort,
                variant = com.altnautica.gcs.core.designsystem.component.AdosButtonVariant.Secondary,
            )
        } else {
            step?.result?.takeIf { it.isNotEmpty() }?.let { com.altnautica.gcs.feature.configure.wizard.ResultCard(it) }
            com.altnautica.gcs.core.designsystem.component.AdosButton(
                stringResource(R.string.cfg_motor_run_agent),
                onClick = onRun,
                enabled = !test.busy,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotorTestScreen(
    state: MotorTestUiState,
    frameKnown: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onThrottle: (Float) -> Unit,
    onMotorCount: (Int) -> Unit,
    onHoldStart: (Int) -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Column(modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        SectionHeader(stringResource(R.string.cfg_motor_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.acknowledged, onCheckedChange = onAcknowledge)
            Text(stringResource(R.string.cfg_motor_props_off), color = colors.statusWarning, style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.cfg_motor_throttle, state.throttlePct), color = colors.fgPrimary)
        Slider(
            value = state.throttlePct,
            onValueChange = onThrottle,
            valueRange = 0f..MotorTestViewModel.MAX_THROTTLE_PCT,
            steps = MotorTestViewModel.MAX_THROTTLE_PCT.toInt() - 1,
        )
        NumberSettingRow(
            title = stringResource(R.string.cfg_motor_count),
            value = state.motorCount,
            onValueChange = onMotorCount,
            range = 1..MotorTestViewModel.MAX_MOTORS,
            decrementDescription = stringResource(R.string.cfg_motor_count_decrease),
            incrementDescription = stringResource(R.string.cfg_motor_count_increase),
            enabled = state.runningMotor == null,
        )
        if (!frameKnown) {
            Text(stringResource(R.string.cfg_motor_count_unknown), color = colors.statusWarning, style = MaterialTheme.typography.bodySmall)
        }
        state.block?.let { block ->
            Text(
                stringResource(
                    when (block) {
                        MotorTestBlock.NoVehicle -> R.string.cfg_block_no_vehicle
                        MotorTestBlock.LinkStale -> R.string.cfg_block_link_stale
                        MotorTestBlock.Armed -> R.string.cfg_block_armed
                        MotorTestBlock.NotAcknowledged -> R.string.cfg_motor_block_ack
                        MotorTestBlock.Running -> R.string.cfg_motor_block_running
                    },
                ),
                color = colors.fgSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(stringResource(R.string.cfg_motor_hold_hint), color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            for (motor in 1..state.motorCount) {
                MotorHoldButton(
                    label = stringResource(R.string.cfg_motor_n, motor),
                    running = state.runningMotor == motor,
                    enabled = state.block == null || state.runningMotor == motor,
                    onPress = { onHoldStart(motor) },
                    onRelease = onHoldEnd,
                )
            }
        }
        state.lastResult?.let { Text(commandResultText(it), color = colors.fgSecondary) }
    }
}

private fun isPressKey(key: Key) = key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter || key == Key.ButtonA

/**
 * A press-and-hold surface: [onPress] when a finger or a focused Enter/D-pad centre/gamepad A
 * goes down, [onRelease] when it comes up. The release always fires, even if the button is
 * disabled mid-press, so a held motor can never be left spinning by a state change.
 */
@Composable
private fun MotorHoldButton(label: String, running: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val colors = AdosTheme.colors
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val shape = RoundedCornerShape(AdosRadius.Lg)
    val accent = colors.accentPrimary
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = AdosTheme.touchMin, minHeight = AdosTheme.touchMin)
            .alpha(if (enabled) 1f else 0.5f)
            .border(BorderStroke(1.dp, if (running) accent else colors.borderStrong), shape)
            .background(if (running) accent else Color.Transparent, shape)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!currentEnabled) return@detectTapGestures
                    currentOnPress()
                    try {
                        tryAwaitRelease()
                    } finally {
                        currentOnRelease()
                    }
                })
            }
            .onKeyEvent { event ->
                if (!isPressKey(event.key)) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> if (currentEnabled && event.nativeKeyEvent.repeatCount == 0) currentOnPress()
                    KeyEventType.KeyUp -> currentOnRelease()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(enabled)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (running) colors.accentForeground else colors.fgPrimary,
        )
    }
}
