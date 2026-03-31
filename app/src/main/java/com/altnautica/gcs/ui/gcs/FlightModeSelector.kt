package com.altnautica.gcs.ui.gcs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.ui.theme.DeepBlack
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SurfaceVariant

private val selectorModes = listOf(
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
fun FlightModeSelector(
    currentMode: FlightMode?,
    onModeSelected: (FlightMode) -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = "Flight Mode",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        selectorModes.forEach { mode ->
            val isActive = mode == currentMode
            // RTL always enabled (safety override), other modes respect enabled flag
            val modeEnabled = enabled || mode == FlightMode.RTL
            val disabledAlpha = if (modeEnabled) 1f else 0.4f

            Surface(
                onClick = { if (modeEnabled) onModeSelected(mode) },
                shape = RoundedCornerShape(8.dp),
                color = (if (isActive) ElectricBlue else SurfaceVariant).copy(alpha = disabledAlpha),
                contentColor = (if (isActive) DeepBlack else MaterialTheme.colorScheme.onSurface).copy(alpha = disabledAlpha),
                border = if (isActive) null else BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f * disabledAlpha)),
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}
