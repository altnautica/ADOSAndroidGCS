package com.altnautica.gcs.ui.configure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.data.mavlink.ParameterManager.ParamEntry
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.NeonLime
import com.altnautica.gcs.ui.theme.SurfaceVariant

/**
 * Bottom sheet for editing a single parameter value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParameterEditorSheet(
    param: ParamEntry,
    onWrite: (String, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editValue by remember { mutableStateOf(formatParamValue(param.value, param.type)) }
    var isError by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // Param name
            Text(
                text = param.name,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(16.dp))

            // Current value + type badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Current:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatParamValue(param.value, param.type),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ParamTypeBadge(type = param.type)
            }

            Spacer(Modifier.height(20.dp))

            // Edit field
            OutlinedTextField(
                value = editValue,
                onValueChange = { newValue ->
                    editValue = newValue
                    isError = newValue.toFloatOrNull() == null && newValue.isNotBlank()
                },
                label = { Text("New value") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = isError,
                supportingText = if (isError) {
                    { Text("Enter a valid number") }
                } else {
                    null
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    cursorColor = ElectricBlue,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Cancel")
                }

                Spacer(Modifier.width(12.dp))

                Button(
                    onClick = {
                        val parsed = editValue.toFloatOrNull()
                        if (parsed != null) {
                            onWrite(param.name, parsed)
                            onDismiss()
                        } else {
                            isError = true
                        }
                    },
                    enabled = !isError && editValue.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricBlue,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text("Write")
                }
            }
        }
    }
}

/**
 * Small badge showing the param type (float, int, enum).
 */
@Composable
private fun ParamTypeBadge(type: Int) {
    val label = when (type) {
        1, 2, 3, 4, 5, 6, 7, 8 -> "int"
        9, 10 -> "float"
        else -> "type:$type"
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = SurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NeonLime,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Format parameter value based on type.
 * Integer types show no decimal places, floats show up to 6 significant digits.
 */
private fun formatParamValue(value: Float, type: Int): String {
    return when (type) {
        1, 2, 3, 4, 5, 6, 7, 8 -> value.toInt().toString()
        else -> {
            if (value == value.toLong().toFloat() && value < 1_000_000f) {
                // Whole number stored as float
                value.toInt().toString()
            } else {
                // Trim trailing zeros
                "%.6f".format(value).trimEnd('0').trimEnd('.')
            }
        }
    }
}
