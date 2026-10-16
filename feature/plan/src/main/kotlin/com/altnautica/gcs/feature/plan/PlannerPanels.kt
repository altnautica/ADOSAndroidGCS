@file:OptIn(ExperimentalMaterial3Api::class)

package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.annotation.StringRes
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.ConfirmSheet
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
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
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.model.PlanItem
import com.altnautica.gcs.feature.plan.model.altitudeProfile
import com.altnautica.gcs.feature.plan.survey.SurveyMode
import kotlin.math.roundToInt

/** Editor column: tool hint, selection or tool editor, vehicle transfer and altitude profile. */
@Composable
internal fun PlannerPanel(state: PlannerUiState, actions: PlannerActions) {
    val colors = AdosTheme.colors
    Text(toolHint(state.tool), style = MaterialTheme.typography.bodySmall, color = colors.hudInk2)
    when (val sel = state.selection) {
        is Selection.Item -> state.plan.items.firstOrNull { it.id == sel.id }?.let { item ->
            ItemEditor(item, state.plan.items.indexOf(item) + 1, actions)
        }
        is Selection.Rally -> state.plan.rally.firstOrNull { it.id == sel.id }?.let { rally ->
            EditorHeader(stringResource(R.string.plan_rally_title)) { actions.onSelect(null) }
            NumberSettingRow(
                title = stringResource(R.string.plan_rally_altitude),
                value = rally.altM.roundToInt(),
                onValueChange = { actions.onRallyAltitude(rally.id, it.toFloat()) },
                range = 0..1000,
                step = 5,
                decrementDescription = stringResource(R.string.plan_decrease),
                incrementDescription = stringResource(R.string.plan_increase),
                format = metresFormatter(),
            )
            AdosButton(stringResource(R.string.plan_rally_delete), onClick = { actions.onDeleteRally(rally.id) }, variant = AdosButtonVariant.Danger)
        }
        is Selection.Fence -> state.plan.fence.firstOrNull { it.id == sel.id }?.let { FenceEditor(it, actions) }
        null -> when (state.tool) {
            EditTool.Survey -> SurveyPanel(state, actions)
            EditTool.FencePolygon, EditTool.FenceCircle -> FenceToolPanel(state, actions)
            else -> DefaultsPanel(state, actions)
        }
    }
    TransferBar(state, actions)
    AltitudeProfile(state)
}

@Composable
private fun toolHint(tool: EditTool) = stringResource(
    when (tool) {
        EditTool.Waypoint -> R.string.plan_tool_hint_waypoint
        EditTool.Survey -> R.string.plan_tool_hint_survey
        EditTool.FencePolygon -> R.string.plan_tool_hint_fence_polygon
        EditTool.FenceCircle -> R.string.plan_tool_hint_fence_circle
        EditTool.Rally -> R.string.plan_tool_hint_rally
    },
)

@Composable
private fun EditorHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.plan_close_editor), tint = AdosTheme.colors.fgSecondary)
        }
    }
}

/** Formats a whole-number value with a unit string resource (`%1$d m`). */
@Composable
private fun unitFormatter(@StringRes id: Int): (Int) -> String {
    val resources = LocalResources.current
    return remember(resources, id) { { value: Int -> resources.getString(id, value) } }
}

@Composable
private fun metresFormatter() = unitFormatter(R.string.plan_unit_m)

@Composable
internal fun commandLabel(command: PlanCommand): String = stringResource(
    when (command) {
        PlanCommand.Waypoint -> R.string.plan_cmd_waypoint
        PlanCommand.Takeoff -> R.string.plan_cmd_takeoff
        PlanCommand.Land -> R.string.plan_cmd_land
        PlanCommand.ReturnToLaunch -> R.string.plan_cmd_rtl
        PlanCommand.LoiterTime -> R.string.plan_cmd_loiter_time
        PlanCommand.LoiterTurns -> R.string.plan_cmd_loiter_turns
        PlanCommand.ChangeSpeed -> R.string.plan_cmd_change_speed
        PlanCommand.SetRoiLocation -> R.string.plan_cmd_roi
        PlanCommand.DigicamControl -> R.string.plan_cmd_digicam
    },
)

