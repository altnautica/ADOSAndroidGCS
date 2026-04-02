package com.altnautica.gcs.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.agriculture.Waypoint
import com.altnautica.gcs.data.mavlink.MavLinkMissionUploader
import com.altnautica.gcs.data.mavlink.UploadState
import com.altnautica.gcs.data.mission.MissionRepository
import com.altnautica.gcs.data.mission.SavedMission
import com.altnautica.gcs.data.mission.SurveyLatLng
import com.altnautica.gcs.data.mission.SurveyResult
import com.altnautica.gcs.data.mission.toMissionWaypoints
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.ui.gcs.DroneMapView
import com.altnautica.gcs.ui.gcs.MapLatLng
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MissionPlannerViewModel @Inject constructor(
    private val missionRepository: MissionRepository,
    private val missionUploader: MavLinkMissionUploader,
    private val telemetryStore: TelemetryStore,
) : ViewModel() {

    val uploadState: StateFlow<UploadState> = missionUploader.uploadState
    val uploadProgress: StateFlow<Float> = missionUploader.uploadProgress
    val position: StateFlow<PositionState> = telemetryStore.position
    val homePosition: StateFlow<PositionState?> = telemetryStore.homePosition
    val savedMissions: StateFlow<List<SavedMission>> = missionRepository.savedMissions

    init {
        viewModelScope.launch { missionRepository.loadMissions() }
    }

    fun uploadMission(waypoints: List<Waypoint>) {
        viewModelScope.launch { missionUploader.uploadMission(waypoints) }
    }

    fun saveMission(name: String, waypoints: List<Waypoint>) {
        viewModelScope.launch {
            missionRepository.saveMission(SavedMission(name, waypoints))
        }
    }

    fun resetUpload() {
        missionUploader.resetState()
    }
}

@Composable
fun MissionPlannerScreen(
    onBack: () -> Unit = {},
    viewModel: MissionPlannerViewModel = hiltViewModel(),
) {
    val position by viewModel.position.collectAsStateWithLifecycle()
    val homePosition by viewModel.homePosition.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()

    val waypoints = remember { mutableStateListOf<Waypoint>() }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var showEditor by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showSurveySheet by remember { mutableStateOf(false) }
    var surveyPreviewPolyline by remember { mutableStateOf<List<MapLatLng>>(emptyList()) }

    // Demo polygon for survey (4 corners around current drone position).
    // In production, this would come from user-drawn polygon on the map.
    val surveyPolygon = remember(position) {
        val lat = if (position.lat != 0.0) position.lat else 12.9716
        val lon = if (position.lon != 0.0) position.lon else 77.5946
        val offset = 0.002 // roughly 200m
        listOf(
            SurveyLatLng(lat - offset, lon - offset),
            SurveyLatLng(lat - offset, lon + offset),
            SurveyLatLng(lat + offset, lon + offset),
            SurveyLatLng(lat + offset, lon - offset),
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left: Map view (60%)
        Box(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxSize(),
        ) {
            DroneMapView(
                dronePosition = position,
                homePosition = homePosition,
                useMapbox = true,
                overlayPolyline = surveyPreviewPolyline,
                modifier = Modifier.fillMaxSize(),
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back to home",
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }

            // Add waypoint FAB
            FloatingActionButton(
                onClick = {
                    editingIndex = -1
                    showEditor = true
                },
                containerColor = ElectricBlue,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add waypoint")
            }
        }

        // Right: Waypoint list + controls (40%)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .padding(8.dp),
        ) {
            // Template buttons row
            Text(
                text = "Templates",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TemplateButton("Survey", Icons.Filled.GridView, Modifier.weight(1f)) {
                    showSurveySheet = true
                }
                TemplateButton("Corridor", Icons.Filled.LinearScale, Modifier.weight(1f))
                TemplateButton("Orbit", Icons.Filled.RadioButtonChecked, Modifier.weight(1f))
                TemplateButton("Custom", Icons.Filled.MyLocation, Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            // Waypoint header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Waypoints (${waypoints.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row {
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(
                            Icons.Filled.Save,
                            contentDescription = "Save mission",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Waypoint list
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(waypoints) { index, wp ->
                    WaypointRow(
                        index = index,
                        waypoint = wp,
                        onEdit = {
                            editingIndex = index
                            showEditor = true
                        },
                        onDelete = { waypoints.removeAt(index) },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Upload button
            Button(
                onClick = { viewModel.uploadMission(waypoints.toList()) },
                enabled = waypoints.isNotEmpty() && uploadState !is UploadState.Uploading,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Upload Mission")
            }

            // Upload status
            when (uploadState) {
                is UploadState.Uploading -> {
                    Text(
                        text = "Uploading... ${"%.0f".format(uploadProgress * 100)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricBlue,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                is UploadState.Complete -> {
                    Text(
                        text = "Upload complete",
                        style = MaterialTheme.typography.labelSmall,
                        color = SuccessGreen,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(3000)
                        viewModel.resetUpload()
                    }
                }
                is UploadState.Error -> {
                    Text(
                        text = "Error: ${(uploadState as UploadState.Error).message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorRed,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                else -> {}
            }
        }
    }

    // Waypoint editor dialog
    if (showEditor) {
        WaypointEditor(
            waypoint = if (editingIndex >= 0) waypoints[editingIndex] else null,
            onSave = { wp ->
                if (editingIndex >= 0) {
                    waypoints[editingIndex] = wp
                } else {
                    waypoints.add(wp)
                }
                showEditor = false
            },
            onDismiss = { showEditor = false },
        )
    }

    // Save mission dialog
    if (showSaveDialog) {
        var missionName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Mission") },
            text = {
                OutlinedTextField(
                    value = missionName,
                    onValueChange = { missionName = it },
                    label = { Text("Mission name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (missionName.isNotBlank()) {
                            viewModel.saveMission(missionName, waypoints.toList())
                            showSaveDialog = false
                        }
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    // Survey config bottom sheet
    if (showSurveySheet) {
        SurveyConfigSheet(
            polygon = surveyPolygon,
            onPreview = { result ->
                surveyPreviewPolyline = result.waypoints.map { MapLatLng(it.lat, it.lon) }
            },
            onUpload = { result ->
                // Replace manual waypoints with generated survey waypoints
                waypoints.clear()
                waypoints.addAll(result.toMissionWaypoints())
                surveyPreviewPolyline = emptyList()
                showSurveySheet = false
                viewModel.uploadMission(waypoints.toList())
            },
            onDismiss = {
                surveyPreviewPolyline = emptyList()
                showSurveySheet = false
            },
        )
    }
}

@Composable
private fun WaypointRow(
    index: Int,
    waypoint: Waypoint,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Index badge
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = ElectricBlue.copy(alpha = 0.2f),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricBlue,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.width(8.dp))

            // Lat/lon/alt/speed
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${"%.6f".format(waypoint.lat)}, ${"%.6f".format(waypoint.lon)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Alt: ${waypoint.alt.toInt()}m  Speed: ${"%.1f".format(waypoint.speed)}m/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = ErrorRed,
                )
            }
        }
    }
}

@Composable
private fun TemplateButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
