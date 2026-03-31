package com.altnautica.gcs.ui.mission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.agriculture.Waypoint

enum class WaypointCommand(val label: String) {
    WAYPOINT("Waypoint"),
    LOITER("Loiter"),
    RTL("Return to Launch"),
    TAKEOFF("Takeoff"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointEditor(
    waypoint: Waypoint?,
    onSave: (Waypoint) -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = waypoint == null
    var lat by remember { mutableStateOf(waypoint?.lat?.toString() ?: "") }
    var lon by remember { mutableStateOf(waypoint?.lon?.toString() ?: "") }
    var alt by remember { mutableFloatStateOf(waypoint?.alt ?: 50f) }
    var speed by remember { mutableFloatStateOf(waypoint?.speed ?: 5f) }
    var commandExpanded by remember { mutableStateOf(false) }
    var selectedCommand by remember { mutableStateOf(WaypointCommand.WAYPOINT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add Waypoint" else "Edit Waypoint") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Lat/Lon inputs
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Latitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Longitude") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Altitude slider
                Text(
                    text = "Altitude: ${alt.toInt()} m",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = alt,
                    onValueChange = { alt = it },
                    valueRange = 5f..200f,
                    steps = 38,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                // Speed slider
                Text(
                    text = "Speed: ${"%.1f".format(speed)} m/s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = speed,
                    onValueChange = { speed = it },
                    valueRange = 1f..30f,
                    steps = 28,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                // Command type dropdown
                ExposedDropdownMenuBox(
                    expanded = commandExpanded,
                    onExpandedChange = { commandExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCommand.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Command") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = commandExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = commandExpanded,
                        onDismissRequest = { commandExpanded = false },
                    ) {
                        WaypointCommand.entries.forEach { cmd ->
                            DropdownMenuItem(
                                text = { Text(cmd.label) },
                                onClick = {
                                    selectedCommand = cmd
                                    commandExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedLat = lat.toDoubleOrNull() ?: return@TextButton
                    val parsedLon = lon.toDoubleOrNull() ?: return@TextButton
                    onSave(Waypoint(parsedLat, parsedLon, alt, speed))
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
