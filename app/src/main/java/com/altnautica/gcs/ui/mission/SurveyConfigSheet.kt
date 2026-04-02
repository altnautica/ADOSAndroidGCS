package com.altnautica.gcs.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.mission.SurveyConfig
import com.altnautica.gcs.data.mission.SurveyLatLng
import com.altnautica.gcs.data.mission.SurveyResult
import com.altnautica.gcs.data.mission.generateSurveyGrid
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.SuccessGreen

/**
 * Bottom sheet for configuring and previewing an aerial survey grid mission.
 *
 * @param polygon The survey boundary drawn on the map.
 * @param onPreview Called with the generated result so the map can show a preview polyline.
 * @param onUpload Called when the user confirms and wants to upload the mission.
 * @param onDismiss Called when the sheet should close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyConfigSheet(
    polygon: List<SurveyLatLng>,
    onPreview: (SurveyResult) -> Unit,
    onUpload: (SurveyResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var altitude by remember { mutableFloatStateOf(50f) }
    var overlapPercent by remember { mutableFloatStateOf(70f) }
    var sidelapPercent by remember { mutableFloatStateOf(60f) }
    var gridAngle by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(5f) }
    var result by remember { mutableStateOf<SurveyResult?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF141414),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Survey Mission",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))

            // Altitude
            SurveySlider(
                label = "Altitude",
                value = altitude,
                range = 20f..120f,
                unit = "m",
                decimals = 0,
                onValueChange = { altitude = it },
            )

            // Forward overlap
            SurveySlider(
                label = "Forward Overlap",
                value = overlapPercent,
                range = 50f..90f,
                unit = "%",
                decimals = 0,
                onValueChange = { overlapPercent = it },
            )

            // Side overlap
            SurveySlider(
                label = "Side Overlap",
                value = sidelapPercent,
                range = 40f..80f,
                unit = "%",
                decimals = 0,
                onValueChange = { sidelapPercent = it },
            )

            // Grid angle
            SurveySlider(
                label = "Grid Angle",
                value = gridAngle,
                range = 0f..180f,
                unit = "\u00B0",
                decimals = 0,
                onValueChange = { gridAngle = it },
            )

            // Speed
            SurveySlider(
                label = "Speed",
                value = speed,
                range = 2f..15f,
                unit = "m/s",
                decimals = 1,
                onValueChange = { speed = it },
            )

            // Stats row (visible after generation)
            result?.let { res ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = SurfaceVariant)
                Spacer(Modifier.height(12.dp))
                SurveyStatsRow(res)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Generate Preview button
            Button(
                onClick = {
                    val config = SurveyConfig(
                        polygon = polygon,
                        altitude = altitude,
                        overlapPercent = overlapPercent,
                        sidelapPercent = sidelapPercent,
                        speed = speed,
                        angle = gridAngle,
                    )
                    val generated = generateSurveyGrid(config)
                    result = generated
                    onPreview(generated)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Text("Generate Preview", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(8.dp))

            // Upload Mission button (enabled only after preview)
            Button(
                onClick = { result?.let { onUpload(it) } },
                enabled = result != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SuccessGreen,
                    disabledContainerColor = SuccessGreen.copy(alpha = 0.3f),
                ),
            ) {
                Text("Upload Mission", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(8.dp))

            // Cancel
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SurveyStatsRow(result: SurveyResult) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatCell(
            label = "Flight Time",
            value = formatFlightTime(result.estimatedFlightTimeSec),
        )
        StatCell(
            label = "Distance",
            value = if (result.totalDistanceM >= 1000f) {
                "%.1f km".format(result.totalDistanceM / 1000f)
            } else {
                "%.0f m".format(result.totalDistanceM)
            },
        )
        StatCell(label = "Lines", value = "${result.lineCount}")
        StatCell(
            label = "Est. Photos",
            value = "${estimatePhotoCount(result)}",
        )
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = NeonLime,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurveySlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    decimals: Int = 1,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (decimals == 0) "%.0f %s".format(value, unit) else "%.${decimals}f %s".format(value, unit),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = NeonLime,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = ElectricBlue,
                activeTrackColor = ElectricBlue,
            ),
        )
    }
}

private fun formatFlightTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun estimatePhotoCount(result: SurveyResult): Int {
    if (result.photoIntervalM <= 0f || result.waypoints.size < 2) return 0
    // Each pair of waypoints is one survey line. Photos along each line.
    var count = 0
    var i = 0
    while (i + 1 < result.waypoints.size) {
        val a = result.waypoints[i]
        val b = result.waypoints[i + 1]
        val dx = (b.lon - a.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(a.lat))
        val dy = (b.lat - a.lat) * 111_320.0
        val lineLen = kotlin.math.sqrt(dx * dx + dy * dy)
        count += kotlin.math.max(1, (lineLen / result.photoIntervalM).toInt() + 1)
        i += 2
    }
    return count
}
