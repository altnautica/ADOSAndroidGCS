package com.altnautica.gcs.ui.video

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.common.ControlsPanel
import com.altnautica.gcs.ui.common.FloatingControls
import com.altnautica.gcs.ui.common.GamepadOverlay
import com.altnautica.gcs.ui.common.MapPip
import com.altnautica.gcs.ui.common.TakeoffDialog
import com.altnautica.gcs.ui.gcs.GcsViewModel
import com.altnautica.gcs.ui.settings.SettingsViewModel
import com.altnautica.gcs.ui.theme.isPortrait

@Composable
fun FlyScreen(
    onBack: () -> Unit,
    viewModel: VideoViewModel = hiltViewModel(),
    gcsViewModel: GcsViewModel = hiltViewModel(),
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
    val isStreaming by viewModel.isStreaming.collectAsStateWithLifecycle()

    val compassEnabled by settingsViewModel.compassEnabled.collectAsStateWithLifecycle()
    val altLadderEnabled by settingsViewModel.altLadderEnabled.collectAsStateWithLifecycle()
    val speedLadderEnabled by settingsViewModel.speedLadderEnabled.collectAsStateWithLifecycle()

    val missionPaused by gcsViewModel.missionPaused.collectAsStateWithLifecycle()
    val showTakeoffDialog by gcsViewModel.showTakeoffDialog.collectAsStateWithLifecycle()
    val gamepadState by gcsViewModel.gamepadState.collectAsStateWithLifecycle()

    var showControlsPanel by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var activeCameraId by remember { mutableStateOf("cam0") }

    val inAutoMode = flightMode == FlightMode.AUTO
    val portrait = isPortrait()

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: Video SurfaceView (fills entire screen)
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    setZOrderOnTop(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2: Cloud mode latency warning banner
        if (videoMode is VideoMode.CloudRelay) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCCFBBF24))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .align(Alignment.TopCenter)
            ) {
                Text(
                    text = "Cloud mode -- high latency (~200ms). Not suitable for manual flight.",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Layer 3: HUD Canvas overlay
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
            recording = recording,
        )

        // Layer 4: Floating controls (ARM/DISARM, RTL, Record, Takeoff, Land, Pause/Resume)
        FloatingControls(
            armed = armed,
            recording = recording,
            paused = missionPaused,
            inAutoMode = inAutoMode,
            onArm = { gcsViewModel.requestArm() },
            onDisarm = { gcsViewModel.requestDisarm() },
            onRtl = { gcsViewModel.requestSetMode(FlightMode.RTL) },
            onToggleRecord = { recording = !recording },
            onTakeoff = { gcsViewModel.requestTakeoff() },
            onLand = { gcsViewModel.requestLand() },
            onPause = { gcsViewModel.requestPause() },
            onResume = { gcsViewModel.requestResume() },
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 4b: Gamepad overlay (top-left, below back button)
        GamepadOverlay(
            state = gamepadState,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 48.dp),
        )

        // Layer 5: Map PiP. Landscape places it bottom-right above the action-button
        // stack. Portrait moves it to top-end so it clears the bottom-dock controls.
        MapPip(
            dronePosition = position,
            homePosition = homePosition,
            modifier = if (portrait) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 96.dp, end = 8.dp)
            } else {
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 80.dp, bottom = 16.dp)
            },
        )

        // Layer 6: Back button (top-left)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(
                Icons.Filled.ArrowBack,
                contentDescription = "Back to home",
                tint = Color.White.copy(alpha = 0.7f),
            )
        }

        // Layer 7: Mode indicator (top-right)
        ModeIndicator(
            videoMode = videoMode,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )

        // Layer 8: Hamburger menu for controls panel (top-right, below mode indicator)
        IconButton(
            onClick = { showControlsPanel = !showControlsPanel },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 8.dp),
        ) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Open controls panel",
                tint = Color.White.copy(alpha = 0.7f),
            )
        }

        // Layer 9: Controls panel. Landscape slides in from the right edge;
        // portrait slides up from the bottom as a sheet.
        ControlsPanel(
            visible = showControlsPanel,
            currentMode = flightMode,
            onModeSelected = { gcsViewModel.requestSetMode(it) },
            battery = battery,
            gps = gps,
            position = position,
            vfr = vfr,
            videoMode = videoMode,
            activeCameraId = activeCameraId,
            onSwitchCamera = { activeCameraId = it },
            modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.CenterEnd),
        )
    }

    // Takeoff altitude dialog
    if (showTakeoffDialog) {
        TakeoffDialog(
            onConfirm = { altitude -> gcsViewModel.confirmTakeoff(altitude) },
            onDismiss = { gcsViewModel.dismissTakeoffDialog() },
        )
    }
}
