package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.extensions.R
import com.altnautica.gcs.core.extensions.data.RosterCamera
import com.altnautica.gcs.core.extensions.parameters.ExtensionParametersViewModel
import com.altnautica.gcs.core.extensions.parameters.ParameterBinding
import com.altnautica.gcs.core.extensions.parameters.ParameterRules
import com.altnautica.gcs.core.extensions.parameters.ParameterType
import com.altnautica.gcs.core.extensions.parameters.ParameterWidget
import com.altnautica.gcs.core.extensions.parameters.ParametersUiState
import com.altnautica.gcs.core.extensions.parameters.PluginParameter
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Native renderer for an extension's declared parameters, with read-back from the node.
 * Values are validated here and again by the agent on write.
 */
@Composable
fun ExtensionParametersPanel(
    node: DeviceId,
    pluginId: String,
    parameters: List<PluginParameter>,
    modifier: Modifier = Modifier,
    sectionKey: String = "parameters",
    onWriteError: (String) -> Unit = {},
) {
    val vm: ExtensionParametersViewModel = hiltViewModel(key = "extension-params:${node.value}:$pluginId:$sectionKey")
    LaunchedEffect(vm, node, pluginId, parameters) { vm.start(node, pluginId, parameters) }
    LaunchedEffect(vm) { vm.writeErrors.collect { onWriteError(it.message) } }
    val state by vm.state.collectAsStateWithLifecycle()
    ParametersContent(state, onCommit = vm::commit, modifier = modifier)
}

@Composable
internal fun ParametersContent(
    state: ParametersUiState,
    onCommit: (PluginParameter, JsonPrimitive) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.groups.isEmpty()) {
            Text(stringResource(R.string.extensions_no_parameters), style = MaterialTheme.typography.bodyMedium)
        }
        for (g in state.groups) {
            val visible = g.items.filter(state::isVisible)
            if (visible.isEmpty()) continue
            g.title?.let { SectionHeader(displayLabel(it, it)) }
            for (p in visible) {
                ParameterControl(p, state, onCommit)
            }
        }
    }
}

@Composable
private fun ParameterControl(p: PluginParameter, state: ParametersUiState, onCommit: (PluginParameter, JsonPrimitive) -> Unit) {
    val title = displayLabel(p.ui?.label, p.key)
    val value = state.values[p.key] ?: ParameterRules.defaultFor(p.schema)
    val writable = p.binding == ParameterBinding.PluginConfig && p.key !in state.saving
    val summary = when {
        p.binding != ParameterBinding.PluginConfig -> stringResource(R.string.extensions_read_only)
        p.key !in state.confirmed -> stringResource(R.string.extensions_unconfirmed_default)
        else -> p.ui?.help?.let { displayLabel(it, it) }
    }
    when (ParameterRules.inferWidget(p.schema, p.ui)) {
        ParameterWidget.Boolean -> SwitchSettingRow(
            title = title,
            checked = value.booleanOrNull == true,
            onCheckedChange = { onCommit(p, JsonPrimitive(it)) },
            summary = summary,
            enabled = writable,
        )
        ParameterWidget.Enum -> {
            val options = p.schema.enum.orEmpty()
            SelectSettingRow(
                title = title,
                options = options,
                selected = options.firstOrNull { ParameterRules.sameScalar(it, value) } ?: options.firstOrNull() ?: value,
                optionLabel = { it.content },
                onSelect = { onCommit(p, it) },
                summary = summary,
                enabled = writable,
            )
        }
        ParameterWidget.Range -> RangeControl(p, title, summary, value, writable, onCommit)
        ParameterWidget.Number -> TextControl(p, title, summary, value, writable, numeric = true, onCommit = onCommit)
        ParameterWidget.Str -> TextControl(p, title, summary, value, writable, numeric = false, onCommit = onCommit)
        ParameterWidget.Bitmask -> BitmaskControl(p, title, summary, value, writable, onCommit)
        ParameterWidget.Camera -> CameraControl(p, title, summary, value, writable, state.cameras, onCommit)
        ParameterWidget.Group -> SectionHeader(title)
        ParameterWidget.Model, ParameterWidget.ModelUpload -> ReadOnlyRow(title, value.content, summary)
    }
}

