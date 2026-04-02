package com.altnautica.gcs.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun TakeoffDialog(
    onConfirm: (altitude: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var altitude by remember { mutableFloatStateOf(10f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // TODO: i18n - move to strings.xml
            Text("Takeoff")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // TODO: i18n - move to strings.xml
                Text(
                    text = "Select takeoff altitude",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))

                // Large altitude display
                Text(
                    text = "%.0f m".format(altitude),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = ElectricBlue,
                )

                Spacer(Modifier.height(12.dp))

                // Slider: 2m to 100m
                Slider(
                    value = altitude,
                    onValueChange = { altitude = it },
                    valueRange = 2f..100f,
                    steps = 97, // 1m increments (98 intervals, 97 steps between)
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricBlue,
                        activeTrackColor = ElectricBlue,
                        inactiveTrackColor = SurfaceVariant,
                    ),
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "2m - 100m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(altitude) }) {
                // TODO: i18n - move to strings.xml
                Text("Takeoff", color = ElectricBlue)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                // TODO: i18n - move to strings.xml
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
