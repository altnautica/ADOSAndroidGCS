package com.altnautica.gcs.ui.video

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun VideoScreen(viewModel: VideoViewModel = hiltViewModel()) {
    val attitude by viewModel.attitude.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val gps by viewModel.gps.collectAsStateWithLifecycle()
    val vfr by viewModel.vfr.collectAsStateWithLifecycle()
    val flightMode by viewModel.flightMode.collectAsStateWithLifecycle()
    val armed by viewModel.armed.collectAsStateWithLifecycle()
    val videoMode by viewModel.videoMode.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        // Video SurfaceView (bottom layer)
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setZOrderOnTop(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // HUD Canvas overlay
        HudOverlay(
            attitude = attitude,
            position = position,
            battery = battery,
            gps = gps,
            vfr = vfr,
            flightMode = flightMode,
            armed = armed,
        )

        // Mode badge (top-right corner)
        ModeIndicator(
            videoMode = videoMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
    }
}
