package com.altnautica.gcs.feature.plan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.survey.SurveySettings

/**
 * Mission planner destination.
 *
 * @param nodeId vehicle to plan for; null lets the operator pick one in the screen.
 * @param onStartMission called with the vehicle id once "Start mission" has been confirmed
 *   and the vehicle accepted it; the host navigates to that vehicle's cockpit.
 */
@Composable
fun PlanRoute(
    nodeId: DeviceId?,
    onStartMission: (DeviceId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlannerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startCallback by rememberUpdatedState(onStartMission)

    LaunchedEffect(nodeId) {
        if (nodeId != null) viewModel.selectVehicle(nodeId)
    }
    LaunchedEffect(state.missionStartedFor) {
        val started = state.missionStartedFor ?: return@LaunchedEffect
        viewModel.onMissionStartHandled()
        startCallback(started)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importDocument(uri)
    }
    val exportPlanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PlanFileFormat.QgcPlan.mimeType)) { uri ->
        if (uri != null) viewModel.exportDocument(uri, PlanFileFormat.QgcPlan)
    }
    val exportWaypointsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PlanFileFormat.Waypoints.mimeType)) { uri ->
        if (uri != null) viewModel.exportDocument(uri, PlanFileFormat.Waypoints)
    }

    val actions = remember(viewModel) {
        PlannerActions(
            onSelectVehicle = viewModel::selectVehicle,
            onTool = viewModel::setTool,
            onMapTap = viewModel::onMapTap,
            onFeatureClick = { ref -> ref.toSelection()?.let(viewModel::select) },
            onFeatureMoved = { ref, point -> viewModel.onFeatureMoved(ref, point) },
            onSelect = viewModel::select,
            onInsertNear = viewModel::insertNear,
            onInsertCommandAfter = viewModel::insertCommandAfter,
            onDeleteItem = viewModel::deleteItem,
            onSetCommand = viewModel::setCommand,
            onSetAltitude = viewModel::setAltitude,
            onSetAltitudeRef = viewModel::setAltitudeRef,
            onSetSpeed = viewModel::setSpeed,
            onSetParam = viewModel::setParam,
            onDefaultAltitude = viewModel::setDefaultAltitude,
            onDefaultRef = viewModel::setDefaultRef,
            onBasemap = viewModel::setBasemap,
            onSurveySettings = viewModel::setSurveySettings,
            onApplySurvey = viewModel::applySurvey,
            onClearSurvey = viewModel::clearSurvey,
            onFenceDraftInclusion = viewModel::setFenceDraftInclusion,
            onCloseFence = viewModel::closeFenceDraft,
            onDiscardFence = viewModel::discardFenceDraft,
            onFenceRadius = viewModel::setFenceRadius,
            onToggleFenceInclusion = viewModel::toggleFenceInclusion,
            onDeleteFence = viewModel::deleteFence,
            onRallyAltitude = viewModel::setRallyAltitude,
            onDeleteRally = viewModel::deleteRally,
            onName = viewModel::setName,
            onNew = viewModel::newPlan,
            onSave = viewModel::save,
            onLoad = viewModel::load,
            onDeleteSaved = viewModel::deleteSaved,
            onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
            onExport = { format, name ->
                val file = "${name.ifBlank { "mission" }}.${format.extension}"
                when (format) {
                    PlanFileFormat.QgcPlan -> exportPlanLauncher.launch(file)
                    PlanFileFormat.Waypoints -> exportWaypointsLauncher.launch(file)
                }
            },
            onUpload = viewModel::upload,
            onDownload = viewModel::download,
            onConfirmDownload = viewModel::confirmDownload,
            onCancelDownload = viewModel::cancelDownload,
            onDismissTransfer = viewModel::dismissTransfer,
            onOpenStart = viewModel::openStartMission,
            onOverride = viewModel::setChecklistOverride,
            onStart = viewModel::startMission,
            onDismissStart = viewModel::dismissStartMission,
            onMessageShown = viewModel::messageShown,
        )
    }
    PlannerScreen(state = state, actions = actions, modifier = modifier)
}

/** Operator actions on the planner; the screen stays stateless. */
class PlannerActions(
    val onSelectVehicle: (DeviceId?) -> Unit,
    val onTool: (EditTool) -> Unit,
    val onMapTap: (com.altnautica.gcs.core.map.GeoPoint) -> Unit,
    val onFeatureClick: (MapFeatureRef) -> Unit,
    val onFeatureMoved: (MapFeatureRef, com.altnautica.gcs.core.map.GeoPoint) -> Unit,
    val onSelect: (Selection?) -> Unit,
    val onInsertNear: (id: Long, after: Boolean) -> Unit,
    val onInsertCommandAfter: (id: Long, command: PlanCommand) -> Unit,
    val onDeleteItem: (Long) -> Unit,
    val onSetCommand: (Long, PlanCommand) -> Unit,
    val onSetAltitude: (Long, Float) -> Unit,
    val onSetAltitudeRef: (Long, AltitudeRef) -> Unit,
    val onSetSpeed: (Long, Float?) -> Unit,
    val onSetParam: (id: Long, index: Int, value: Float) -> Unit,
    val onDefaultAltitude: (Float) -> Unit,
    val onDefaultRef: (AltitudeRef) -> Unit,
    val onBasemap: (MapBasemap) -> Unit,
    val onSurveySettings: (SurveySettings) -> Unit,
    val onApplySurvey: () -> Unit,
    val onClearSurvey: () -> Unit,
    val onFenceDraftInclusion: (Boolean) -> Unit,
    val onCloseFence: () -> Unit,
    val onDiscardFence: () -> Unit,
    val onFenceRadius: (Long, Double) -> Unit,
    val onToggleFenceInclusion: (Long) -> Unit,
    val onDeleteFence: (Long) -> Unit,
    val onRallyAltitude: (Long, Float) -> Unit,
    val onDeleteRally: (Long) -> Unit,
    val onName: (String) -> Unit,
    val onNew: () -> Unit,
    val onSave: (defaultName: String) -> Unit,
    val onLoad: (String) -> Unit,
    val onDeleteSaved: (String) -> Unit,
    val onImport: () -> Unit,
    val onExport: (PlanFileFormat, name: String) -> Unit,
    val onUpload: () -> Unit,
    val onDownload: () -> Unit,
    val onConfirmDownload: () -> Unit,
    val onCancelDownload: () -> Unit,
    val onDismissTransfer: () -> Unit,
    val onOpenStart: () -> Unit,
    val onOverride: (Boolean) -> Unit,
    val onStart: () -> Unit,
    val onDismissStart: () -> Unit,
    val onMessageShown: () -> Unit,
)

private fun MapFeatureRef.toSelection(): Selection? = when (type) {
    MapFeatureType.Waypoint -> id.toLongOrNull()?.let { Selection.Item(it) }
    MapFeatureType.Rally -> id.toLongOrNull()?.let { Selection.Rally(it) }
    MapFeatureType.Handle -> when (val target = HandleTarget.parse(id)) {
        is HandleTarget.FenceVertex -> Selection.Fence(target.zoneId)
        is HandleTarget.FenceCenter -> Selection.Fence(target.zoneId)
        else -> null
    }
}
