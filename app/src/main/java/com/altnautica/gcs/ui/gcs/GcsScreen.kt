package com.altnautica.gcs.ui.gcs

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.settings.SettingsViewModel
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.WarningAmber
import com.altnautica.gcs.ui.video.VideoViewModel

@Composable
fun MapScreen(
    onBack: () -> Unit,
    viewModel: GcsViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    videoViewModel: VideoViewModel = hiltViewModel(),
) {
    val flightMode by viewModel.flightMode.collectAsStateWithLifecycle()
    val armed by viewModel.armed.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val homePosition by viewModel.homePosition.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val gps by viewModel.gps.collectAsStateWithLifecycle()
    val confirmAction by viewModel.confirmAction.collectAsStateWithLifecycle()
    val mapProvider by settingsViewModel.mapProvider.collectAsStateWithLifecycle()
    val videoMode by videoViewModel.videoMode.collectAsStateWithLifecycle()

    val isCloudMode = videoMode is VideoMode.CloudRelay

    Box(Modifier.fillMaxSize()) {
        // Full-screen map
        DroneMapView(
            dronePosition = position,
            homePosition = homePosition,
            useMapbox = shouldUseMapbox(mapProvider),
            modifier = Modifier.fillMaxSize(),
        )

        // Back button (top-left)
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

        // Flight mode selector overlay (top-right)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = DeepBlack.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            FlightModeSelector(
                currentMode = flightMode,
                onModeSelected = { if (!isCloudMode) viewModel.requestSetMode(it) },
                enabled = !isCloudMode,
            )
        }

        // Compact telemetry overlay (bottom)
        Surface(
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
            color = DeepBlack.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Battery
                val battColor = when {
                    battery.remaining < 0 -> OnSurfaceMedium
                    battery.remaining <= 20 -> ErrorRed
                    battery.remaining <= 40 -> WarningAmber
                    else -> SuccessGreen
                }
                CompactStat("BAT", if (battery.remaining >= 0) "${battery.remaining}%" else "--%", battColor)

                // GPS
                val gpsColor = when (gps.fixType) {
                    0, 1 -> ErrorRed
                    2 -> WarningAmber
                    else -> SuccessGreen
                }
                CompactStat("GPS", "${gps.satellites} sats", gpsColor)

                // Altitude
                CompactStat("ALT", "%.1fm".format(position.altRel), OnSurfaceMedium)

                // Armed status
                val armedColor = if (armed) SuccessGreen else OnSurfaceMedium
                CompactStat("", if (armed) "ARMED" else "DISARMED", armedColor)

                // Flight mode
                CompactStat("MODE", flightMode?.label ?: "---", Color.White)
            }
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

@Composable
private fun CompactStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceMedium,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}
