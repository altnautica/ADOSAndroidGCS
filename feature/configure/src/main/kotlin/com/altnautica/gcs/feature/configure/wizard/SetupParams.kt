package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.ui.draw.alpha
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Test tag of a step's planned-parameter table. */
const val SETUP_PARAMS_TAG = "setup-params"

/**
 * A flight-controller parameter a step will write (`args.params`): its [name], the [value] to
 * write and [have], the flight controller's current value (null when the step does not say).
 */
data class SetupParamRow(val name: String, val value: JsonPrimitive, val have: JsonPrimitive? = null) {
    /** The write changes nothing: the flight controller already holds [value]. */
    val unchanged: Boolean get() = have != null && (
        have.content == value.content ||
            (have.content.toDoubleOrNull()?.let { h -> value.content.toDoubleOrNull()?.let { v -> abs(h - v) < SAME_EPS } } ?: false)
        )
}

private const val SAME_EPS = 1e-6

/** The parameters the step plans to write, in wire order; rows without a name or a scalar value are skipped. */
fun SetupStep.paramRows(): List<SetupParamRow> =
    (args["params"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return@mapNotNull null
        val value = (o["value"] as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return@mapNotNull null
        SetupParamRow(name, value, (o["have"] as? JsonPrimitive)?.takeIf { it !is JsonNull })
    }

/**
 * The values a step writes (`setup.<step>.params`), one row per parameter: its name, what it is
 * for where the spec says (`setup.<step>.param.<NAME>`, e.g. the flight controller's obstacle
 * backstop rows) and the change, current → new. A row already at its value is dimmed.
 */
@Composable
internal fun ParamTable(step: String, rows: List<SetupParamRow>, modifier: Modifier = Modifier) {
    val text = LocalSetupText.current
    val colors = AdosTheme.colors
    Column(
        modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(AdosRadius.Lg)).padding(AdosSpacing.S3).testTag(SETUP_PARAMS_TAG),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        text.known("setup.$step.params")?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary) }
        rows.forEach { row ->
            val dim = if (row.unchanged) UNCHANGED_ALPHA else 1f
            Row(
                Modifier.fillMaxWidth().alpha(dim).testTag("$SETUP_PARAMS_TAG-${row.name}"),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, color = colors.fgPrimary, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    text.known("setup.$step.param.${row.name}")?.let {
                        Text(it, color = colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                val change = row.have?.takeUnless { row.unchanged }?.let { "${it.displayText()} → " }.orEmpty()
                Text(change + row.value.displayText(), color = colors.fgPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** The spec's text for [key], or null when the strings do not have it. */
private fun SetupText.known(key: String): String? = format(key, emptyMap()).takeIf { it != key }

/** A row the write leaves as it is reads at this opacity. */
private const val UNCHANGED_ALPHA = 0.5f
