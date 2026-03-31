package com.altnautica.gcs.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.CameraRear
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SurfaceVariant

private data class CameraOption(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

private val cameras = listOf(
    CameraOption("cam0", "Forward", Icons.Filled.CameraFront),
    CameraOption("cam1", "Down", Icons.Filled.CameraRear),
    CameraOption("cam2", "Thermal", Icons.Filled.Thermostat),
)

@Composable
fun CameraSwitcher(
    activeCameraId: String,
    onSwitchCamera: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cameras.forEach { cam ->
            val isActive = cam.id == activeCameraId
            val bgColor = if (isActive) ElectricBlue.copy(alpha = 0.2f) else SurfaceVariant
            val contentColor = if (isActive) ElectricBlue
                else MaterialTheme.colorScheme.onSurfaceVariant

            Surface(
                onClick = { if (!isActive) onSwitchCamera(cam.id) },
                shape = RoundedCornerShape(8.dp),
                color = bgColor,
                contentColor = contentColor,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = cam.icon,
                        contentDescription = cam.label,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = cam.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
