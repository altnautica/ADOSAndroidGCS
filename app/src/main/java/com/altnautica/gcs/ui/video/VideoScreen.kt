package com.altnautica.gcs.ui.video

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.settings.SettingsViewModel

@Composable
fun VideoScreen(
    viewModel: VideoViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val attitude by viewModel.attitude.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val homePosition by viewModel.homePosition.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val gps by viewModel.gps.collectAsStateWithLifecycle()
    val vfr by viewModel.vfr.collectAsStateWithLifecycle()
    val flightMode by viewModel.flightMode.collectAsStateWithLifecycle()
    val armed by viewModel.armed.collectAsStateWithLifecycle()
    val videoMode by viewModel.videoMode.collectAsStateWithLifecycle()

    val compassEnabled by settingsViewModel.compassEnabled.collectAsStateWithLifecycle()
    val altLadderEnabled by settingsViewModel.altLadderEnabled.collectAsStateWithLifecycle()
    val speedLadderEnabled by settingsViewModel.speedLadderEnabled.collectAsStateWithLifecycle()

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

        // Cloud mode latency warning banner
        if (videoMode is VideoMode.CloudRelay) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCCFBBF24))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter)
            ) {
                Text(
                    text = "Cloud mode \u2014 high latency (~200ms). Not suitable for manual flight.",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // HUD Canvas overlay
        HudOverlay(
            attitude = attitude,
            position = position,
            battery = battery,
            gps = gps,
            vfr = vfr,
            flightMode = flightMode,
            armed = armed,
            homePosition = homePosition,
            compassEnabled = compassEnabled,
            altLadderEnabled = altLadderEnabled,
            speedLadderEnabled = speedLadderEnabled,
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
