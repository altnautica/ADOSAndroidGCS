package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.settings.SettingsViewModel
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.video.VideoViewModel

@Composable
private fun ArmDisarmButton(
    armed: Boolean,
    enabled: Boolean = true,
    onArmRequest: () -> Unit,
    onDisarmRequest: () -> Unit,
) {
    val color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else if (armed) ErrorRed else SuccessGreen
    val label = if (armed) "DISARM" else "ARM"

    Surface(
        onClick = { if (enabled) { if (armed) onDisarmRequest() else onArmRequest() } },
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        contentColor = color,
        border = BorderStroke(1.dp, color),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
        )
    }
}

@Composable
fun GcsScreen(
    viewModel: GcsViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    videoViewModel: VideoViewModel = hiltViewModel(),
) {
    val flightMode by viewModel.flightMode.collectAsStateWithLifecycle()
    val armed by viewModel.armed.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val homePosition by viewModel.homePosition.collectAsStateWithLifecycle()
    val confirmAction by viewModel.confirmAction.collectAsStateWithLifecycle()
    val mapProvider by settingsViewModel.mapProvider.collectAsStateWithLifecycle()
    val videoMode by videoViewModel.videoMode.collectAsStateWithLifecycle()

    val isCloudMode = videoMode is VideoMode.CloudRelay

    Row(Modifier.fillMaxSize()) {
        // Map panel (60% width)
        DroneMapView(
            dronePosition = position,
            homePosition = homePosition,
            useMapbox = shouldUseMapbox(mapProvider),
            modifier = Modifier
                .weight(0.6f)
                .fillMaxSize(),
        )

        // Controls panel (40% width)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            FlightModeSelector(
                currentMode = flightMode,
                onModeSelected = { if (!isCloudMode) viewModel.requestSetMode(it) },
                enabled = !isCloudMode,
            )
            Spacer(Modifier.height(8.dp))
            ArmDisarmButton(
                armed = armed,
                enabled = !isCloudMode,
                onArmRequest = { viewModel.requestArm() },
                onDisarmRequest = { viewModel.requestDisarm() },
            )
            if (isCloudMode) {
                Text(
                    text = "Controls disabled in cloud mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TelemetryDashboard(viewModel)
        }
    }

    // Confirmation dialog
    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelAction() },
            title = { Text("Confirm") },
            text = { Text(action.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmAction() }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelAction() }) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
