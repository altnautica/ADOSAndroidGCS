package com.altnautica.gcs.ui.voice

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SurfaceVariant

/**
 * FloatingActionButton with mic icon for voice input.
 * When pressed, shows a listening indicator and live transcription text.
 * On result, the caller should execute the matched command.
 */
@Composable
fun VoiceInputButton(
    isListening: Boolean,
    partialText: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fabColor by animateColorAsState(
        targetValue = if (isListening) ErrorRed else ElectricBlue,
        label = "fab_color",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        // Live transcription bubble (shown only when listening and text available)
        if (isListening && partialText.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(
                    text = partialText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Listening indicator label
        if (isListening) {
            Text(
                text = "Listening...",
                style = MaterialTheme.typography.labelSmall,
                color = ErrorRed,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        FloatingActionButton(
            onClick = onToggle,
            containerColor = fabColor,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Voice command",
            )
        }
    }
}
