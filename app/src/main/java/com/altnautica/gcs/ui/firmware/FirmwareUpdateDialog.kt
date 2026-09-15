package com.altnautica.gcs.ui.firmware

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.R
import com.altnautica.gcs.data.firmware.FirmwareState
import com.altnautica.gcs.data.firmware.FirmwareUpdate
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.WarningAmber

/**
 * Reports an available ground-station release and where to apply it.
 *
 * Deliberately has no install button. The agent serves no OTA route, so an
 * install action here could only fake progress and then fail — see
 * [com.altnautica.gcs.data.firmware.FirmwareManager] for the named gap and
 * what closing it needs.
 */
@Composable
fun FirmwareUpdateDialog(
    update: FirmwareUpdate,
    state: FirmwareState,
    error: String?,
    applyInstructionRes: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (state) {
                    FirmwareState.ERROR -> stringResource(R.string.firmware_check_failed)
                    else -> stringResource(R.string.firmware_update_available)
                },
            )
        },
        text = {
            Column {
                Text(
                    text = "${update.currentVersion}  ->  ${update.newVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = ElectricBlue,
                )

                if (update.sizeMb > 0) {
                    Text(
                        text = stringResource(
                            R.string.firmware_size_mb,
                            "%.1f".format(update.sizeMb),
                        ),
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

                if (state == FirmwareState.ERROR) {
                    Text(
                        text = error ?: stringResource(R.string.firmware_check_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ErrorRed,
                    )
                } else {
                    Text(
                        text = stringResource(applyInstructionRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = WarningAmber,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
