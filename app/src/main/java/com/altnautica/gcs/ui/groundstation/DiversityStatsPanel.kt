package com.altnautica.gcs.ui.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

data class AdapterRssi(
    val name: String,
    val rssiDbm: Int,
)

/**
 * Shows per-adapter RSSI bars when diversity reception is active (2+ adapters).
 * Only visible when adapterCount > 1. Follows the SignalMeter pattern for
 * individual bar rendering.
 */
@Composable
fun DiversityStatsPanel(
    adapters: List<AdapterRssi>,
    modifier: Modifier = Modifier,
) {
    if (adapters.size < 2) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Diversity Reception",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "${adapters.size} adapters active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            adapters.forEach { adapter ->
                AdapterRssiBar(adapter)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun AdapterRssiBar(adapter: AdapterRssi) {
    val normalized = ((adapter.rssiDbm + 100).coerceIn(0, 80)) / 80f
    val color = when {
        adapter.rssiDbm > -60 -> SuccessGreen
        adapter.rssiDbm > -75 -> WarningAmber
        else -> ErrorRed
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = adapter.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${adapter.rssiDbm} dBm",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { normalized },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
    }
}
