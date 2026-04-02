package com.altnautica.gcs.ui.configure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun CompassCalibrationWizard(
    viewModel: CalibrationViewModel,
    modifier: Modifier = Modifier,
) {
    val compassState by viewModel.compassState.collectAsStateWithLifecycle()
    val magCalProgress by viewModel.magCalProgress.collectAsStateWithLifecycle()
    val magCalReport by viewModel.magCalReport.collectAsStateWithLifecycle()

    // Route progress/report updates to the ViewModel
    LaunchedEffect(magCalProgress) {
        magCalProgress?.let { viewModel.onCompassProgress(it) }
    }

    LaunchedEffect(magCalReport) {
        magCalReport?.let { viewModel.onCompassReport(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val state = compassState) {
            is CompassCalState.Idle -> {
                CompassIdleContent(onStart = { viewModel.startCompassCal() })
            }

            is CompassCalState.InProgress -> {
                CompassInProgressContent(pct = state.pct)
            }

            is CompassCalState.Complete -> {
                CompassCompleteContent(onReset = { viewModel.resetCompassCal() })
            }

            is CompassCalState.Failed -> {
                CompassFailedContent(
                    message = state.message,
                    onRetry = { viewModel.startCompassCal() },
                    onReset = { viewModel.resetCompassCal() },
                )
            }
        }
    }
}

@Composable
private fun CompassIdleContent(onStart: () -> Unit) {
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
                text = "Compass Calibration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Move away from metal objects and magnetic sources. " +
                    "You will need to rotate the drone slowly through all orientations " +
                    "until the progress reaches 100%.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Text("Start Calibration")
            }
        }
    }
}

@Composable
private fun CompassInProgressContent(pct: Float) {
    Spacer(Modifier.height(32.dp))

    // Circular progress
    CircularProgressIndicator(
        progress = { pct / 100f },
        modifier = Modifier.size(120.dp),
        strokeWidth = 8.dp,
        color = ElectricBlue,
        trackColor = SurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "%.0f%%".format(pct),
        style = MaterialTheme.typography.headlineLarge,
        color = ElectricBlue,
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "Rotate the drone slowly in all axes",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Try to cover every possible orientation. " +
            "Rotate around the roll, pitch, and yaw axes with smooth, steady movements.",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfaceMedium,
    )
}

@Composable
private fun CompassCompleteContent(onReset: () -> Unit) {
    Spacer(Modifier.height(48.dp))

    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Complete",
        tint = SuccessGreen,
        modifier = Modifier.size(64.dp),
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "Compass Calibration Complete!",
        style = MaterialTheme.typography.titleLarge,
        color = SuccessGreen,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Compass offsets have been saved to the flight controller.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))

    OutlinedButton(onClick = onReset) {
        Text("Done")
    }
}

@Composable
private fun CompassFailedContent(
    message: String,
    onRetry: () -> Unit,
    onReset: () -> Unit,
) {
    Spacer(Modifier.height(48.dp))

    Icon(
        Icons.Filled.Error,
        contentDescription = "Failed",
        tint = ErrorRed,
        modifier = Modifier.size(64.dp),
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "Calibration Failed",
        style = MaterialTheme.typography.titleLarge,
        color = ErrorRed,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Try moving further from metal objects and magnetic interference sources.",
        style = MaterialTheme.typography.bodySmall,
        color = OnSurfaceMedium,
    )

    Spacer(Modifier.height(24.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onReset) {
            Text("Cancel")
        }
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        ) {
            Text("Retry")
        }
    }
}