@Composable
private fun refLabel(ref: AltitudeRef) = stringResource(
    when (ref) {
        AltitudeRef.Relative -> R.string.plan_ref_rel
        AltitudeRef.Msl -> R.string.plan_ref_msl
        AltitudeRef.Terrain -> R.string.plan_ref_terrain
    },
)

@Composable
private fun AltitudeRefSelector(selected: AltitudeRef?, onSelect: (AltitudeRef) -> Unit) {
    val options = listOf(AltitudeRef.Relative, AltitudeRef.Msl) + if (selected == AltitudeRef.Terrain) listOf(AltitudeRef.Terrain) else emptyList()
    Column {
        Text(stringResource(R.string.plan_item_altitude_ref), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, ref ->
                SegmentedButton(
                    selected = ref == selected,
                    onClick = { onSelect(ref) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(refLabel(ref)) },
                )
            }
        }
    }
}

@Composable
private fun ItemEditor(item: PlanItem, number: Int, actions: PlannerActions) {
    val colors = AdosTheme.colors
    EditorHeader(stringResource(R.string.plan_item_title, number)) { actions.onSelect(null) }
    val known = item.planCommand
    if (known != null) {
        val labels = PlanCommand.entries.associateWith { commandLabel(it) }
        SelectSettingRow(
            title = stringResource(R.string.plan_item_command),
            options = PlanCommand.entries,
            selected = known,
            optionLabel = { labels.getValue(it) },
            onSelect = { actions.onSetCommand(item.id, it) },
        )
    } else {
        Text(stringResource(R.string.plan_item_command_other, item.command), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
    }
    Text(
        item.position?.let { stringResource(R.string.plan_item_position, it.latDeg, it.lonDeg) } ?: stringResource(R.string.plan_item_no_position),
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    if (item.altitudeRef != null && item.command != MavCmd.NAV_RETURN_TO_LAUNCH) {
        NumberSettingRow(
            title = stringResource(R.string.plan_item_altitude),
            value = item.z.roundToInt(),
            onValueChange = { actions.onSetAltitude(item.id, it.toFloat()) },
            range = -500..10_000,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = metresFormatter(),
        )
        AltitudeRefSelector(item.altitudeRef) { actions.onSetAltitudeRef(item.id, it) }
    }
    if (MavCmd.isNav(item.command)) {
        val noSpeed = stringResource(R.string.plan_item_speed_none)
        val speedFmt = unitFormatter(R.string.plan_unit_ms)
        NumberSettingRow(
            title = stringResource(R.string.plan_item_speed),
            value = item.speedMs?.roundToInt() ?: 0,
            onValueChange = { actions.onSetSpeed(item.id, it.toFloat().takeIf { v -> v > 0f }) },
            range = 0..60,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = { if (it == 0) noSpeed else speedFmt(it) },
        )
    }
    val secondsFmt = unitFormatter(R.string.plan_unit_s)
    when (item.command) {
        MavCmd.NAV_WAYPOINT, MavCmd.NAV_LOITER_TIME -> NumberSettingRow(
            title = stringResource(if (item.command == MavCmd.NAV_WAYPOINT) R.string.plan_item_hold else R.string.plan_item_loiter_time),
            value = item.param1.takeIf { it.isFinite() }?.roundToInt() ?: 0,
            onValueChange = { actions.onSetParam(item.id, 1, it.toFloat()) },
            range = 0..3600,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = secondsFmt,
        )
        MavCmd.NAV_LOITER_TURNS -> NumberSettingRow(
            title = stringResource(R.string.plan_item_turns),
            value = item.param1.takeIf { it.isFinite() }?.roundToInt() ?: 1,
            onValueChange = { actions.onSetParam(item.id, 1, it.toFloat()) },
            range = 1..100,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
        )
        MavCmd.DO_CHANGE_SPEED -> {
            val fmt = unitFormatter(R.string.plan_unit_ms)
            NumberSettingRow(
                title = stringResource(R.string.plan_item_new_speed),
                value = item.param2.takeIf { it.isFinite() }?.roundToInt() ?: 0,
                onValueChange = { actions.onSetParam(item.id, 2, it.toFloat()) },
                range = 1..60,
                decrementDescription = stringResource(R.string.plan_decrease),
                incrementDescription = stringResource(R.string.plan_increase),
                format = fmt,
            )
        }
    }
    AdosButton(stringResource(R.string.plan_item_delete), onClick = { actions.onDeleteItem(item.id) }, variant = AdosButtonVariant.Danger)
}

@Composable
private fun DefaultsPanel(state: PlannerUiState, actions: PlannerActions) {
    NumberSettingRow(
        title = stringResource(R.string.plan_defaults_altitude),
        value = state.defaultAltM.roundToInt(),
        onValueChange = { actions.onDefaultAltitude(it.toFloat()) },
        range = 0..1000,
        step = 5,
        decrementDescription = stringResource(R.string.plan_decrease),
        incrementDescription = stringResource(R.string.plan_increase),
        format = metresFormatter(),
    )
    AltitudeRefSelector(state.defaultRef, actions.onDefaultRef)
}

@Composable
private fun SurveyPanel(state: PlannerUiState, actions: PlannerActions) {
    val s = state.surveySettings
    val colors = AdosTheme.colors
    SectionHeader(stringResource(R.string.plan_survey_title))
    val modeLabels = mapOf(
        SurveyMode.Camera to stringResource(R.string.plan_survey_mode_camera),
        SurveyMode.Spray to stringResource(R.string.plan_survey_mode_spray),
    )
    SelectSettingRow(
        title = stringResource(R.string.plan_survey_mode),
        options = SurveyMode.entries,
        selected = s.mode,
        optionLabel = { modeLabels.getValue(it) },
        onSelect = { actions.onSurveySettings(s.copy(mode = it)) },
    )
    val dec = stringResource(R.string.plan_decrease)
    val inc = stringResource(R.string.plan_increase)
    val pct = unitFormatter(R.string.plan_unit_pct)
    val deg = unitFormatter(R.string.plan_unit_deg)
    val ms = unitFormatter(R.string.plan_unit_ms)
    val m = unitFormatter(R.string.plan_unit_m)
    NumberSettingRow(stringResource(R.string.plan_survey_altitude), s.altitudeM.roundToInt(), { actions.onSurveySettings(s.copy(altitudeM = it.toFloat())) }, 5..500, dec, inc, step = 5, format = m)
    NumberSettingRow(stringResource(R.string.plan_survey_speed), s.speedMs.roundToInt(), { actions.onSurveySettings(s.copy(speedMs = it.toFloat())) }, 1..30, dec, inc, format = ms)
    when (s.mode) {
        SurveyMode.Camera -> {
            NumberSettingRow(stringResource(R.string.plan_survey_overlap), s.overlapPct.roundToInt(), { actions.onSurveySettings(s.copy(overlapPct = it.toFloat())) }, 0..95, dec, inc, step = 5, format = pct)
            NumberSettingRow(stringResource(R.string.plan_survey_sidelap), s.sidelapPct.roundToInt(), { actions.onSurveySettings(s.copy(sidelapPct = it.toFloat())) }, 0..95, dec, inc, step = 5, format = pct)
            NumberSettingRow(stringResource(R.string.plan_survey_fov), s.cameraFovDeg.roundToInt(), { actions.onSurveySettings(s.copy(cameraFovDeg = it.toFloat())) }, 10..170, dec, inc, format = deg)
            NumberSettingRow(stringResource(R.string.plan_survey_angle), s.angleDeg.roundToInt(), { actions.onSurveySettings(s.copy(angleDeg = it.toFloat())) }, 0..179, dec, inc, step = 5, format = deg)
        }
        SurveyMode.Spray -> NumberSettingRow(stringResource(R.string.plan_survey_swath), s.swathM.roundToInt(), { actions.onSurveySettings(s.copy(swathM = it.toFloat())) }, 1..50, dec, inc, format = m)
    }
    val preview = state.surveyPreview
    val stats = when {
        state.survey.size < 3 -> stringResource(R.string.plan_survey_need_points)
        preview == null -> stringResource(R.string.plan_msg_survey_empty)
        preview.photoIntervalM != null && preview.areaHa != null && preview.flightTimeS != null -> stringResource(
            R.string.plan_survey_stats_camera,
            pluralStringResource(R.plurals.plan_survey_lines, preview.lineCount, preview.lineCount),
            preview.photoIntervalM,
            preview.areaHa,
            (preview.flightTimeS + 59) / 60,
        )
        else -> stringResource(
            R.string.plan_pair_label,
            pluralStringResource(R.plurals.plan_survey_lines, preview.lineCount, preview.lineCount),
            pluralStringResource(R.plurals.plan_survey_waypoints, preview.path.size, preview.path.size),
        )
    }
    Text(stats, style = MaterialTheme.typography.bodyMedium, color = colors.hudInk)
    Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        AdosButton(stringResource(R.string.plan_survey_apply), onClick = actions.onApplySurvey, enabled = preview != null)
        AdosButton(stringResource(R.string.plan_survey_clear), onClick = actions.onClearSurvey, variant = AdosButtonVariant.Secondary, enabled = state.survey.isNotEmpty())
    }
}

@Composable
private fun InclusionSelector(inclusion: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(true, false)
    Column {
        Text(stringResource(R.string.plan_fence_type), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, value ->
                SegmentedButton(
                    selected = value == inclusion,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(stringResource(if (value) R.string.plan_fence_inclusion else R.string.plan_fence_exclusion)) },
                )
            }
        }
    }
}

