package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.survey.SurveyMode
import kotlin.math.roundToInt

/** Inspector body: tool hint, then the selection's editor or the active tool's settings. */
@Composable
internal fun PlannerPanel(state: PlannerUiState, actions: PlannerActions) {
    val colors = AdosTheme.colors
    Text(toolHint(state.tool), style = MaterialTheme.typography.bodySmall, color = colors.hudInk2)
    when (val sel = state.selection) {
        is Selection.Item -> state.plan.items.firstOrNull { it.id == sel.id }?.let { item ->
            ItemEditor(item, state.plan.items.indexOf(item) + 1, actions)
        }
        is Selection.Rally -> state.plan.rally.firstOrNull { it.id == sel.id }?.let { RallyEditor(it, actions) }
        is Selection.Fence -> state.plan.fence.firstOrNull { it.id == sel.id }?.let { FenceEditor(it, actions) }
        null -> when (state.tool) {
            EditTool.Survey -> SurveyPanel(state, actions)
            EditTool.FencePolygon, EditTool.FenceCircle -> FenceToolPanel(state, actions)
            else -> DefaultsPanel(state, actions)
        }
    }
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
            NumberSettingRow(stringResource(R.string.plan_survey_vfov), s.cameraVFovDeg.roundToInt(), { actions.onSurveySettings(s.copy(cameraVFovDeg = it.toFloat())) }, 10..170, dec, inc, format = deg)
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
    is PlannerMessage.ImportFailed -> importFailedText(LocalResources.current, message.reason)
    is PlannerMessage.Exported -> stringResource(R.string.plan_msg_exported, message.format.extension)
    PlannerMessage.ExportFailed -> stringResource(R.string.plan_msg_export_failed)
    PlannerMessage.SurveyEmpty -> stringResource(R.string.plan_msg_survey_empty)
    PlannerMessage.VehicleHasNoMission -> stringResource(R.string.plan_msg_vehicle_empty)
    PlannerMessage.TransferInProgress -> stringResource(R.string.plan_upload_in_progress)
    is PlannerMessage.DownloadPartial -> stringResource(R.string.plan_msg_download_partial, missionTypeLabel(message.failed))
}