@Composable
private fun ReadOnlyRow(title: String, value: String, summary: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium)
        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RangeControl(
    p: PluginParameter,
    title: String,
    summary: String?,
    value: JsonPrimitive,
    enabled: Boolean,
    onCommit: (PluginParameter, JsonPrimitive) -> Unit,
) {
    val min = (p.schema.minimum ?: 0.0).toFloat()
    val max = (p.schema.maximum ?: 100.0).toFloat()
    var pos by remember(value) { mutableFloatStateOf((value.doubleOrNull ?: min.toDouble()).toFloat()) }
    val steps = p.schema.step?.let { s -> (((max - min) / s).toInt() - 1).coerceIn(0, 200) } ?: 0
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(formatNumber(pos.toDouble(), p.schema.type), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = pos.coerceIn(min, max),
            onValueChange = { pos = it },
            onValueChangeFinished = { onCommit(p, JsonPrimitive(pos.toDouble())) },
            valueRange = min..max,
            steps = steps,
            enabled = enabled,
        )
        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TextControl(
    p: PluginParameter,
    title: String,
    summary: String?,
    value: JsonPrimitive,
    enabled: Boolean,
    numeric: Boolean,
    onCommit: (PluginParameter, JsonPrimitive) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(if (numeric) value.doubleOrNull?.let { formatNumber(it, p.schema.type) } ?: "" else value.content)
    }
    val commit = {
        if (numeric) {
            text.trim().toDoubleOrNull()?.let { onCommit(p, JsonPrimitive(it)) }
        } else {
            onCommit(p, JsonPrimitive(text))
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(title) },
        supportingText = summary?.let { { Text(it) } },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BitmaskControl(
    p: PluginParameter,
    title: String,
    summary: String?,
    value: JsonPrimitive,
    enabled: Boolean,
    onCommit: (PluginParameter, JsonPrimitive) -> Unit,
) {
    val mask = value.doubleOrNull?.toLong() ?: 0L
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        for (b in p.ui?.bits.orEmpty()) {
            val on = mask and (1L shl b.bit) != 0L
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = on,
                    onCheckedChange = { checked ->
                        val next = if (checked) mask or (1L shl b.bit) else mask and (1L shl b.bit).inv()
                        onCommit(p, JsonPrimitive(next))
                    },
                    enabled = enabled,
                )
                Text(displayLabel(b.label, b.label))
            }
        }
        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CameraControl(
    p: PluginParameter,
    title: String,
    summary: String?,
    value: JsonPrimitive,
    enabled: Boolean,
    cameras: List<RosterCamera>?,
    onCommit: (PluginParameter, JsonPrimitive) -> Unit,
) {
    val auto = stringResource(R.string.extensions_auto_camera)
    val ordered = cameraOptions(cameras.orEmpty(), p.ui?.purpose)
    val options = listOf(AUTO) + ordered.map { it.id }
    val selected = value.content.takeIf { it in options } ?: AUTO
    SelectSettingRow(
        title = title,
        options = options,
        selected = selected,
        optionLabel = { id -> if (id == AUTO) auto else ordered.firstOrNull { it.id == id }?.name ?: id },
        onSelect = { onCommit(p, JsonPrimitive(it)) },
        summary = summary,
        enabled = enabled && cameras != null,
    )
}

private const val AUTO = "auto"

/** Enabled cameras with the widget's purpose first, then the rest, roster order kept. */
internal fun cameraOptions(cameras: List<RosterCamera>, purpose: String?): List<RosterCamera> {
    val enabled = cameras.filter { it.enabled }
    if (purpose == null) return enabled
    val (match, rest) = enabled.partition { purpose in it.purposes }
    return match + rest
}

private fun formatNumber(v: Double, type: ParameterType): String =
    if (type == ParameterType.Integer || v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
