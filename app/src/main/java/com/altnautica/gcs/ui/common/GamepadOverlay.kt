package com.altnautica.gcs.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen

/**
 * Small overlay shown in a corner when a gamepad is connected.
 * Displays controller icon, connection status, and small crosshair
 * indicators for current stick positions.
 */
@Composable
fun GamepadOverlay(
    state: GamepadState,
    modifier: Modifier = Modifier,
) {
    if (!state.connected) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = DeepBlack.copy(alpha = 0.85f),
        modifier = modifier.padding(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Gamepad icon + status
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Gamepad,
                    contentDescription = "Gamepad connected",
                    tint = SuccessGreen,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.labelSmall,
                    color = SuccessGreen,
                )
            }

            Spacer(Modifier.width(4.dp))

            // Left stick crosshair (throttle/yaw)
            StickIndicator(
                label = "L",
                normalizedX = state.r.toFloat() / 1000f,
                normalizedY = -state.z.toFloat() / 1000f, // Invert Y for display (up = up)
            )

            // Right stick crosshair (pitch/roll)
            StickIndicator(
                label = "R",
                normalizedX = state.y.toFloat() / 1000f,
                normalizedY = -state.x.toFloat() / 1000f,
            )
        }
    }
}

@Composable
private fun StickIndicator(
    label: String,
    normalizedX: Float, // -1 to 1
    normalizedY: Float, // -1 to 1
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMedium,
        )
        Canvas(modifier = Modifier.size(32.dp)) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val radius = size.width / 2

            // Background circle
            drawCircle(
                color = Color.White.copy(alpha = 0.1f),
                radius = radius,
                center = Offset(centerX, centerY),
            )

            // Crosshair lines
            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1f,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.2f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = 1f,
            )

            // Stick position dot
            val dotX = centerX + normalizedX * (radius - 4)
            val dotY = centerY + normalizedY * (radius - 4)
            drawCircle(
                color = ElectricBlue,
                radius = 4f,
                center = Offset(dotX, dotY),
            )
        }
    }
}
