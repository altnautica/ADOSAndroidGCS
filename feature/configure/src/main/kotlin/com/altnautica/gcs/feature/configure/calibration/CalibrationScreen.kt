package com.altnautica.gcs.feature.configure.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCard
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.calibration.AccelPose
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.link.calibration.CompassStatus
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.commandResultText
import com.altnautica.gcs.feature.configure.wizard.SetupProblemText
import com.altnautica.gcs.core.link.text.linkReasonOrMessage
import androidx.compose.ui.platform.LocalContext

/** [onRebootAdvised] runs once when a calibration whose offsets need a reboot finishes. */
@Composable
fun CalibrationRoute(nodeId: DeviceId, modifier: Modifier = Modifier, onRebootAdvised: () -> Unit = {}) {
    val viewModel = hiltViewModel<CalibrationViewModel, CalibrationViewModel.Factory>(
        key = "cal-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val rebootAdvised by viewModel.rebootAdvised.collectAsStateWithLifecycle()
    LaunchedEffect(rebootAdvised) {
        if (rebootAdvised) {
            onRebootAdvised()
            viewModel.consumeRebootAdvice()
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.altnautica.gcs.feature.configure.wizard.LocalSetupText provides viewModel.text,
    ) {
        CalibrationScreen(
            state = state,
            onStart = viewModel::start,
            onConfirmPose = viewModel::confirmAccelPosition,
            onAcceptCompass = viewModel::acceptCompass,
            onSaveRadio = viewModel::saveRadio,
            onCancel = viewModel::cancel,
            onAgentAction = viewModel::agentAction,
            modifier = modifier,
        )
    }
}

@Composable
fun poseLabel(pose: AccelPose): String = stringResource(
    when (pose) {
        AccelPose.Level -> R.string.cfg_pose_level
        AccelPose.LeftSide -> R.string.cfg_pose_left
        AccelPose.RightSide -> R.string.cfg_pose_right
        AccelPose.NoseDown -> R.string.cfg_pose_nose_down
        AccelPose.NoseUp -> R.string.cfg_pose_nose_up
        AccelPose.Back -> R.string.cfg_pose_back
    },
)

@Composable
private fun kindLabel(kind: CalibrationKind): String = stringResource(
    when (kind) {
        CalibrationKind.Gyro -> R.string.cfg_cal_gyro
        CalibrationKind.Level -> R.string.cfg_cal_level
        CalibrationKind.Accel -> R.string.cfg_cal_accel
        CalibrationKind.Compass -> R.string.cfg_cal_compass
        CalibrationKind.Radio -> R.string.cfg_cal_radio
        CalibrationKind.Esc -> R.string.cfg_cal_esc
        CalibrationKind.Airspeed -> R.string.cfg_cal_airspeed
    },
)

private fun blockText(block: CalibrationBlock): Int = when (block) {
    CalibrationBlock.NoVehicle -> R.string.cfg_block_no_vehicle
    CalibrationBlock.LinkStale -> R.string.cfg_block_link_stale
    CalibrationBlock.Armed -> R.string.cfg_block_armed
}

private fun kindIcon(kind: CalibrationKind): ImageVector = when (kind) {
    CalibrationKind.Gyro -> Icons.Outlined.Sync
    CalibrationKind.Level -> Icons.Outlined.Straighten
    CalibrationKind.Accel -> Icons.Outlined.ScreenRotation
    CalibrationKind.Compass -> Icons.Outlined.Explore
    CalibrationKind.Radio -> Icons.Outlined.SettingsRemote
    CalibrationKind.Esc -> Icons.Outlined.Bolt
    CalibrationKind.Airspeed -> Icons.Outlined.Air
}

@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    onStart: (CalibrationKind) -> Unit,
    onConfirmPose: () -> Unit,
    onAcceptCompass: () -> Unit,
    onSaveRadio: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onAgentAction: (action: String, args: kotlinx.serialization.json.JsonObject?) -> Unit = { _, _ -> },
) {
    val colors = AdosTheme.colors
    // A calibration with a precaution starts only after the operator confirms it.
    var confirming by remember { mutableStateOf<CalibrationKind?>(null) }
    confirming?.let { kind ->
        AdosConfirmSheet(
            title = kindLabel(kind),
            message = kind.precaution()?.let { stringResource(it) }.orEmpty(),
            onDismiss = { confirming = null },
            danger = kind != CalibrationKind.Airspeed,
        ) {
            AdosButton(
                stringResource(R.string.cfg_cal_start),
                onClick = {
                    confirming = null
                    onStart(kind)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    Column(modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        SectionHeader(stringResource(R.string.cfg_cal_title))
        state.block?.let {
            Text(stringResource(blockText(it)), color = colors.fgSecondary)
        }
        val blockedReason = state.block?.let { stringResource(blockText(it)) }
        if (state.agent) state.agentProblem?.let { SetupProblemText(it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            CalibrationKind.entries.filter(state::offers).forEach { kind ->
                ConsoleCard(
                    title = kindLabel(kind),
                    icon = kindIcon(kind),
                    onClick = { if (kind.precaution() != null) confirming = kind else onStart(kind) },
                    enabled = state.canStart,
                    disabledReason = blockedReason,
                )
            }
        }
        if (state.agent) {
            Text(stringResource(R.string.cfg_setup_gyro_agent), color = colors.fgTertiary, style = MaterialTheme.typography.bodySmall)
            val step = state.agentStep
            if (step == null) {
                Text(stringResource(R.string.cfg_cal_idle), color = colors.fgSecondary)
            } else {
                com.altnautica.gcs.feature.configure.wizard.WizardStepView(step, busy = state.busy, onAction = onAgentAction)
                if (state.running) {
                    AdosButton(stringResource(R.string.cfg_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
                }
            }
            return@Column
        }
        when (val cal = state.calibration) {
            CalibrationState.Idle -> Text(stringResource(R.string.cfg_cal_idle), color = colors.fgSecondary)
            is CalibrationState.Running -> {
                Text(stringResource(R.string.cfg_cal_running, kindLabel(cal.kind)), color = colors.fgPrimary)
                cal.message?.let { Text(it, color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall) }
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (cal.kind == CalibrationKind.Gyro || cal.kind == CalibrationKind.Level) {
                    Text(stringResource(R.string.cfg_cal_keep_still), color = colors.statusWarning)
                }
                AdosButton(stringResource(R.string.cfg_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
            }
            is CalibrationState.AccelPosition -> {
                val step = state.accelStep
                if (step != null) {
                    Text(stringResource(R.string.cfg_pose_step, step.index, step.total), style = MaterialTheme.typography.labelLarge, color = colors.fgSecondary)
                    val label = poseLabel(step.pose)
                    Text(label, style = MaterialTheme.typography.titleLarge, color = colors.fgPrimary)
                    AccelPoseIllustration(step.pose, label)
                }
                cal.message?.let { Text(it, color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    AdosButton(stringResource(R.string.cfg_pose_confirm), onClick = onConfirmPose, enabled = !state.busy)
                    AdosButton(stringResource(R.string.cfg_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
                }
            }
            is CalibrationState.Compass -> {
                Text(stringResource(R.string.cfg_compass_rotate), color = colors.fgPrimary)
                state.compassPct?.let { pct ->
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                }
                cal.progress.forEach { p ->
                    Text(stringResource(R.string.cfg_compass_progress, p.compassId + 1, p.completionPct), color = colors.fgSecondary)
                }
                cal.reports.forEach { r ->
                    val ok = r.status == CompassStatus.Success
                    StatusChip(
                        level = if (ok) StatusLevel.Success else StatusLevel.Error,
                        label = stringResource(
                            if (ok) R.string.cfg_compass_report_ok else R.string.cfg_compass_report_failed,
                            r.compassId + 1, r.fitness,
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    AdosButton(stringResource(R.string.cfg_compass_accept), onClick = onAcceptCompass, enabled = state.canAcceptCompass)
                    AdosButton(stringResource(R.string.cfg_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
                }
            }
            is CalibrationState.Radio -> {
                Text(stringResource(R.string.cfg_radio_move), color = colors.fgPrimary)
                cal.channels.forEach { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cfg_radio_channel, ch.channel), modifier = Modifier.weight(0.25f))
                        LinearProgressIndicator(
                            progress = { ((ch.current - 800) / 1400f).coerceIn(0f, 1f) },
                            modifier = Modifier.weight(0.45f),
                        )
                        Text(
                            stringResource(R.string.cfg_radio_range, ch.min, ch.current, ch.max),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(0.3f).padding(start = AdosSpacing.S2),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    AdosButton(stringResource(R.string.cfg_radio_save), onClick = onSaveRadio, enabled = !state.busy)
                    AdosButton(stringResource(R.string.cfg_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
                }
            }
            is CalibrationState.Succeeded -> StatusChip(
                level = StatusLevel.Success,
                label = cal.message ?: stringResource(R.string.cfg_cal_done, kindLabel(cal.kind)),
            )
            is CalibrationState.Failed -> StatusChip(
                level = StatusLevel.Error,
                label = stringResource(R.string.cfg_cal_failed, kindLabel(cal.kind), linkReasonOrMessage(LocalContext.current.resources, cal.reason)),
            )
        }
        state.lastResult?.let { Text(commandResultText(it), color = colors.fgTertiary, style = MaterialTheme.typography.bodySmall) }
    }
}
