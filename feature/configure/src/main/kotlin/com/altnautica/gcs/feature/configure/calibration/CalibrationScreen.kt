package com.altnautica.gcs.feature.configure.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.calibration.AccelPose
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.link.calibration.CompassStatus
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.feature.configure.common.commandResultText

@Composable
fun CalibrationRoute(nodeId: DeviceId, modifier: Modifier = Modifier) {
    val viewModel = hiltViewModel<CalibrationViewModel, CalibrationViewModel.Factory>(
        key = "cal-${nodeId.value}",
        creationCallback = { it.create(nodeId.value) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    CalibrationScreen(
        state = state,
        onStart = viewModel::start,
        onConfirmPose = viewModel::confirmAccelPosition,
        onAcceptCompass = viewModel::acceptCompass,
        onSaveRadio = viewModel::saveRadio,
        onCancel = viewModel::cancel,
        modifier = modifier,
    )
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
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalibrationScreen(
    state: CalibrationUiState,
    onStart: (CalibrationKind) -> Unit,
    onConfirmPose: () -> Unit,
    onAcceptCompass: () -> Unit,
    onSaveRadio: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Column(modifier.fillMaxWidth().padding(AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        SectionHeader(stringResource(R.string.cfg_cal_title))
        state.block?.let {
            Text(
                stringResource(
                    when (it) {
                        CalibrationBlock.NoVehicle -> R.string.cfg_block_no_vehicle
                        CalibrationBlock.LinkStale -> R.string.cfg_block_link_stale
                        CalibrationBlock.Armed -> R.string.cfg_block_armed
                    },
                ),
                color = colors.fgSecondary,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            CalibrationKind.entries.forEach { kind ->
                AdosButton(kindLabel(kind), onClick = { onStart(kind) }, enabled = state.canStart, variant = AdosButtonVariant.Secondary)
            }
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
                label = stringResource(R.string.cfg_cal_failed, kindLabel(cal.kind), cal.reason),
            )
        }
        state.lastResult?.let { Text(commandResultText(it), color = colors.fgTertiary, style = MaterialTheme.typography.bodySmall) }
    }
}
