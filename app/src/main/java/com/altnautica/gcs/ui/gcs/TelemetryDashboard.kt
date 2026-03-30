package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelemetryDashboard(viewModel: GcsViewModel) {
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val gps by viewModel.gps.collectAsStateWithLifecycle()
    val sysStatus by viewModel.sysStatus.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()

    Text(
        text = "Telemetry",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Battery card
        val battPct = battery.remaining
        val battColor = when {
            battPct < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
            battPct <= 20 -> ErrorRed
            battPct <= 40 -> WarningAmber
            else -> SuccessGreen
        }
        TelemetryCard(
            label = "Battery",
            value = if (battPct >= 0) "${battPct}%" else "--%",
            detail = "%.1fV  %.1fA".format(battery.voltage, battery.current),
            valueColor = battColor,
        )

        // GPS card
        val gpsColor = when (gps.fixType) {
            0, 1 -> ErrorRed
            2 -> WarningAmber
            else -> SuccessGreen
        }
        val fixLabel = when (gps.fixType) {
            0, 1 -> "No Fix"
            2 -> "2D"
            3 -> "3D"
            4 -> "DGPS"
            5 -> "RTK Float"
            6 -> "RTK Fix"
            else -> "Fix:${gps.fixType}"
        }
        TelemetryCard(
            label = "GPS",
            value = "${gps.satellites} sats",
            detail = "$fixLabel  HDOP:${gps.hdop / 100f}",
            valueColor = gpsColor,
        )

        // EKF Status (derived from SYS_STATUS sensors)
        val ekfHealthy = (sysStatus.sensorsHealth and 0x10000000L) != 0L
        TelemetryCard(
            label = "EKF",
            value = if (ekfHealthy) "OK" else "WARN",
            detail = "",
            valueColor = if (ekfHealthy) SuccessGreen else WarningAmber,
        )

        // Link quality
        val linkColor = when (connection.status) {
            ConnectionStatus.CONNECTED -> SuccessGreen
            ConnectionStatus.CONNECTING -> WarningAmber
            ConnectionStatus.LOST -> ErrorRed
            ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        TelemetryCard(
            label = "Link",
            value = connection.status.name,
            detail = connection.message,
            valueColor = linkColor,
        )

        // System load
        val loadPct = sysStatus.load / 10f
        val loadColor = when {
            loadPct > 90 -> ErrorRed
            loadPct > 70 -> WarningAmber
            else -> SuccessGreen
        }
        TelemetryCard(
            label = "CPU",
            value = "%.0f%%".format(loadPct),
            detail = "",
            valueColor = loadColor,
        )
    }
}

@Composable
private fun TelemetryCard(
    label: String,
    value: String,
    detail: String,
    valueColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                color = valueColor,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
