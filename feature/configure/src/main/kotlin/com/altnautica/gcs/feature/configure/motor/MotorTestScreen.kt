package com.altnautica.gcs.feature.configure.motor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
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
    LaunchedEffect(motorCountHint) { motorCountHint?.let(viewModel::setMotorCount) }
    MotorTestScreen(
        state = state,
        onAcknowledge = viewModel::setAcknowledged,
        onThrottle = viewModel::setThrottle,
        onDuration = viewModel::setDuration,
        onTest = viewModel::test,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MotorTestScreen(
    state: MotorTestUiState,
    onAcknowledge: (Boolean) -> Unit,
    onThrottle: (Float) -> Unit,
    onDuration: (Float) -> Unit,
    onTest: (Int) -> Unit,
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
        Text(stringResource(R.string.cfg_motor_duration, state.durationS), color = colors.fgPrimary)
        Slider(value = state.durationS, onValueChange = onDuration, valueRange = 0.5f..MotorTestViewModel.MAX_DURATION_S)
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            for (motor in 1..state.motorCount) {
                AdosButton(
                    label = stringResource(R.string.cfg_motor_n, motor),
                    onClick = { onTest(motor) },
                    enabled = state.block == null,
                    variant = if (state.runningMotor == motor) AdosButtonVariant.Primary else AdosButtonVariant.Secondary,
                )
            }
        }
        state.lastResult?.let { Text(commandResultText(it), color = colors.fgSecondary) }
    }
}
