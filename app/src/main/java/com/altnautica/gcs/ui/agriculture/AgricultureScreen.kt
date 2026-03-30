package com.altnautica.gcs.ui.agriculture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgricultureScreen(viewModel: AgricultureViewModel = hiltViewModel()) {
    val fieldState by viewModel.fieldState.collectAsStateWithLifecycle()
    val sprayConfig by viewModel.sprayConfig.collectAsStateWithLifecycle()
    val missionState by viewModel.missionState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // Status indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Agriculture",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val statusColor = when (missionState) {
                MissionState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                MissionState.MAPPING -> ElectricBlue
                MissionState.CONFIGURED -> WarningAmber
                MissionState.RUNNING -> SuccessGreen
                MissionState.COMPLETE -> NeonLime
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = statusColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = missionState.label,
                    color = statusColor,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Large action buttons
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(
                icon = Icons.Filled.Map,
                label = "Map Field",
                color = ElectricBlue,
                enabled = missionState == MissionState.IDLE || missionState == MissionState.COMPLETE,
                onClick = { viewModel.startMapping() },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                icon = Icons.Filled.Agriculture,
                label = "Set Up Spray",
                color = WarningAmber,
                enabled = fieldState.boundaryPoints.isNotEmpty(),
                onClick = { viewModel.openSprayConfig() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(
                icon = Icons.Filled.PlayArrow,
                label = "Start Mission",
                color = SuccessGreen,
                enabled = missionState == MissionState.CONFIGURED,
                onClick = { viewModel.startMission() },
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                icon = Icons.Filled.Summarize,
                label = "View Summary",
                color = NeonLime,
                enabled = missionState == MissionState.COMPLETE,
                onClick = { viewModel.viewSummary() },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))

        // Field info (when boundary exists)
        if (fieldState.boundaryPoints.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Field",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${fieldState.boundaryPoints.size} points, %.2f ha".format(fieldState.areaHectares),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) color.copy(alpha = 0.12f) else SurfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.height(100.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) color else color.copy(alpha = 0.3f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) color else color.copy(alpha = 0.3f),
            )
        }
    }
}
