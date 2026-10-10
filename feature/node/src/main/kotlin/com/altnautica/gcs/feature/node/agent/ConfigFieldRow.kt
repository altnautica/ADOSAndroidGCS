package com.altnautica.gcs.feature.node.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import kotlinx.serialization.json.JsonElement

/** One schema-typed config field: switch, choice, number, text, secret or JSON. */
@Composable
fun ConfigFieldRow(
    leaf: ConfigNode.Leaf,
    value: JsonElement?,
    error: ConfigValueCodec.Reason?,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val type = leaf.type) {
        FieldType.Bool -> SwitchSettingRow(
            title = leaf.title,
            checked = ConfigValueCodec.boolValue(value),
            onCheckedChange = { onCommit(it.toString()) },
            summary = leaf.description,
            enabled = enabled,
            modifier = modifier,
        )
        is FieldType.Choice -> {
            val current = ConfigValueCodec.display(leaf, value)
            val options = if (current.isEmpty() || current in type.options) type.options else listOf(current) + type.options
            SelectSettingRow(
                title = leaf.title,
                options = options,
                selected = current.ifEmpty { options.first() },
                optionLabel = { it },
                onSelect = { if (it != current) onCommit(it) },
                summary = leaf.description,
                enabled = enabled,
                modifier = modifier,
            )
        }
        else -> TextFieldRow(leaf, value, error, enabled, onCommit, modifier)
    }
}

@Composable
private fun TextFieldRow(
    leaf: ConfigNode.Leaf,
    value: JsonElement?,
    error: ConfigValueCodec.Reason?,
    enabled: Boolean,
    onCommit: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = AdosTheme.colors
    val current = ConfigValueCodec.display(leaf, value)
    var text by rememberSaveable(leaf.key, current) { mutableStateOf(current) }
    val dirty = if (leaf.secret) text.isNotEmpty() else text != current
    val keyboard = when (leaf.type) {
        is FieldType.Integer -> KeyboardType.Number
        is FieldType.Decimal -> KeyboardType.Decimal
        else -> if (leaf.secret) KeyboardType.Password else KeyboardType.Text
    }
    Column(modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(leaf.title) },
                placeholder = if (leaf.secret) {
                    { Text(stringResource(if (ConfigValueCodec.secretIsSet(value)) R.string.config_secret_set else R.string.config_secret_unset)) }
                } else {
                    null
                },
                singleLine = leaf.type !is FieldType.Json,
                minLines = if (leaf.type is FieldType.Json) 3 else 1,
                enabled = enabled,
                isError = error != null,
                visualTransformation = if (leaf.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                supportingText = error?.let { e -> { Text(reasonText(e, leaf.type)) } },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onCommit(text) }, enabled = enabled && dirty) {
                Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.config_save, leaf.title))
            }
        }
        leaf.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary) }
    }
}

@Composable
private fun reasonText(reason: ConfigValueCodec.Reason, type: FieldType): String = when (reason) {
    ConfigValueCodec.Reason.NotANumber -> stringResource(R.string.config_error_number)
    ConfigValueCodec.Reason.BelowMinimum -> stringResource(R.string.config_error_min, bound(type, min = true))
    ConfigValueCodec.Reason.AboveMaximum -> stringResource(R.string.config_error_max, bound(type, min = false))
    ConfigValueCodec.Reason.NotAnOption -> stringResource(R.string.config_error_option)
    ConfigValueCodec.Reason.NotJson -> stringResource(R.string.config_error_json)
}

private fun bound(type: FieldType, min: Boolean): String = when (type) {
    is FieldType.Integer -> (if (min) type.min else type.max)?.toString().orEmpty()
    is FieldType.Decimal -> (if (min) type.min else type.max)?.toString().orEmpty()
    else -> ""
}
