package com.altnautica.gcs.ui.common

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.R
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.WarningAmber
import com.altnautica.gcs.ui.theme.isPortrait

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingControls(
    armed: Boolean,
    recording: Boolean,
    paused: Boolean = false,
    inAutoMode: Boolean = false,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRtl: () -> Unit,
    onToggleRecord: () -> Unit,
    onTakeoff: () -> Unit = {},
    onLand: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var showDisarmDialog by remember { mutableStateOf(false) }
    val portrait = isPortrait()

    val armButton: @Composable () -> Unit = {
        Surface(
            shape = CircleShape,
            color = if (armed) ErrorRed.copy(alpha = 0.9f) else SuccessGreen.copy(alpha = 0.9f),
            modifier = Modifier
                .size(if (portrait) 64.dp else 72.dp)
                .combinedClickable(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (armed) {
                            showDisarmDialog = true
                        }
                    },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (!armed) {
                            onArm()
                        }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(if (portrait) 64.dp else 72.dp)) {
                Text(
                    text = if (armed) stringResource(R.string.gcs_disarm) else stringResource(R.string.gcs_arm),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    val recordButton: @Composable () -> Unit = {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onToggleRecord()
            },
            shape = CircleShape,
            color = if (recording) ErrorRed.copy(alpha = 0.9f) else OnSurfaceMedium.copy(alpha = 0.5f),
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = if (recording) "Stop recording" else "Start recording",
                    tint = if (recording) Color.White else ErrorRed,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    val pauseButton: @Composable () -> Unit = {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (paused) onResume() else onPause()
            },
            shape = CircleShape,
            color = if (paused) NeonLime.copy(alpha = 0.9f) else WarningAmber.copy(alpha = 0.9f),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (paused) "Resume mission" else "Pause mission",
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    val takeoffButton: @Composable () -> Unit = {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onTakeoff()
            },
            shape = CircleShape,
            color = ElectricBlue.copy(alpha = 0.9f),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.FlightTakeoff,
                    contentDescription = "Takeoff",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    val landButton: @Composable () -> Unit = {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLand()
            },
            shape = CircleShape,
            color = WarningAmber.copy(alpha = 0.9f),
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.FlightLand,
                    contentDescription = "Land",
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    val rtlButton: @Composable () -> Unit = {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onRtl()
            },
            shape = CircleShape,
            color = ErrorRed.copy(alpha = 0.9f),
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = "Return to launch",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "RTL",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
            }
        }
    }

    val showTakeoff = !armed || !inAutoMode

    Box(modifier = modifier) {
        if (portrait) {
            // Portrait: single horizontal dock at bottom-center.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                armButton()
                recordButton()
                if (inAutoMode) {
                    pauseButton()
                }
                if (showTakeoff) {
                    takeoffButton()
                }
                if (armed) {
                    landButton()
                }
                rtlButton()
            }
        } else {
            // Landscape: ARM bottom-left, action column bottom-right.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 16.dp),
            ) {
                armButton()
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                recordButton()
                if (inAutoMode) {
                    pauseButton()
                }
                if (showTakeoff) {
                    takeoffButton()
                }
                if (armed) {
                    landButton()
                }
                rtlButton()
            }
        }
    }

    // Disarm confirmation dialog
    if (showDisarmDialog) {
        AlertDialog(
            onDismissRequest = { showDisarmDialog = false },
            title = { Text("Confirm Disarm") },
            text = { Text("Disarm motors? The drone will stop all motors immediately.") },
            confirmButton = {
                TextButton(onClick = {
                    onDisarm()
                    showDisarmDialog = false
                }) { Text("Disarm", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { showDisarmDialog = false }) { Text("Cancel") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
