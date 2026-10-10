package com.altnautica.gcs.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Shared layout: title and optional summary on the left, control on the right. */
@Composable
private fun SettingRowLayout(
    title: String,
    summary: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AdosTheme.touchMin)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.fgPrimary)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
            }
        }
        Spacer(Modifier.width(AdosSpacing.S3))
        trailing()
    }
}

@Composable
fun SwitchSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val colors = AdosTheme.colors
    SettingRowLayout(
        title = title,
        summary = summary,
        enabled = enabled,
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accentForeground,
                checkedTrackColor = colors.accentPrimary,
                uncheckedThumbColor = colors.fgSecondary,
                uncheckedTrackColor = colors.bgRaised,
                uncheckedBorderColor = colors.borderStrong,
            ),
        )
    }
}

@Composable
fun <T> SelectSettingRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val colors = AdosTheme.colors
    var expanded by remember { mutableStateOf(false) }
    SettingRowLayout(
        title = title,
        summary = summary,
        enabled = enabled,
        modifier = modifier.clickable(enabled = enabled, role = Role.DropdownList) { expanded = true },
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium, color = colors.accentPrimary)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = colors.fgSecondary)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = colors.bgRaised,
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), color = colors.fgPrimary) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        modifier = Modifier.heightIn(min = AdosTheme.touchMin),
                    )
                }
            }
        }
    }
}

/** Integer stepper; [format] renders the value (for units). */
@Composable
fun NumberSettingRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    decrementDescription: String,
    incrementDescription: String,
    modifier: Modifier = Modifier,
    step: Int = 1,
    summary: String? = null,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
) {
    val colors = AdosTheme.colors
    SettingRowLayout(title = title, summary = summary, enabled = enabled, modifier = modifier) {
        IconButton(
            onClick = { onValueChange((value - step).coerceIn(range)) },
            enabled = enabled && value > range.first,
            modifier = Modifier.size(AdosTheme.touchMin),
        ) {
            Icon(Icons.Outlined.Remove, contentDescription = decrementDescription, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconLg))
        }
        Text(
            format(value),
            modifier = Modifier.widthIn(min = 48.dp),
            style = AdosTypography.numeric.medium,
            color = colors.fgPrimary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(
            onClick = { onValueChange((value + step).coerceIn(range)) },
            enabled = enabled && value < range.last,
            modifier = Modifier.size(AdosTheme.touchMin),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = incrementDescription, tint = colors.fgPrimary, modifier = Modifier.size(AdosSize.IconLg))
        }
    }
}

@Composable
fun TextSettingRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = AdosTheme.colors
    SettingRowLayout(title = title, summary = summary, enabled = enabled, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.widthIn(min = 120.dp, max = 220.dp),
            enabled = enabled,
            singleLine = true,
            placeholder = placeholder?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderDefault,
                cursorColor = colors.accentPrimary,
                focusedTextColor = colors.fgPrimary,
                unfocusedTextColor = colors.fgPrimary,
            ),
        )
    }
}

@Preview(widthDp = 1000)
@Composable
private fun SwitchSettingRowPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        SwitchSettingRow("Glove mode", checked = true, onCheckedChange = {}, summary = "Larger touch targets")
        SwitchSettingRow("Haptics", checked = false, onCheckedChange = {})
    }
}

@Preview(widthDp = 1000)
@Composable
private fun SelectSettingRowPreview() = ThemePreviews {
    SelectSettingRow("Units", options = listOf("Metric", "Imperial"), selected = "Metric", optionLabel = { it }, onSelect = {})
}

@Preview(widthDp = 1000)
@Composable
private fun NumberSettingRowPreview() = ThemePreviews {
    NumberSettingRow(
        "Return altitude",
        value = 60,
        onValueChange = {},
        range = 10..120,
        step = 5,
        decrementDescription = "Decrease",
        incrementDescription = "Increase",
        format = { "$it m" },
    )
}

@Preview(widthDp = 1000)
@Composable
private fun TextSettingRowPreview() = ThemePreviews {
    TextSettingRow("Agent host", value = "192.168.1.50", onValueChange = {})
}
