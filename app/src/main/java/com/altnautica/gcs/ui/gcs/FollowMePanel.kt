package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.followme.FollowAlgorithm
import com.altnautica.gcs.data.followme.FollowMeEngine
import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

private enum class AlgoOption(val label: String) {
    LEASH("Leash"),
    LEAD("Lead"),
    ORBIT("Orbit"),
    ABOVE("Above"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowMePanel(
    engine: FollowMeEngine,
    modeDetector: ModeDetector,
    onDismiss: () -> Unit,
) {
    val isActive by engine.isActive.collectAsStateWithLifecycle()
    val gpsAccuracy by engine.gpsAccuracy.collectAsStateWithLifecycle()

    var selectedAlgo by remember { mutableStateOf(AlgoOption.LEASH) }
    var altitudeOffset by remember { mutableFloatStateOf(15f) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Request a GPS fix when the panel opens so the accuracy indicator is live
    LaunchedEffect(Unit) {
        engine.requestSingleFix()
    }

    val currentVideoMode = modeDetector.detect()
    val isDirectUsb = currentVideoMode is VideoMode.DirectUsb
    val gpsOk = gpsAccuracy <= 10f && gpsAccuracy != Float.MAX_VALUE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepBlack,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Follow Me",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(16.dp))

            // GPS accuracy indicator
            GpsAccuracyRow(gpsAccuracy)

            Spacer(Modifier.height(12.dp))

            // Algorithm picker
            Text(
                text = "Algorithm",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceMedium,
            )
            Spacer(Modifier.height(4.dp))

            Column(Modifier.selectableGroup()) {
                AlgoOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedAlgo == option,
                                onClick = { if (!isActive) selectedAlgo = option },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedAlgo == option,
                            onClick = null,
                            enabled = !isActive,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = ElectricBlue,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isActive) OnSurfaceMedium
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Altitude offset slider
            Text(
                text = "Altitude Offset: ${altitudeOffset.toInt()}m",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceMedium,
            )
            Slider(
                value = altitudeOffset,
                onValueChange = { if (!isActive) altitudeOffset = it },
                valueRange = 10f..50f,
                steps = 7, // 10, 15, 20, 25, 30, 35, 40, 45, 50
                enabled = !isActive,
                colors = SliderDefaults.colors(
                    thumbColor = ElectricBlue,
                    activeTrackColor = ElectricBlue,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // Warnings
            if (isDirectUsb) {
                WarningRow("Only works in Mode A or C (ground station WiFi or cloud)")
            }
            if (!gpsOk && !isActive) {
                WarningRow("GPS accuracy too low (>${gpsAccuracy.toInt()}m). Move to open sky.")
            }

            Spacer(Modifier.height(16.dp))

            // Start / Stop button
            Button(
                onClick = {
                    if (isActive) {
                        engine.stop()
                    } else {
                        val algo = when (selectedAlgo) {
                            AlgoOption.LEASH -> FollowAlgorithm.Leash()
                            AlgoOption.LEAD -> FollowAlgorithm.Lead()
                            AlgoOption.ORBIT -> FollowAlgorithm.Orbit()
                            AlgoOption.ABOVE -> FollowAlgorithm.Above
                        }
                        engine.start(algo, altitudeOffset)
                    }
                },
                enabled = isActive || (gpsOk && !isDirectUsb),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) ErrorRed else SuccessGreen,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (isActive) "STOP FOLLOW ME" else "START FOLLOW ME",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GpsAccuracyRow(accuracy: Float) {
    val displayAccuracy = if (accuracy == Float.MAX_VALUE) "--" else "${accuracy.toInt()}m"
    val color = when {
        accuracy == Float.MAX_VALUE -> OnSurfaceMedium
        accuracy <= 5f -> SuccessGreen
        accuracy <= 10f -> WarningAmber
        else -> ErrorRed
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.GpsFixed,
                contentDescription = "GPS accuracy",
                tint = color,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Phone GPS Accuracy",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = displayAccuracy,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
    }
}

@Composable
private fun WarningRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = "Warning",
            tint = WarningAmber,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = WarningAmber,
        )
    }
}
