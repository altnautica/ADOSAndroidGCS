package com.altnautica.gcs.ui.common

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
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
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingControls(
    armed: Boolean,
    recording: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onRtl: () -> Unit,
    onToggleRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var showDisarmDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Left side: ARM/DISARM button
        Surface(
            shape = CircleShape,
            color = if (armed) ErrorRed.copy(alpha = 0.9f) else SuccessGreen.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 16.dp)
                .size(72.dp)
                .combinedClickable(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (armed) {
                            showDisarmDialog = true
                        }
                        // ARM requires long press, single tap does nothing when disarmed
                    },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (!armed) {
                            onArm()
                        }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                Text(
                    text = if (armed) stringResource(R.string.gcs_disarm) else stringResource(R.string.gcs_arm),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Right side: RTL + Record buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Record button
            Surface(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onToggleRecord()
                },
                shape = CircleShape,
                color = if (recording) ErrorRed.copy(alpha = 0.9f) else OnSurfaceMedium.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = if (recording) "Stop recording" else "Start recording",
                        tint = if (recording) Color.White else ErrorRed,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // RTL button
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
