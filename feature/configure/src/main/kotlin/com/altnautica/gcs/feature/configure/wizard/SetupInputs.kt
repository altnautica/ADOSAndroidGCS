package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SetupInputKind { Number, Enum, Bool }

/** One operator input of a step (`args.inputs.<name>`); every action of the step sends all of them. */
data class SetupInput(
    val name: String,
    val kind: SetupInputKind,
    val labelKey: String,
    val initial: JsonElement?,
    val options: List<JsonElement> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    /** A picture per enum option (`images`, option value to setup-image key); empty without pictures. */
    val images: Map<String, String> = emptyMap(),
) {
    companion object {
        /** The inputs a step declares, in wire order. */
        fun of(step: SetupStep): List<SetupInput> {
            val inputs = step.args[INPUTS_KEY] as? JsonObject ?: return emptyList()
            return inputs.mapNotNull { (name, spec) ->
                val o = spec as? JsonObject ?: return@mapNotNull null
                val kind = when (o.str("kind")) {
                    "number" -> SetupInputKind.Number
                    "enum" -> SetupInputKind.Enum
                    "bool" -> SetupInputKind.Bool
                    else -> return@mapNotNull null
                }
                SetupInput(
                    name = name,
                    kind = kind,
                    labelKey = o.str("label_key") ?: name,
                    initial = o["value"],
                    options = (o["options"] as? kotlinx.serialization.json.JsonArray).orEmpty(),
                    min = o.num("min"),
                    max = o.num("max"),
                    unit = o.str("unit"),
                    images = (o["images"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it }
                    }.toMap(),
                )
            }
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    }
}

const val INPUTS_KEY = "inputs"

/** The operator's current input values for one step. */
@Stable
class SetupInputState(val inputs: List<SetupInput>) {
    /** Number inputs are kept as typed text until they parse. */
    val values = mutableStateMapOf<String, String>().apply {
        inputs.forEach { put(it.name, it.initial?.let { v -> (v as? JsonPrimitive)?.contentOrNull } ?: "") }
    }

    fun valid(input: SetupInput): Boolean {
        val raw = values[input.name].orEmpty()
        return when (input.kind) {
            SetupInputKind.Number -> raw.toDoubleOrNull()?.let { v ->
                (input.min == null || v >= input.min) && (input.max == null || v <= input.max)
            } ?: false
            SetupInputKind.Enum -> input.options.any { (it as? JsonPrimitive)?.contentOrNull == raw }
            SetupInputKind.Bool -> raw == "true" || raw == "false"
        }
    }

    val ready: Boolean get() = inputs.all(::valid)

    /** `{<name>: value}` for every input, or null when the step has none. */
    fun args(): JsonObject? {
        if (inputs.isEmpty()) return null
        return JsonObject(
            inputs.associate { input ->
                val raw = values[input.name].orEmpty()
                input.name to when (input.kind) {
                    SetupInputKind.Number -> JsonPrimitive(raw.toDouble())
                    SetupInputKind.Bool -> JsonPrimitive(raw == "true")
                    SetupInputKind.Enum -> input.options.first { (it as? JsonPrimitive)?.contentOrNull == raw }
                }
            },
        )
    }
}

@Composable
fun rememberSetupInputs(step: SetupStep): SetupInputState {
    val inputs = SetupInput.of(step)
    return remember(step.wizard, step.step, inputs.map { it.name }) { SetupInputState(inputs) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupInputFields(state: SetupInputState, modifier: Modifier = Modifier) {
    if (state.inputs.isEmpty()) return
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        state.inputs.forEach { input ->
            val label = text.format(input.labelKey, emptyMap()) + (input.unit?.let { " ($it)" } ?: "")
            val value = state.values[input.name].orEmpty()
            when (input.kind) {
                SetupInputKind.Number -> OutlinedTextField(
                    value = value,
                    onValueChange = { state.values[input.name] = it.trim() },
                    label = { Text(text.format(input.labelKey, emptyMap())) },
                    suffix = input.unit?.let { u -> { Text(u) } },
                    supportingText = rangeText(input)?.let { r -> { Text(r) } },
                    isError = value.isNotEmpty() && !state.valid(input),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("setup-input-${input.name}"),
                )
                SetupInputKind.Enum -> if (input.images.isNotEmpty()) {
                    PicturePicker(input, value, label) { state.values[input.name] = it }
                } else Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                        input.options.forEach { option ->
                            val raw = option.jsonPrimitive.content
                            FilterChip(
                                selected = raw == value,
                                onClick = { state.values[input.name] = raw },
                                label = { Text(text.format("${input.labelKey}.$raw", emptyMap())) },
                            )
                        }
                    }
                }
                SetupInputKind.Bool -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = colors.fgPrimary)
                    Switch(
                        checked = value == "true" || (value.isEmpty() && (input.initial as? JsonPrimitive)?.booleanOrNull == true),
                        onCheckedChange = { state.values[input.name] = it.toString() },
                    )
                }
            }
        }
    }
}

/** "{min} to {max} {unit}" under a number field, when the input gives both bounds. */
@Composable
private fun rangeText(input: SetupInput): String? {
    val min = input.min ?: return null
    val max = input.max ?: return null
    return LocalSetupText.current.format(
        "setup.overview.range",
        mapOf("min" to JsonPrimitive(min).displayText(), "max" to JsonPrimitive(max).displayText(), "unit" to input.unit.orEmpty()),
    ).trim()
}

/**
 * An enum input with a picture per option (the frame layouts): a grid of tiles, each the
 * option's setup image over its label; the selected tile carries the primary outline. Options
 * without a picture still show, by label. Each tile is at least 96 dp.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PicturePicker(input: SetupInput, value: String, label: String, onPick: (String) -> Unit) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            input.options.forEach { option ->
                val raw = option.jsonPrimitive.content
                val selected = raw == value
                val name = text.format("${input.labelKey}.$raw", emptyMap())
                val shape = RoundedCornerShape(AdosRadius.Md)
                Column(
                    Modifier
                        .width(PICTURE_TILE)
                        .heightIn(min = PICTURE_TILE)
                        .clip(shape)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accentPrimary else colors.borderDefault, shape)
                        .selectable(selected = selected, role = Role.RadioButton) { onPick(raw) }
                        .padding(AdosSpacing.S2)
                        .testTag("setup-picture-${input.name}-$raw"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    input.images[raw]?.let { key -> SetupImage(key, name, colors.fgPrimary, Modifier.size(PICTURE_TILE - 24.dp)) }
                    Text(name, style = MaterialTheme.typography.labelMedium, color = if (selected) colors.accentPrimary else colors.fgPrimary, maxLines = 2)
                }
            }
        }
    }
}

private val PICTURE_TILE = 112.dp
