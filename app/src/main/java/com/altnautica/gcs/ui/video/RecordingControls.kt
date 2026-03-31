package com.altnautica.gcs.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ErrorRed

/**
 * Composable overlay for video recording controls.
 * Shows a red record dot with timer (MM:SS) and file size.
 * Tap the button to start or stop recording.
 */
@Composable
fun RecordingControls(
    isRecording: Boolean,
    durationMs: Long,
    fileSizeBytes: Long,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isRecording) {
                // Pulsing red dot
                Surface(
                    shape = CircleShape,
                    color = ErrorRed,
                    modifier = Modifier.size(10.dp),
                ) {}
                Spacer(Modifier.width(8.dp))

                // Timer MM:SS
                val seconds = (durationMs / 1000) % 60
                val minutes = (durationMs / 1000) / 60
                Text(
                    text = "%02d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = ErrorRed,
                )
                Spacer(Modifier.width(8.dp))

                // File size
                val sizeMb = fileSizeBytes / (1024f * 1024f)
                Text(
                    text = "${"%.1f".format(sizeMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))

                // Stop button
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop recording", tint = ErrorRed)
                }
            } else {
                // Record button
                IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = "Start recording",
                        tint = ErrorRed,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "REC",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
