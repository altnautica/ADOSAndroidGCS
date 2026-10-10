package com.altnautica.gcs.feature.configure.params

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.configure.R

@Composable
fun invalidReasonText(reason: ParamInvalidReason, meta: ParamMeta?): String = when (reason) {
    ParamInvalidReason.NotANumber -> stringResource(R.string.cfg_param_invalid_number)
    ParamInvalidReason.NotInteger -> stringResource(R.string.cfg_param_invalid_integer)
    ParamInvalidReason.BelowMin -> stringResource(R.string.cfg_param_invalid_min, ParamFile.formatValue(meta?.min ?: 0.0))
    ParamInvalidReason.AboveMax -> stringResource(R.string.cfg_param_invalid_max, ParamFile.formatValue(meta?.max ?: 0.0))
    ParamInvalidReason.NotAnOption -> stringResource(R.string.cfg_param_invalid_option)
    ParamInvalidReason.UnknownBits -> stringResource(R.string.cfg_param_invalid_bits)
    ParamInvalidReason.ReadOnly -> stringResource(R.string.cfg_param_read_only)
}

/** Label for a value: the enum option name when documented, plus units. */
fun ParamRow.displayValue(): String {
    val option = meta?.options?.firstOrNull { it.value == value }?.label
    val number = ParamFile.formatValue(value)
    return when {
        option != null -> "$option ($number)"
        meta?.units != null -> "$number ${meta.units}"
        else -> number
    }
}

/** Bottom-sheet editor choosing the widget from the parameter's documentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamEditorSheet(row: ParamRow, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = AdosTheme.colors
    val meta = row.meta
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.bgSurface, contentColor = colors.fgPrimary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6, vertical = AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            Text(row.name, style = MaterialTheme.typography.titleLarge)
            meta?.let {
                if (it.humanName != row.name) Text(it.humanName, style = MaterialTheme.typography.titleSmall, color = colors.fgSecondary)
                if (it.description.isNotBlank()) Text(it.description, style = MaterialTheme.typography.bodySmall, color = colors.fgSecondary)
                if (it.rebootRequired) Text(stringResource(R.string.cfg_param_reboot), style = MaterialTheme.typography.bodySmall, color = colors.statusWarning)
            }
            if (meta?.readOnly == true) {
                Text(stringResource(R.string.cfg_param_read_only), color = colors.fgTertiary)
                return@Column
            }
            when (meta?.editor) {
                ParamEditorKind.Bitmask -> BitmaskEditor(row.value, meta, onSubmit)
                ParamEditorKind.Enum -> if (meta.min == null && meta.max == null) {
                    EnumEditor(row.value, meta, onSubmit)
                } else {
                    NumberEditor(row, meta, onSubmit)
                }
                else -> NumberEditor(row, meta, onSubmit)
            }
        }
    }
}

@Composable
private fun NumberEditor(row: ParamRow, meta: ParamMeta?, onSubmit: (String) -> Unit) {
    var text by remember(row.name) { mutableStateOf(ParamFile.formatValue(row.value)) }
    val validation = ParamValidator.validate(meta, text)
    val error = (validation as? ParamValidation.Invalid)?.reason
    val hint = listOfNotNull(
        meta?.min?.let { stringResource(R.string.cfg_param_min, ParamFile.formatValue(it)) },
        meta?.max?.let { stringResource(R.string.cfg_param_max, ParamFile.formatValue(it)) },
        meta?.units,
        meta?.defaultValue?.let { stringResource(R.string.cfg_param_default, ParamFile.formatValue(it)) },
    ).joinToString(" · ")
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        supportingText = { Text(if (error != null) invalidReasonText(error, meta) else hint) },
    )
    meta?.options?.takeIf { it.isNotEmpty() }?.let { options ->
        options.forEach { o ->
            Text(
                "${ParamFile.formatValue(o.value)} — ${o.label}",
                modifier = Modifier.fillMaxWidth().clickable { text = ParamFile.formatValue(o.value) }.padding(vertical = AdosSpacing.S1),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    AdosButton(stringResource(R.string.cfg_param_write), onClick = { onSubmit(text) }, enabled = error == null)
}

@Composable
private fun EnumEditor(value: Double, meta: ParamMeta, onSubmit: (String) -> Unit) {
    var selected by remember(meta.name) { mutableDoubleStateOf(value) }
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        meta.options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clickable { selected = o.value }.heightIn(min = AdosTheme.touchMin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == o.value, onClick = { selected = o.value })
                Text("${o.label} (${ParamFile.formatValue(o.value)})")
            }
        }
    }
    AdosButton(stringResource(R.string.cfg_param_write), onClick = { onSubmit(ParamFile.formatValue(selected)) })
}

@Composable
private fun BitmaskEditor(value: Double, meta: ParamMeta, onSubmit: (String) -> Unit) {
    var current by remember(meta.name) { mutableDoubleStateOf(value) }
    Text(stringResource(R.string.cfg_param_value, ParamFile.formatValue(current)), color = AdosTheme.colors.fgSecondary)
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        meta.bits.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clickable { current = ParamValidator.toggleBit(current, b.bit) }.heightIn(min = AdosTheme.touchMin),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = ParamValidator.isBitSet(current, b.bit), onCheckedChange = { current = ParamValidator.toggleBit(current, b.bit) })
                Text("${b.bit}: ${b.label}")
            }
        }
    }
    val invalid = ParamValidator.validate(meta, current) as? ParamValidation.Invalid
    invalid?.let { Text(invalidReasonText(it.reason, meta), color = AdosTheme.colors.statusError) }
    AdosButton(stringResource(R.string.cfg_param_write), onClick = { onSubmit(ParamFile.formatValue(current)) }, enabled = invalid == null)
}
