package com.altnautica.gcs.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.ui.gcs.DroneMapView
import com.altnautica.gcs.ui.theme.OnSurfaceMedium

@Composable
fun MapPip(
    dronePosition: PositionState,
    homePosition: PositionState?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val pipWidth = if (expanded) 240.dp else 120.dp
    val pipHeight = if (expanded) 180.dp else 90.dp

    Box(
        modifier = modifier
            .width(pipWidth)
            .height(pipHeight)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, OnSurfaceMedium.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .animateContentSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { expanded = !expanded },
                )
            },
    ) {
        DroneMapView(
            dronePosition = dronePosition,
            homePosition = homePosition,
            useMapbox = true,
            modifier = Modifier
                .width(pipWidth)
                .height(pipHeight),
        )
    }
}