@Composable
private fun FenceToolPanel(state: PlannerUiState, actions: PlannerActions) {
    SectionHeader(stringResource(R.string.plan_fence_title))
    InclusionSelector(state.fenceDraftInclusion, actions.onFenceDraftInclusion)
    if (state.tool == EditTool.FencePolygon) {
        Text(
            pluralStringResource(R.plurals.plan_fence_corners, state.fenceDraft.size, state.fenceDraft.size),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.hudInk,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(stringResource(R.string.plan_fence_close), onClick = actions.onCloseFence, enabled = state.fenceDraft.size >= 3)
            AdosButton(stringResource(R.string.plan_fence_discard), onClick = actions.onDiscardFence, variant = AdosButtonVariant.Secondary, enabled = state.fenceDraft.isNotEmpty())
        }
    }
    state.plan.fence.forEach { zone ->
        val label = when (zone) {
            is FenceZone.Polygon -> stringResource(
                R.string.plan_fence_polygon_summary,
                pluralStringResource(R.plurals.plan_fence_corners, zone.points.size, zone.points.size),
            )
            is FenceZone.Circle -> stringResource(R.string.plan_fence_circle_summary)
        }
        val kind = stringResource(if (zone.inclusion) R.string.plan_fence_inclusion else R.string.plan_fence_exclusion)
        AdosButton(stringResource(R.string.plan_pair_label, label, kind), onClick = { actions.onSelect(Selection.Fence(zone.id)) }, variant = AdosButtonVariant.Secondary)
    }
}

