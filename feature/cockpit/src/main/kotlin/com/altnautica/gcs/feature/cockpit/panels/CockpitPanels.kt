package com.altnautica.gcs.feature.cockpit.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosMotion
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.StripValues
import com.altnautica.gcs.feature.cockpit.settings.AltitudeReference
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
import com.altnautica.gcs.feature.cockpit.settings.CockpitSettings
import kotlin.math.roundToInt

private const val DASH = "—"

/** Stream tabs under the band (CS-11); shown only with more than one stream. */
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

/** CS-4 telemetry strip: distance and bearing home, climb, throttle, waypoint, satellites/HDOP. */
@Composable
fun TelemetryStrip(values: StripValues, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val items = listOf(
        stringResource(R.string.cockpit_strip_dist) to (values.homeDistanceM?.let { "${it.roundToInt()} m" } ?: DASH),
        stringResource(R.string.cockpit_strip_home) to (values.homeBearingDeg?.let { "%03d°".format(it.roundToInt() % 360) } ?: DASH),
        stringResource(R.string.cockpit_strip_vs) to (values.climbMs?.let { "%+.1f m/s".format(it) } ?: DASH),
        stringResource(R.string.cockpit_strip_thr) to (values.throttlePct?.let { "$it%" } ?: DASH),
        stringResource(R.string.cockpit_strip_wp) to (
            if (values.missionSeq != null && values.missionTotal != null && values.missionTotal > 0) "${values.missionSeq}/${values.missionTotal}" else DASH
            ),
        stringResource(R.string.cockpit_strip_sats) to (
            values.satellites?.let { sats -> "$sats" + (values.hdop?.let { " · %.1f".format(it) } ?: "") } ?: DASH
            ),
    )
    val colors = AdosTheme.colors
    val cell: @Composable (Pair<String, String>) -> Unit = { (label, value) ->
        Column(Modifier.padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.hudMuted)
            Text(value, style = AdosTypography.numeric.medium, color = colors.hudInk)
        }
    }
    Surface(modifier, shape = RoundedCornerShape(AdosRadius.Xl), color = colors.hudGlass, border = BorderStroke(1.dp, colors.hudHair)) {
        if (vertical) Column { items.forEach { cell(it) } } else Row { items.forEach { cell(it) } }
    }
}

/** Cockpit quick settings, sliding in from the right edge. [panels] hosts extension `cockpit.panel` slots. */
@Composable
fun QuickSettingsPanel(
    open: Boolean,
    settings: CockpitSettings,
    maxBrightness: Boolean,
    showTelemetryPaneToggle: Boolean,
    canRecord: Boolean,
    recording: Boolean,
    pipAvailable: Boolean,
    pipOn: Boolean,
    onDismiss: () -> Unit,
    onAltitudeReference: (AltitudeReference) -> Unit,
    onDensity: (CockpitDensity) -> Unit,
    onMaxBrightness: (Boolean) -> Unit,
    onTelemetryPane: (Boolean) -> Unit,
    onToggleRecording: () -> Unit,
    onTogglePip: () -> Unit,
    panels: @Composable () -> Unit,
) {
    val colors = AdosTheme.colors
    val reduced = rememberReducedMotion()
    val duration = if (reduced) 0 else AdosMotion.QuickMs
    if (open) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.mediaScrim.copy(alpha = 0.3f))
                .clickable(onClickLabel = stringResource(R.string.cockpit_close), onClick = onDismiss),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(duration, easing = AdosMotion.Standard)) { it },
            exit = slideOutHorizontally(tween(duration, easing = AdosMotion.Standard)) { it },
        ) {
            Surface(
                modifier = Modifier.fillMaxHeight().width(340.dp),
                color = colors.bgSurface.copy(alpha = 0.96f),
                contentColor = colors.fgPrimary,
                shape = RoundedCornerShape(topStart = AdosRadius.Xl, bottomStart = AdosRadius.Xl),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = AdosSpacing.S3)) {
                    SectionHeader(stringResource(R.string.cockpit_quick_settings))
                    val rel = stringResource(R.string.cockpit_alt_rel)
                    val msl = stringResource(R.string.cockpit_alt_msl)
                    SelectSettingRow(
                        title = stringResource(R.string.cockpit_qs_altitude_reference),
                        options = AltitudeReference.entries,
                        selected = settings.altitudeReference,
                        optionLabel = { if (it == AltitudeReference.Rel) rel else msl },
                        onSelect = onAltitudeReference,
                    )
                    val densityLabels = mapOf(
                        CockpitDensity.Min to stringResource(R.string.cockpit_density_min),
                        CockpitDensity.Std to stringResource(R.string.cockpit_density_std),
                        CockpitDensity.Full to stringResource(R.string.cockpit_density_full),
                    )
                    SelectSettingRow(
                        title = stringResource(R.string.cockpit_qs_density),
                        options = CockpitDensity.entries,
                        selected = settings.density,
                        optionLabel = { densityLabels.getValue(it) },
                        onSelect = onDensity,
                    )
                    SwitchSettingRow(stringResource(R.string.cockpit_qs_max_brightness), maxBrightness, onMaxBrightness)
                    if (showTelemetryPaneToggle) {
                        SwitchSettingRow(stringResource(R.string.cockpit_qs_telemetry_pane), settings.telemetryPane, onTelemetryPane)
                    }
                    if (pipAvailable) {
                        SwitchSettingRow(stringResource(R.string.cockpit_qs_pip), pipOn, { onTogglePip() })
                    }
                    if (canRecord) {
                        AdosButton(
                            label = stringResource(if (recording) R.string.cockpit_qs_stop_recording else R.string.cockpit_qs_start_recording),
                            onClick = onToggleRecording,
                            variant = if (recording) AdosButtonVariant.Danger else AdosButtonVariant.Secondary,
                            modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
                        )
                    }
                    panels()
                }
            }
        }
    }
}
