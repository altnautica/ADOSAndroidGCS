package com.altnautica.gcs.ui.video

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ErrorRed
import kotlinx.coroutines.delay

/**
 * Camera control overlay with photo capture and video record toggle.
 * Photo button flashes white briefly on tap. Video record button
 * toggles between record (red dot) and stop icon with a recording indicator.
 */
@Composable
fun CameraControls(
    isRecordingVideo: Boolean,
    onPhotoCapture: () -> Unit,
    onToggleVideoRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var photoFlash by remember { mutableStateOf(false) }

    // Brief flash animation on photo capture
    val photoBgColor by animateColorAsState(
        targetValue = if (photoFlash) Color.White.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "photoFlash",
    )
    LaunchedEffect(photoFlash) {
        if (photoFlash) {
            delay(200)
            photoFlash = false
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Photo button
            Surface(
                shape = CircleShape,
                color = photoBgColor,
            ) {
                IconButton(
                    onClick = {
                        photoFlash = true
                        onPhotoCapture()
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Filled.Camera,
                        contentDescription = "Take photo",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Video record toggle
            IconButton(
                onClick = onToggleVideoRecord,
                modifier = Modifier.size(40.dp),
            ) {
                if (isRecordingVideo) {
                    // Recording indicator: red dot behind stop icon
                    Surface(
                        shape = CircleShape,
                        color = ErrorRed.copy(alpha = 0.3f),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Stop video recording",
                            tint = ErrorRed,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(6.dp),
                        )
                    }
                } else {
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = "Start video recording",
                        tint = ErrorRed,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}