@Composable
private fun FenceEditor(zone: FenceZone, actions: PlannerActions) {
    EditorHeader(stringResource(R.string.plan_fence_title)) { actions.onSelect(null) }
    InclusionSelector(zone.inclusion) { if (it != zone.inclusion) actions.onToggleFenceInclusion(zone.id) }
    if (zone is FenceZone.Circle) {
        NumberSettingRow(
            title = stringResource(R.string.plan_fence_radius),
            value = zone.radiusM.roundToInt(),
            onValueChange = { actions.onFenceRadius(zone.id, it.toDouble()) },
            range = PlannerViewModel.MIN_FENCE_RADIUS_M.toInt()..50_000,
            step = 10,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = metresFormatter(),
        )
    }
    AdosButton(stringResource(R.string.plan_fence_delete), onClick = { actions.onDeleteFence(zone.id) }, variant = AdosButtonVariant.Danger)
}

// ── Transfer and start ─────────────────────────────────────

@Composable
private fun missionTypeLabel(type: MissionType) = stringResource(
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
}

@Composable
private fun TransferBar(state: PlannerUiState, actions: PlannerActions) {
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
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.statusWarning)
        if (connected != null) {
            StatusChip(
                level = if (state.inSyncWithVehicle) StatusLevel.Success else StatusLevel.Neutral,
                label = stringResource(if (state.inSyncWithVehicle) R.string.plan_in_sync else R.string.plan_not_in_sync),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            AdosButton(stringResource(R.string.plan_upload), onClick = actions.onUpload, enabled = canTransfer && !state.plan.isEmpty, icon = Icons.Outlined.CloudUpload)
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
    ConfirmSheet(
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

// ── Altitude profile ───────────────────────────────────────

@Composable
private fun AltitudeProfile(state: PlannerUiState) {
    val colors = AdosTheme.colors
    val profile = state.plan.altitudeProfile()
    if (profile.size < 2) {
        Text(stringResource(R.string.plan_profile_empty), style = MaterialTheme.typography.bodySmall, color = colors.hudInk2)
        return
    }
    val maxAlt = profile.maxOf { it.altM }
    val minAlt = minOf(0f, profile.minOf { it.altM })
    val description = pluralStringResource(R.plurals.plan_profile_description, profile.size, profile.size, maxAlt.roundToInt(), profile.minOf { it.altM }.roundToInt())
    val selected = (state.selection as? Selection.Item)?.id
    val line = colors.accentPrimary
    val grid = colors.hudHair
    val mark = colors.fgPrimary
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = description },
    ) {
        val total = profile.last().distanceM.coerceAtLeast(1.0)
        val span = (maxAlt - minAlt).coerceAtLeast(1f)
        fun x(d: Double) = (d / total * size.width).toFloat()
        fun y(a: Float) = size.height - (a - minAlt) / span * (size.height - 8f) - 4f
        drawLine(grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1f)
        val path = Path()
        profile.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.distanceM), y(p.altM)) else path.lineTo(x(p.distanceM), y(p.altM)) }
        drawPath(path, line, style = Stroke(width = 3f))
        profile.forEach { p ->
            drawCircle(if (p.itemId == selected) mark else line, radius = if (p.itemId == selected) 7f else 4f, center = Offset(x(p.distanceM), y(p.altM)))
        }
    }
}

