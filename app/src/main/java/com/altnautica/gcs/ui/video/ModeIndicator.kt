package com.altnautica.gcs.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.WarningAmber

@Composable
fun ModeIndicator(
    videoMode: VideoMode,
    modifier: Modifier = Modifier,
) {
    val color = when (videoMode) {
        is VideoMode.GroundStation -> ElectricBlue
        is VideoMode.DirectUsb -> SuccessGreen
        is VideoMode.CloudRelay -> WarningAmber
        is VideoMode.NoConnection -> ErrorRed
    }

    val label = when (videoMode) {
        is VideoMode.GroundStation -> "A"
        is VideoMode.DirectUsb -> "B"
        is VideoMode.CloudRelay -> "C"
        is VideoMode.NoConnection -> "--"
    }

    Text(
        text = label,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .background(
                color = color.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
