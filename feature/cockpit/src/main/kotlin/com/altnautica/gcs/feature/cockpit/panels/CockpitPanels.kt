package com.altnautica.gcs.feature.cockpit.panels

import com.altnautica.gcs.feature.cockpit.flow.specText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommand
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandHandler
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettings


/** Stream tabs under the band; shown only with more than one stream. */
@Composable
fun StreamTabs(
    streams: List<StreamDescriptor>,
    selectedId: String?,
    switchingId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (streams.size < 2) return
    val colors = AdosTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        streams.forEachIndexed { i, s ->
            val selected = s.id == selectedId
            val switching = s.id == switchingId
            var shimmer by remember(switching) { mutableFloatStateOf(1f) }
            LaunchedEffect(switching) {
                if (!switching) return@LaunchedEffect
                while (true) {
                    shimmer = if (shimmer > 0.6f) 0.45f else 1f
                    kotlinx.coroutines.delay(300)
                }
            }
            Surface(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .alpha(if (switching) shimmer else 1f)
                    .semantics { this.selected = selected }
                    .clickable(enabled = s.live, role = Role.Tab) { onSelect(s.id) },
                shape = RoundedCornerShape(AdosRadius.Md),
                color = if (selected) colors.hudPrimary.copy(alpha = 0.25f) else colors.hudGlass,
                contentColor = colors.hudInk,
                border = BorderStroke(1.dp, if (selected) colors.hudPrimary else colors.hudHair),
            ) {
                Text(
                    "${i + 1} · ${s.role.ifBlank { s.id }}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S1),
                    color = if (s.live) colors.hudInk else colors.hudMuted,
                )
            }
        }
    }
}


/**
 * Cockpit quick settings in a side sheet, so it traps controller focus and closes with
 * Back like every other overlay. [panels] hosts extension `cockpit.panel` slots.
 */
@Composable
fun QuickSettingsPanel(
    open: Boolean,
    settings: CockpitSettings,
    maxBrightness: Boolean,
    showTelemetryPaneToggle: Boolean,
    /** Stick control in every mode for the flown vehicle; null hides the row (no vehicle). */
    stickAllModes: Boolean?,
    onDismiss: () -> Unit,
    onAltitudeReference: (AltitudeReference) -> Unit,
    onDensity: (CockpitDensity) -> Unit,
    onMaxBrightness: (Boolean) -> Unit,
    onTelemetryPane: (Boolean) -> Unit,
    onStickAllModes: (Boolean) -> Unit,
    panels: @Composable () -> Unit,
) {
    if (!open) return
    // The short Options press that opened quick settings also closes it.
    ConsoleCommandHandler { command ->
        if (command == ConsoleCommand.Menu) onDismiss()
        command == ConsoleCommand.Menu
    }
    AdosSheet(onDismiss = onDismiss, title = specText("cockpit.quick_settings")) {
        val rel = specText("cockpit.qs.alt_rel")
        val msl = specText("cockpit.qs.alt_msl")
        SelectSettingRow(
            title = specText("cockpit.qs.alt_ref"),
            options = AltitudeReference.entries,
            selected = settings.altitudeReference,
            optionLabel = { if (it == AltitudeReference.Rel) rel else msl },
            onSelect = onAltitudeReference,
        )
        val densityLabels = mapOf(
            CockpitDensity.Min to specText("cockpit.qs.density.min"),
            CockpitDensity.Std to specText("cockpit.qs.density.std"),
            CockpitDensity.Full to specText("cockpit.level.full"),
        )
        SelectSettingRow(
            title = specText("cockpit.qs.density"),
            options = CockpitDensity.entries,
            selected = settings.density,
            optionLabel = { densityLabels.getValue(it) },
            onSelect = onDensity,
        )
        SwitchSettingRow(specText("cockpit.qs.max_brightness"), maxBrightness, onMaxBrightness)
        if (showTelemetryPaneToggle) {
            SwitchSettingRow(specText("cockpit.qs.telemetry_pane"), settings.telemetryPane, onTelemetryPane)
        }
        if (stickAllModes != null) StickAllModesSetting(stickAllModes, onStickAllModes)
        panels()
    }
}
