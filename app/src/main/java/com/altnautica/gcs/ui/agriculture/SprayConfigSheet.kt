package com.altnautica.gcs.ui.agriculture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime

private val CROP_TYPES = listOf("Rice", "Wheat", "Cotton", "Sugarcane", "Maize", "Soybean", "Mustard", "Vegetables")
private val CHEMICAL_TYPES = listOf("Pesticide", "Herbicide", "Fungicide", "Fertilizer (liquid)", "Growth Regulator")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SprayConfigSheet(
    onDismiss: () -> Unit,
    onConfirm: (SprayConfig) -> Unit,
) {
    var cropType by remember { mutableStateOf(CROP_TYPES[0]) }
    var chemical by remember { mutableStateOf(CHEMICAL_TYPES[0]) }
    var ratePerAcre by remember { mutableFloatStateOf(10f) }
    var altitude by remember { mutableFloatStateOf(5f) }
    var speed by remember { mutableFloatStateOf(3f) }
    var swathWidth by remember { mutableFloatStateOf(4f) }

    var cropExpanded by remember { mutableStateOf(false) }
    var chemicalExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Spray Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // Crop type dropdown
            ExposedDropdownMenuBox(
                expanded = cropExpanded,
                onExpandedChange = { cropExpanded = it },
            ) {
                OutlinedTextField(
                    value = cropType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Crop Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = cropExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = cropExpanded, onDismissRequest = { cropExpanded = false }) {
                    CROP_TYPES.forEach { crop ->
                        DropdownMenuItem(
                            text = { Text(crop) },
                            onClick = { cropType = crop; cropExpanded = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Chemical dropdown
            ExposedDropdownMenuBox(
                expanded = chemicalExpanded,
                onExpandedChange = { chemicalExpanded = it },
            ) {
                OutlinedTextField(
                    value = chemical,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Chemical") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = chemicalExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = chemicalExpanded, onDismissRequest = { chemicalExpanded = false }) {
                    CHEMICAL_TYPES.forEach { chem ->
                        DropdownMenuItem(
                            text = { Text(chem) },
                            onClick = { chemical = chem; chemicalExpanded = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Rate slider
            SliderField(label = "Rate (L/acre)", value = ratePerAcre, range = 1f..50f, unit = "L") {
                ratePerAcre = it
            }

            // Altitude slider
            SliderField(label = "Flight Altitude", value = altitude, range = 1.5f..15f, unit = "m") {
                altitude = it
            }

            // Speed slider
            SliderField(label = "Flight Speed", value = speed, range = 1f..8f, unit = "m/s") {
                speed = it
            }

            // Swath width slider
            SliderField(label = "Swath Width", value = swathWidth, range = 1f..10f, unit = "m") {
                swathWidth = it
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    onConfirm(
                        SprayConfig(
                            cropType = cropType,
                            chemical = chemical,
                            ratePerAcre = ratePerAcre,
                            altitude = altitude,
                            speed = speed,
                            swathWidth = swathWidth,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            ) {
                Text("Confirm", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
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
                text = "%.1f %s".format(value, unit),
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

data class SprayConfig(
    val cropType: String,
    val chemical: String,
    val ratePerAcre: Float,
    val altitude: Float,
    val speed: Float,
    val swathWidth: Float,
)
