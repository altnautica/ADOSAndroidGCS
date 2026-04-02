package com.altnautica.gcs.ui.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.altnautica.gcs.data.firmware.FirmwareManager
import com.altnautica.gcs.data.firmware.FirmwareState
import com.altnautica.gcs.ui.firmware.FirmwareUpdateDialog
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

@Composable
fun GroundStationScreen(viewModel: GroundStationViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val recordingStartTime by viewModel.recordingStartTime.collectAsStateWithLifecycle()
    val systemInfo by viewModel.systemInfo.collectAsStateWithLifecycle()

    // Recording duration timer
    var recordingElapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording, recordingStartTime) {
        if (recording && recordingStartTime > 0L) {
            while (true) {
                recordingElapsed = (System.currentTimeMillis() - recordingStartTime) / 1000L
                delay(1000)
            }
        } else {
            recordingElapsed = 0L
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Status header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ground Station",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val statusColor = if (stats.connected) SuccessGreen else ErrorRed
            val statusText = if (stats.connected) "Connected" else "Disconnected"
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Signal meter
        SignalMeter(rssi = stats.rssiDbm, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))

        // Diversity stats (only visible with 2+ adapters)
        DiversityStatsPanel(
            adapters = stats.adapterRssiList,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // Stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatCard("Packet Loss", "%.1f%%".format(stats.packetLossPercent), Modifier.weight(1f))
            StatCard("FEC Recovered", "${stats.fecRecovered}", Modifier.weight(1f))
            StatCard("Bitrate", "%.1f Mbps".format(stats.bitrateKbps / 1000f), Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        // Packet loss graph
        PacketLossGraph(
            currentPacketLoss = stats.packetLossPercent,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // Recording controls
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (recording) {
                    val minutes = recordingElapsed / 60
                    val seconds = recordingElapsed % 60
                    Text(
                        text = "REC %02d:%02d".format(minutes, seconds),
                        color = ErrorRed,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { viewModel.stopRecording() }) {
                        Icon(Icons.Filled.Stop, "Stop recording", tint = ErrorRed)
                    }
                } else {
                    IconButton(onClick = { viewModel.startRecording() }) {
                        Icon(Icons.Filled.FiberManualRecord, "Start recording", tint = ErrorRed)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // System info
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "System Info",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("Hostname", systemInfo.hostname)
                InfoRow("IP Address", systemInfo.ipAddress)
                val socTempColor = when {
                    systemInfo.cpuTempC >= 80 -> ErrorRed
                    systemInfo.cpuTempC >= 60 -> WarningAmber
                    else -> SuccessGreen
                }
                InfoRow(
                    label = "SoC Temp",
                    value = "${systemInfo.cpuTempC}°C",
                    valueColor = socTempColor,
                )
                InfoRow("Uptime", systemInfo.uptime)
                InfoRow("WFB-ng Version", systemInfo.wfbVersion)

                // Firmware update badge
                if (systemInfo.firmwareUpdateAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { viewModel.showFirmwareDialog() },
                        shape = RoundedCornerShape(6.dp),
                        color = NeonLime.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = "Update Available",
                            color = NeonLime,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // Firmware update dialog
    val firmwareUpdate by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val firmwareState by viewModel.firmwareState.collectAsStateWithLifecycle()
    val firmwareProgress by viewModel.firmwareProgress.collectAsStateWithLifecycle()
    val firmwareError by viewModel.firmwareError.collectAsStateWithLifecycle()
    val showFirmwareDialog by viewModel.showFirmwareDialog.collectAsStateWithLifecycle()

    if (showFirmwareDialog && firmwareUpdate != null) {
        FirmwareUpdateDialog(
            update = firmwareUpdate!!,
            state = firmwareState,
            progress = firmwareProgress,
            error = firmwareError,
            onStartUpdate = { viewModel.startFirmwareUpdate() },
            onDismiss = { viewModel.dismissFirmwareDialog() },
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (valueColor != Color.Unspecified) valueColor
                else MaterialTheme.colorScheme.onSurface,
        )
    }
}
