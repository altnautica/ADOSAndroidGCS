package com.altnautica.gcs.ui.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.ui.theme.ElectricBlue
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Virtual joystick overlay for gimbal pitch/yaw control.
 * Vertical axis controls gimbal pitch, horizontal axis controls yaw.
 * Springs back to center on release and sends commands at 10Hz while active.
 */
@Composable
fun GimbalJoystick(
    commandSender: MavLinkCommandSender,
    modifier: Modifier = Modifier,
    sensitivity: Float = 1.0f,
    enabled: Boolean = true,
) {
    val joystickSizeDp = 128.dp

    var isDragging by remember { mutableStateOf(false) }
    var rawOffsetX by remember { mutableFloatStateOf(0f) }
    var rawOffsetY by remember { mutableFloatStateOf(0f) }

    // Spring return to center when released
    val offsetX by animateFloatAsState(
        targetValue = if (isDragging) rawOffsetX else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "joystickX",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (isDragging) rawOffsetY else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "joystickY",
    )

    // Send gimbal commands at 10Hz while the joystick is active
    LaunchedEffect(isDragging) {
        if (!enabled) return@LaunchedEffect
        while (isDragging) {
            val normalizedX = offsetX / 150f // Normalize to roughly -1..1
            val normalizedY = offsetY / 150f
            val pitch = -normalizedY * 45f * sensitivity // Up = negative Y = pitch up
            val yaw = normalizedX * 45f * sensitivity
            if (pitch != 0f || yaw != 0f) {
                commandSender.sendGimbalPitchYaw(pitch, yaw)
            }
            delay(100) // 10Hz
        }
    }

    Box(
        modifier = modifier.size(joystickSizeDp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(joystickSizeDp)
                .pointerInput(Unit) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                            rawOffsetX = 0f
                            rawOffsetY = 0f
                        },
                        onDragCancel = {
                            isDragging = false
                            rawOffsetX = 0f
                            rawOffsetY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxRadius = size.width / 2f
                            val newX = rawOffsetX + dragAmount.x
                            val newY = rawOffsetY + dragAmount.y
                            val dist = sqrt(newX * newX + newY * newY)
                            if (dist <= maxRadius) {
                                rawOffsetX = newX
                                rawOffsetY = newY
                            } else {
                                // Clamp to circle
                                rawOffsetX = newX / dist * maxRadius
                                rawOffsetY = newY / dist * maxRadius
                            }
                        },
                    )
                },
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val outerRadius = size.width / 2f
            val thumbRadius = outerRadius * 0.3f

            // Outer ring
            drawCircle(
                color = Color.White.copy(alpha = 0.15f),
                radius = outerRadius,
                center = Offset(centerX, centerY),
            )
            drawCircle(
                color = ElectricBlue.copy(alpha = 0.3f),
                radius = outerRadius,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )

            // Inner thumb
            drawCircle(
                color = ElectricBlue.copy(alpha = if (isDragging) 0.7f else 0.4f),
                radius = thumbRadius,
                center = Offset(centerX + offsetX, centerY + offsetY),
            )
        }
    }
}
