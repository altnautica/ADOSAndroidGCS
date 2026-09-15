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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.altnautica.gcs.R
import com.altnautica.gcs.ui.firmware.FirmwareUpdateDialog
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

@Composable
fun GroundStationScreen(
    onBack: () -> Unit = {},
    viewModel: GroundStationViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val recordingStartTime by viewModel.recordingStartTime.collectAsStateWithLifecycle()
    val systemInfo by viewModel.systemInfo.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val restarting by viewModel.restarting.collectAsStateWithLifecycle()
    var confirmRestart by remember { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = stringResource(R.string.station_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val statusColor = if (stats.connected) SuccessGreen else ErrorRed
            val statusText = if (stats.connected) {
                stringResource(R.string.station_connected)
            } else {
                stringResource(R.string.station_disconnected)
            }
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

        // Operator notice. The ViewModel has published one since it was
        // written; nothing rendered it, so every recording, camera and pairing
        // failure was silent.
        notice?.let { res ->
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = WarningAmber.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(res),
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            LaunchedEffect(res) {
                delay(6000)
                viewModel.consumeNotice()
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
            StatCard(
                stringResource(R.string.station_packet_loss),
                "%.1f%%".format(stats.packetLossPercent),
                Modifier.weight(1f),
            )
            StatCard(
                stringResource(R.string.station_fec_recovered),
                "${stats.fecRecovered}",
                Modifier.weight(1f),
            )
            StatCard(
                stringResource(R.string.station_bitrate),
                "%.1f Mbps".format(stats.bitrateKbps / 1000f),
                Modifier.weight(1f),
            )
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
                    text = stringResource(R.string.station_recording),
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
                        Icon(
                            Icons.Filled.Stop,
                            stringResource(R.string.station_recording_stop),
                            tint = ErrorRed,
                        )
                    }
                } else {
                    IconButton(onClick = { viewModel.startRecording() }) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            stringResource(R.string.station_recording_start),
                            tint = ErrorRed,
                        )
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
                    text = stringResource(R.string.station_system_info),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.station_hostname), systemInfo.hostname)
                InfoRow(stringResource(R.string.station_ip_address), systemInfo.ipAddress)
                val socTempColor = when {
                    systemInfo.cpuTempC >= 80 -> ErrorRed
                    systemInfo.cpuTempC >= 60 -> WarningAmber
                    else -> SuccessGreen
                }
                InfoRow(
                    label = stringResource(R.string.station_soc_temp),
                    value = "${systemInfo.cpuTempC}°C",
                    valueColor = socTempColor,
                )
                InfoRow(stringResource(R.string.station_uptime), systemInfo.uptime)
                InfoRow(stringResource(R.string.station_agent_version), systemInfo.wfbVersion)

                // Firmware update badge
                if (systemInfo.firmwareUpdateAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { viewModel.showFirmwareDialog() },
                        shape = RoundedCornerShape(6.dp),
                        color = NeonLime.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = stringResource(R.string.station_update_available),
                            color = NeonLime,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Node service restart: the recovery action for a wedged radio, video
        // or MAVLink service. The agent serves no OS-reboot route, so this is
        // the strongest action reachable from here and is labelled as what it
        // does rather than as a reboot.
        OutlinedButton(
            onClick = { confirmRestart = true },
            enabled = !restarting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(stringResource(R.string.station_restart_action), color = WarningAmber)
        }

        Spacer(Modifier.height(16.dp))
    }

    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text(stringResource(R.string.station_restart_confirm_title)) },
            text = { Text(stringResource(R.string.station_restart_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestart = false
                        viewModel.restartAgentServices()
                    },
                ) {
                    Text(stringResource(R.string.station_restart_action), color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Firmware advisory dialog
    val firmwareUpdate by viewModel.firmwareUpdate.collectAsStateWithLifecycle()
    val firmwareState by viewModel.firmwareState.collectAsStateWithLifecycle()
    val firmwareError by viewModel.firmwareError.collectAsStateWithLifecycle()
    val showFirmwareDialog by viewModel.showFirmwareDialog.collectAsStateWithLifecycle()

    if (showFirmwareDialog && firmwareUpdate != null) {
        FirmwareUpdateDialog(
            update = firmwareUpdate!!,
            state = firmwareState,
            error = firmwareError,
            applyInstructionRes = viewModel.firmwareApplyInstructionRes,
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
