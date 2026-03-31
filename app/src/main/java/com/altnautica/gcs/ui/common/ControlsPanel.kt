package com.altnautica.gcs.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.VfrState
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber
import com.altnautica.gcs.ui.video.CameraSwitcher

private val panelModes = listOf(
    FlightMode.STABILIZE,
    FlightMode.ALT_HOLD,
    FlightMode.LOITER,
    FlightMode.AUTO,
    FlightMode.RTL,
    FlightMode.LAND,
    FlightMode.GUIDED,
    FlightMode.POSHOLD,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsPanel(
    visible: Boolean,
    currentMode: FlightMode?,
    onModeSelected: (FlightMode) -> Unit,
    battery: BatteryState,
    gps: GpsState,
    position: PositionState,
    vfr: VfrState,
    videoMode: VideoMode,
    activeCameraId: String,
    onSwitchCamera: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
            color = DeepBlack.copy(alpha = 0.92f),
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // Flight mode selector
                Text(
                    text = "Flight Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceMedium,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    panelModes.forEach { mode ->
                        val isActive = mode == currentMode
                        Surface(
                            onClick = { onModeSelected(mode) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isActive) ElectricBlue else SurfaceVariant,
                            contentColor = if (isActive) DeepBlack else MaterialTheme.colorScheme.onSurface,
                        ) {
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(16.dp))

                // Telemetry summary
                Text(
                    text = "Telemetry",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceMedium,
                )
                Spacer(Modifier.height(8.dp))

                val battColor = when {
                    battery.remaining < 0 -> OnSurfaceMedium
                    battery.remaining <= 20 -> ErrorRed
                    battery.remaining <= 40 -> WarningAmber
                    else -> SuccessGreen
                }
                CompactTelemetryRow("Battery", if (battery.remaining >= 0) "${battery.remaining}%" else "--%", battColor)
                CompactTelemetryRow("Voltage", "%.1fV".format(battery.voltage), OnSurfaceMedium)

                val gpsColor = when (gps.fixType) {
                    0, 1 -> ErrorRed
                    2 -> WarningAmber
                    else -> SuccessGreen
                }
                CompactTelemetryRow("GPS", "${gps.satellites} sats", gpsColor)
                CompactTelemetryRow("Alt", "%.1f m".format(position.altRel), OnSurfaceMedium)
                CompactTelemetryRow("Speed", "%.1f m/s".format(vfr.groundspeed), OnSurfaceMedium)

                // Ground station stats if Mode A
                if (videoMode is VideoMode.GroundStation) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = SurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Ground Station",
                        style = MaterialTheme.typography.labelLarge,
                        color = OnSurfaceMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    CompactTelemetryRow("Mode", "A (WebRTC)", ElectricBlue)
                }

                // Camera switcher
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Camera",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnSurfaceMedium,
                )
                Spacer(Modifier.height(8.dp))
                CameraSwitcher(
                    activeCameraId = activeCameraId,
                    onSwitchCamera = onSwitchCamera,
                )
            }
        }
    }
}

@Composable
private fun CompactTelemetryRow(
    label: String,
    value: String,
    valueColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMedium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}
