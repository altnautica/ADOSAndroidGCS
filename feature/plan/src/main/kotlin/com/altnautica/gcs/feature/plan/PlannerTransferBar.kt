package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.core.designsystem.component.SlideToConfirm
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.model.CommandResult

// ── Transfer and start ─────────────────────────────────────

@Composable
internal fun missionTypeLabel(type: MissionType) = stringResource(
    when (type) {
        MissionType.Mission -> R.string.plan_transfer_mission
        MissionType.Fence -> R.string.plan_transfer_fence
        MissionType.Rally -> R.string.plan_transfer_rally
    },
)

@Composable
private fun missionErrorLabel(error: MissionError) = when (error) {
    MissionError.NoLink -> stringResource(R.string.plan_error_no_link)
    MissionError.Busy -> stringResource(R.string.plan_error_busy)
    MissionError.Timeout -> stringResource(R.string.plan_error_timeout)
    is MissionError.WriteFailed -> stringResource(R.string.plan_error_write, error.reason)
    is MissionError.Rejected -> stringResource(R.string.plan_error_rejected, error.code)
    is MissionError.VerifyMismatch -> stringResource(R.string.plan_error_verify, missionTypeLabel(error.type))
}

@Composable
internal fun TransferBar(state: PlannerUiState, actions: PlannerActions) {
    val colors = AdosTheme.colors
    val link = state.link
    val connected = link as? VehicleLink.Connected
    val reason = when {
        link == VehicleLink.None -> stringResource(R.string.plan_vehicle_needed)
        link == VehicleLink.Connecting -> stringResource(R.string.plan_vehicle_connecting_detail)
        link == VehicleLink.Unsupported -> stringResource(R.string.plan_vehicle_unsupported_detail)
        connected != null && !connected.linkFresh -> stringResource(R.string.plan_vehicle_stale_detail)
        else -> null
    }
    val canTransfer = connected != null && !state.transferring
    val issues = state.issues
    val uploadBlocked = issues.any { it.kind.blocking }
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.statusWarning)
        if (connected != null) {
            StatusChip(
                level = if (state.inSyncWithVehicle) StatusLevel.Success else StatusLevel.Neutral,
                label = stringResource(if (state.inSyncWithVehicle) R.string.plan_in_sync else R.string.plan_not_in_sync),
            )
        }
        state.drone?.takeIf { it.differs }?.let {
            Text(stringResource(R.string.plan_drone_differs), style = MaterialTheme.typography.bodySmall, color = colors.statusWarning)
            AdosButton(
                stringResource(R.string.plan_load_from_drone),
                onClick = actions.onLoadFromDrone,
                enabled = !state.transferring,
                variant = AdosButtonVariant.Secondary,
                icon = Icons.Outlined.CloudDownload,
            )
        }
        PlanIssueList(issues)
        if (uploadBlocked && connected != null) {
            Text(stringResource(R.string.plan_issues_blocking), style = MaterialTheme.typography.bodySmall, color = colors.statusError)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(stringResource(R.string.plan_upload), onClick = actions.onUpload, enabled = canTransfer && !uploadBlocked, icon = Icons.Outlined.CloudUpload)
            AdosButton(stringResource(R.string.plan_download), onClick = actions.onDownload, enabled = canTransfer, variant = AdosButtonVariant.Secondary, icon = Icons.Outlined.CloudDownload)
        }
        when (val t = state.transfer) {
            TransferState.Idle -> Unit
            is TransferState.Running -> {
                val type = missionTypeLabel(t.type)
                val text = when {
                    t.direction == TransferDirection.Clear -> stringResource(R.string.plan_transfer_clearing, type)
                    t.direction == TransferDirection.Upload -> stringResource(R.string.plan_transfer_uploading, type, t.done, t.total)
                    else -> stringResource(R.string.plan_transfer_downloading, type, t.done, t.total)
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.hudInk)
                if (t.total > 0) {
                    LinearProgressIndicator(
                        progress = { t.done.toFloat() / t.total },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accentPrimary,
                        trackColor = colors.borderDefault,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accentPrimary, trackColor = colors.borderDefault)
                }
            }
            is TransferState.Verifying -> {
                Text(stringResource(R.string.plan_verifying, missionTypeLabel(t.type)), style = MaterialTheme.typography.bodySmall, color = colors.hudInk)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accentPrimary, trackColor = colors.borderDefault)
            }
            is TransferState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    level = StatusLevel.Success,
                    label = pluralStringResource(
                        if (t.direction == TransferDirection.Download) R.plurals.plan_transfer_download_done else R.plurals.plan_transfer_upload_done,
                        t.itemCount,
                        t.itemCount,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = actions.onDismissTransfer) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.plan_transfer_dismiss), tint = colors.fgSecondary)
                }
            }
            is TransferState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                val verb = stringResource(
                    when (t.direction) {
                        TransferDirection.Upload -> R.string.plan_transfer_upload_word
                        TransferDirection.Download -> R.string.plan_transfer_download_word
                        TransferDirection.Clear -> R.string.plan_transfer_clear_word
                    },
                )
                StatusChip(
                    level = StatusLevel.Error,
                    label = stringResource(R.string.plan_transfer_failed, verb, missionTypeLabel(t.type), missionErrorLabel(t.error)),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (t.direction == TransferDirection.Upload) {
                    AdosButton(
                        stringResource(R.string.plan_retry),
                        onClick = actions.onRetryUpload,
                        enabled = connected != null && !uploadBlocked,
                        variant = AdosButtonVariant.Secondary,
                    )
                }
                IconButton(onClick = actions.onDismissTransfer) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.plan_transfer_dismiss), tint = colors.fgSecondary)
                }
            }
        }
        AdosButton(
            stringResource(R.string.plan_start_mission),
            onClick = actions.onOpenStart,
            enabled = connected != null && connected.linkFresh && state.inSyncWithVehicle && state.plan.items.isNotEmpty() && !state.transferring,
            icon = Icons.Outlined.PlayArrow,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun commandResultLabel(result: CommandResult) = when (result) {
    CommandResult.Accepted -> ""
    is CommandResult.Rejected -> stringResource(R.string.plan_result_rejected, result.reason, result.code)
    CommandResult.Unsupported -> stringResource(R.string.plan_result_unsupported)
    CommandResult.Timeout -> stringResource(R.string.plan_result_timeout)
    is CommandResult.Denied -> stringResource(R.string.plan_result_denied, result.reason)
    is CommandResult.Queued -> stringResource(R.string.plan_result_queued)
}

@Composable
internal fun StartMissionSheetView(sheet: StartMissionSheet, actions: PlannerActions) {
    val colors = AdosTheme.colors
    AdosConfirmSheet(
        title = stringResource(R.string.plan_start_title),
        message = stringResource(if (sheet.armed) R.string.plan_start_message_armed else R.string.plan_start_message_disarmed),
        onDismiss = actions.onDismissStart,
        danger = true,
    ) {
        sheet.checks.forEach { check ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Text(
                    stringResource(
                        when (check.kind) {
                            StartCheckKind.LinkFresh -> R.string.plan_start_check_link
                            StartCheckKind.PlanOnVehicle -> R.string.plan_start_check_plan
                            StartCheckKind.GpsFix -> R.string.plan_start_check_gps
                            StartCheckKind.HomeSet -> R.string.plan_start_check_home
                        },
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.fgPrimary,
                )
                StatusChip(
                    level = if (check.ok) StatusLevel.Success else if (check.kind.blocking) StatusLevel.Error else StatusLevel.Warning,
                    label = stringResource(if (check.ok) R.string.plan_start_check_ok else R.string.plan_start_check_failed),
                )
            }
        }
        if (sheet.needsOverride || sheet.overrideChecklist) {
            SwitchSettingRow(
                title = stringResource(R.string.plan_start_override),
                checked = sheet.overrideChecklist,
                onCheckedChange = actions.onOverride,
                summary = stringResource(R.string.plan_start_override_summary),
            )
        }
        sheet.error?.let { error ->
            val text = when (error) {
                is StartError.ArmRefused -> stringResource(R.string.plan_start_error_arm, commandResultLabel(error.result))
                is StartError.StartRefused -> stringResource(R.string.plan_start_error_start, commandResultLabel(error.result))
            }
            StatusChip(level = StatusLevel.Error, label = text)
        }
        SlideToConfirm(
            label = stringResource(if (sheet.running) R.string.plan_start_running else R.string.plan_start_slide),
            onConfirmed = actions.onStart,
            enabled = sheet.canSlide,
        )
    }
}
