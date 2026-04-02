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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
fun AccelCalibrationWizard(
    viewModel: CalibrationViewModel,
    modifier: Modifier = Modifier,
) {
    val accelState by viewModel.accelState.collectAsStateWithLifecycle()
    val statusMessages by viewModel.statusMessages.collectAsStateWithLifecycle()

    // Watch status messages for calibration progress keywords
    LaunchedEffect(statusMessages) {
        val last = statusMessages.lastOrNull() ?: return@LaunchedEffect
        viewModel.onAccelCalStatusMessage(last)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val state = accelState) {
            is AccelCalState.Idle -> {
                IdleContent(onStart = { viewModel.startAccelCal() })
            }

            is AccelCalState.InProgress -> {
                InProgressContent(
                    step = state.step,
                    onNextPosition = { viewModel.advanceAccelStep() },
                )
            }

            is AccelCalState.Complete -> {
                CompleteContent(onReset = { viewModel.resetAccelCal() })
            }

            is AccelCalState.Failed -> {
                FailedContent(
                    message = state.message,
                    onRetry = { viewModel.startAccelCal() },
                    onReset = { viewModel.resetAccelCal() },
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit) {
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
                text = "Accelerometer Calibration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This calibration requires placing the drone in 6 different positions. " +
                    "You will need a flat surface and the ability to hold the drone steady in each orientation.",
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
private fun InProgressContent(
    step: Int,
    onNextPosition: () -> Unit,
) {
    val steps = CalibrationViewModel.ACCEL_STEPS
    val (stepName, stepInstruction) = steps[step]

    // Progress bar
    LinearProgressIndicator(
        progress = { (step + 1).toFloat() / steps.size },
        modifier = Modifier.fillMaxWidth(),
        color = ElectricBlue,
        trackColor = SurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Step ${step + 1} of ${steps.size}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))

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
                text = stepName,
                style = MaterialTheme.typography.titleLarge,
                color = ElectricBlue,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stepInstruction,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onNextPosition,
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.fillMaxWidth(0.6f),
    ) {
        Text(if (step < CalibrationViewModel.ACCEL_STEPS.size - 1) "Next Position" else "Finish")
    }
}

@Composable
private fun CompleteContent(onReset: () -> Unit) {
    Spacer(Modifier.height(48.dp))

    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = "Complete",
        tint = SuccessGreen,
        modifier = Modifier.size(64.dp),
    )

    Spacer(Modifier.height(16.dp))

    Text(
        text = "Calibration Complete!",
        style = MaterialTheme.typography.titleLarge,
        color = SuccessGreen,
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Accelerometer has been calibrated successfully.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(24.dp))

    OutlinedButton(onClick = onReset) {
        Text("Done")
    }
}

@Composable
private fun FailedContent(
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
