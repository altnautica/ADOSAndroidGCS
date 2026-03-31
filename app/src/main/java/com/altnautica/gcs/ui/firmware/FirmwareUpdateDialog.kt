package com.altnautica.gcs.ui.firmware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.firmware.FirmwareState
import com.altnautica.gcs.data.firmware.FirmwareUpdate
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.WarningAmber

/**
 * AlertDialog for firmware OTA updates.
 *
 * Shows: current version -> new version, download progress, push progress,
 * "Do not power off" warning during active update, reboot countdown.
 */
@Composable
fun FirmwareUpdateDialog(
    update: FirmwareUpdate,
    state: FirmwareState,
    progress: Float,
    error: String?,
    onStartUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isActive = state in listOf(
        FirmwareState.DOWNLOADING,
        FirmwareState.PUSHING,
        FirmwareState.REBOOTING,
    )

    AlertDialog(
        onDismissRequest = { if (!isActive) onDismiss() },
        title = {
            Text(
                text = when (state) {
                    FirmwareState.COMPLETE -> "Update Complete"
                    FirmwareState.ERROR -> "Update Failed"
                    FirmwareState.REBOOTING -> "Rebooting..."
                    else -> "Firmware Update"
                },
            )
        },
        text = {
            Column {
                // Version info
                Text(
                    text = "${update.currentVersion}  ->  ${update.newVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = ElectricBlue,
                )

                if (update.sizeMb > 0) {
                    Text(
                        text = "Size: ${"%.1f".format(update.sizeMb)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (update.releaseNotes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = update.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Progress
                when (state) {
                    FirmwareState.DOWNLOADING -> {
                        Text(
                            text = "Downloading firmware...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = ElectricBlue,
                            trackColor = ElectricBlue.copy(alpha = 0.15f),
                        )
                    }
                    FirmwareState.PUSHING -> {
                        Text(
                            text = "Pushing to ground station...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = WarningAmber,
                            trackColor = WarningAmber.copy(alpha = 0.15f),
                        )
                    }
                    FirmwareState.REBOOTING -> {
                        Text(
                            text = "Ground station is rebooting...",
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningAmber,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = WarningAmber,
                            trackColor = WarningAmber.copy(alpha = 0.15f),
                        )
                    }
                    FirmwareState.COMPLETE -> {
                        Text(
                            text = "Update installed. Ground station will reconnect shortly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SuccessGreen,
                        )
                    }
                    FirmwareState.ERROR -> {
                        Text(
                            text = error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorRed,
                        )
                    }
                    else -> {
                        // IDLE or CHECKING - show install prompt
                    }
                }

                // Warning during active update
                if (isActive) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "DO NOT power off the ground station",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = ErrorRed,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                FirmwareState.IDLE, FirmwareState.CHECKING -> {
                    TextButton(onClick = onStartUpdate) {
                        Text("Install Update")
                    }
                }
                FirmwareState.COMPLETE, FirmwareState.ERROR -> {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                else -> {
                    // No button during active update
                }
            }
        },
        dismissButton = {
            if (!isActive && state != FirmwareState.COMPLETE) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
