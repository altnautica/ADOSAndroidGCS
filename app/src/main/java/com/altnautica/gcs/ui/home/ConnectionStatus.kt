package com.altnautica.gcs.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

@Composable
fun ConnectionStatus(
    videoMode: VideoMode,
    batteryPercent: Int,
    gpsFixType: Int,
    satellites: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Video mode badge
            val modeLabel = when (videoMode) {
                is VideoMode.GroundStation -> "A"
                is VideoMode.DirectUsb -> "B"
                is VideoMode.CloudRelay -> "C"
                is VideoMode.NoConnection -> "--"
            }
            val modeColor = when (videoMode) {
                is VideoMode.GroundStation -> ElectricBlue
                is VideoMode.DirectUsb -> SuccessGreen
                is VideoMode.CloudRelay -> WarningAmber
                is VideoMode.NoConnection -> ErrorRed
            }
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = modeColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = modeLabel,
                    color = modeColor,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }

            // Battery
            val battColor = when {
                batteryPercent < 0 -> OnSurfaceMedium
                batteryPercent <= 15 -> ErrorRed
                batteryPercent <= 30 -> WarningAmber
                else -> SuccessGreen
            }
            val battText = if (batteryPercent >= 0) "${batteryPercent}%" else "--%"
            Text(
                text = battText,
                color = battColor,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )

            // GPS fix
            val gpsColor = when (gpsFixType) {
                0, 1 -> ErrorRed
                2 -> WarningAmber
                else -> SuccessGreen
            }
            val gpsLabel = when (gpsFixType) {
                0, 1 -> "No Fix"
                2 -> "2D"
                3 -> "3D"
                else -> "RTK"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = gpsColor,
                    modifier = Modifier.size(8.dp),
                ) {}
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "$gpsLabel $satellites sats",
                    color = OnSurfaceMedium,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.weight(1f))

            // Connection dot
            val connected = videoMode !is VideoMode.NoConnection
            Surface(
                shape = CircleShape,
                color = if (connected) SuccessGreen else ErrorRed,
                modifier = Modifier.size(8.dp),
            ) {}
        }
    }
}
