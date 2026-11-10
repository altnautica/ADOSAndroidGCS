package com.altnautica.gcs.feature.cockpit.band

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.SatelliteAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.NavChip
import com.altnautica.gcs.feature.cockpit.autonomy.NavInputs
import com.altnautica.gcs.feature.cockpit.autonomy.NavLabel
import com.altnautica.gcs.feature.cockpit.autonomy.NavSource
import com.altnautica.gcs.feature.cockpit.autonomy.NavTone
import com.altnautica.gcs.feature.cockpit.autonomy.TargetText
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.ChromeChip

/**
 * Safety-band nav-source chip: what the EKF navigates on (icon, label, quality word),
 * coloured by quality. Compact shows the label only; the popover lists source sets 1-3,
 * the readouts, and a hold-to-confirm switch per set.
 */
@Composable
internal fun NavSourceChip(chip: NavChip?, inputs: NavInputs?, compact: Boolean, onUseSet: (Int) -> Unit) {
    val chipValue = chip ?: NavChip(NavLabel.Stale, null, NavTone.Muted)
    val text = TargetText(LocalCockpitStrings.current)
    var open by remember { mutableStateOf(false) }
    val icon = when (chipValue.label) {
        NavLabel.DeadReckoning -> Icons.Outlined.Explore
        NavLabel.ExtNav -> Icons.Outlined.Radar
        NavLabel.Gps -> Icons.Outlined.SatelliteAlt
        NavLabel.Flow -> Icons.Outlined.Air
        NavLabel.Stale -> Icons.Outlined.GpsOff
    }
    val tone = when (chipValue.tone) {
        NavTone.Good -> ChipTone.Good
        NavTone.Warn -> ChipTone.Warn
        NavTone.Crit -> ChipTone.Crit
        NavTone.Muted -> ChipTone.Neutral
    }
    val label = text.navLabel(chipValue) + (chipValue.quality?.takeIf { !compact }?.let { " · " + text.quality(it) } ?: "")
    ChromeChip(icon, label, tone, onClick = { open = true }, modifier = Modifier.testTag("nav-source"))
    if (open) NavSourceSheet(chipValue, inputs, text, onUseSet) { open = false }
}

@Composable
private fun NavSourceSheet(chip: NavChip, inputs: NavInputs?, text: TargetText, onUseSet: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = AdosTheme.colors
    val params = inputs?.params.orEmpty()
    val mono = AdosTypography.numeric.small
    AdosSheet(onDismiss, title = specText("cockpit.status.source.nav_source")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S6), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Text(text.navLabel(chip) + (chip.quality?.let { " · " + text.quality(it) } ?: ""), style = MaterialTheme.typography.titleSmall)
            Row {
                Text("", Modifier.width(96.dp))
                for (field in listOf("nav_source.posxy", "nav_source.velxy", "nav_source.yaw")) {
                    Text(specText(field), Modifier.width(112.dp), style = mono, color = c.hudMuted)
                }
            }
            for (set in 1..3) {
                Row {
                    val active = set == chip.activeSet
                    Text(
                        specText("nav_source.set_n", "n" to set.toString()) + if (active) " · " + specText("nav_source.active") else "",
                        Modifier.width(96.dp),
                        style = mono,
                        color = if (active) c.hudGood else c.hudInk,
                    )
                    for (field in listOf("POSXY", "VELXY", "YAW")) {
                        Text(text.source(NavSource.sourceWord(params[NavSource.param(set, field)])), Modifier.width(112.dp), style = mono)
                    }
                }
            }
            inputs?.nav?.takeIf { inputs.navFresh }?.let { nav ->
                Text(
                    specText(
                        "nav_source.extnav_readout",
                        "health" to (nav.extnavHealth?.name?.uppercase() ?: "—"),
                        "hz" to (nav.odomRateHz?.let(HudGeometry::oneDecimal) ?: "—"),
                    ),
                    style = mono,
                )
            }
            Text(
                specText(
                    "nav_source.gps_readout",
                    "fix" to text.fixWord(inputs?.gpsFixType),
                    "sats" to (inputs?.sats?.toString() ?: "—"),
                    "hdop" to (inputs?.hdop?.let(HudGeometry::oneDecimal) ?: "—"),
                ),
                style = mono,
            )
            Text(specText("nav_source.ekf") + " " + flagWords(inputs?.ekfFlags), style = mono)
            if (inputs?.nav?.mode == "external_nav" && inputs.navFresh) {
                Text(specText("nav_source.auto_note"), style = MaterialTheme.typography.bodySmall, color = c.hudWarn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), modifier = Modifier.padding(vertical = AdosSpacing.S2)) {
                for (set in 1..3) {
                    AdosButton(specText("nav_source.use_set", "n" to set.toString()), onClick = {
                        onDismiss()
                        onUseSet(set)
                    })
                }
            }
        }
    }
}

@Composable
private fun flagWords(flags: Int?): String {
    if (flags == null) return "—"
    val words = listOf(
        NavSource.VEL_H to "nav_source.flag.vel_h",
        NavSource.POS_H_REL to "nav_source.flag.pos_rel",
        NavSource.POS_H_ABS to "nav_source.flag.pos_abs",
        NavSource.CONST_POS to "nav_source.flag.const_pos",
        NavSource.GPS_GLITCH to "nav_source.flag.glitch",
    ).filter { flags and it.first != 0 }.map { specText(it.second).uppercase() }
    return if (words.isEmpty()) "—" else words.joinToString(" · ")
}
