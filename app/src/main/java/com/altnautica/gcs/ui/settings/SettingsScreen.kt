package com.altnautica.gcs.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.SurfaceVariant

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val mapProvider by viewModel.mapProvider.collectAsStateWithLifecycle()
    val hudEnabled by viewModel.hudEnabled.collectAsStateWithLifecycle()
    val compassEnabled by viewModel.compassEnabled.collectAsStateWithLifecycle()
    val altLadderEnabled by viewModel.altLadderEnabled.collectAsStateWithLifecycle()
    val speedLadderEnabled by viewModel.speedLadderEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(20.dp))

        // Theme
        SettingsSection(title = "Theme") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeOption.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = theme == option,
                        onClick = { viewModel.setTheme(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeOption.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // Units
        SettingsSection(title = "Units") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                UnitSystem.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = units == option,
                        onClick = { viewModel.setUnits(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = UnitSystem.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // Map provider
        SettingsSection(title = "Map Provider") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MapProvider.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mapProvider == option,
                        onClick = { viewModel.setMapProvider(option) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = MapProvider.entries.size,
                        ),
                    ) {
                        Text(option.label)
                    }
                }
            }
        }

        // HUD elements
        SettingsSection(title = "HUD Elements") {
            ToggleRow("HUD Overlay", hudEnabled) { viewModel.setHudEnabled(it) }
            ToggleRow("Compass Tape", compassEnabled) { viewModel.setCompassEnabled(it) }
            ToggleRow("Altitude Ladder", altLadderEnabled) { viewModel.setAltLadderEnabled(it) }
            ToggleRow("Speed Ladder", speedLadderEnabled) { viewModel.setSpeedLadderEnabled(it) }
        }

        Spacer(Modifier.height(20.dp))

        // About
        SettingsSection(title = "About") {
            InfoRow("Version", "0.1.0")
            InfoRow("License", "GPL-3.0")
            InfoRow("Website", "altnautica.com")
            InfoRow("Source", "github.com/altnautica/ADOSAndroidGCS")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ElectricBlue,
            ),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