// ── Messages ───────────────────────────────────────────────

@Composable
internal fun messageText(message: PlannerMessage): String = when (message) {
    is PlannerMessage.Saved -> stringResource(R.string.plan_msg_saved, message.name)
    is PlannerMessage.Loaded -> stringResource(R.string.plan_msg_loaded, message.name)
    PlannerMessage.Deleted -> stringResource(R.string.plan_msg_deleted)
    is PlannerMessage.Imported -> if (message.warnings == 0) {
        stringResource(R.string.plan_msg_imported, message.name)
    } else {
        pluralStringResource(R.plurals.plan_msg_imported_warnings, message.warnings, message.name, message.warnings)
    }
    is PlannerMessage.ImportFailed -> stringResource(
        R.string.plan_msg_import_failed,
        when (message.reason) {
            PlanFormatException.Reason.NotWaypoints -> stringResource(R.string.plan_msg_import_not_waypoints)
            PlanFormatException.Reason.NotPlan -> stringResource(R.string.plan_msg_import_not_plan)
            PlanFormatException.Reason.Malformed -> stringResource(R.string.plan_msg_import_malformed)
            PlanFormatException.Reason.ComplexWithoutGeometry -> stringResource(R.string.plan_msg_import_complex_geometry)
            PlanFormatException.Reason.ComplexWithoutAltitude -> stringResource(R.string.plan_msg_import_complex_altitude)
            null -> message.detail.orEmpty()
        },
    )
    is PlannerMessage.Exported -> stringResource(R.string.plan_msg_exported, message.format.extension)
    is PlannerMessage.ExportFailed -> stringResource(R.string.plan_msg_export_failed, message.detail)
    PlannerMessage.SurveyEmpty -> stringResource(R.string.plan_msg_survey_empty)
    PlannerMessage.VehicleHasNoMission -> stringResource(R.string.plan_msg_vehicle_empty)
    is PlannerMessage.DownloadPartial -> stringResource(R.string.plan_msg_download_partial, missionTypeLabel(message.failed))
}
