package com.altnautica.gcs.ui.configure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun LevelCalibrationCard(
    viewModel: CalibrationViewModel,
    modifier: Modifier = Modifier,
) {
    val levelState by viewModel.levelState.collectAsStateWithLifecycle()
    val statusMessages by viewModel.statusMessages.collectAsStateWithLifecycle()

    // Watch status messages for level cal progress
    LaunchedEffect(statusMessages) {
        val last = statusMessages.lastOrNull() ?: return@LaunchedEffect
        viewModel.onLevelCalStatusMessage(last)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Level Calibration",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.height(12.dp))

                when (val state = levelState) {
                    is LevelCalState.Idle -> {
                        Text(
                            text = "Place the drone on a flat, level surface before calibrating. " +
                                "This sets the accelerometer's reference for horizontal flight.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.startLevelCal() },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        ) {
                            Text("Calibrate Level")
                        }
                    }

                    is LevelCalState.InProgress -> {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = ElectricBlue,
                            strokeWidth = 4.dp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Calibrating... keep the drone still.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is LevelCalState.Complete -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Complete",
                            tint = SuccessGreen,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Level calibration complete!",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SuccessGreen,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { viewModel.resetLevelCal() }) {
                            Text("Done")
                        }
                    }

                    is LevelCalState.Failed -> {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = "Failed",
                            tint = ErrorRed,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ErrorRed,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.startLevelCal() },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
