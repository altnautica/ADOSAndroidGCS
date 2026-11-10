package com.altnautica.gcs.feature.cockpit.chip

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.flow.specText

/** A spec colour (0xAARRGGBB) as a Compose colour. */
fun specColor(argb: Long): Color = Color(argb.toInt())

/** The icon paired with each chip rule, so the state never rests on colour alone. */
fun chipIcon(ruleId: String): ImageVector = when (ruleId) {
    "link_stale" -> Icons.Outlined.LinkOff
    "idle_ready" -> Icons.Outlined.CheckCircle
    "idle_not_ready" -> Icons.Outlined.ErrorOutline
    "pilot" -> Icons.Outlined.SportsEsports
    "skill" -> Icons.Outlined.AutoAwesome
    "goto" -> Icons.Outlined.NearMe
    "swarm" -> Icons.Outlined.Hub
    "tuning" -> Icons.Outlined.Tune
    "mission_paused", "mission_active", "mission_complete", "mission_fc" -> Icons.Outlined.Route
    "vtol_transition" -> Icons.Outlined.Sync
    "fc_rth" -> Icons.Outlined.Home
    "fc_land" -> Icons.Outlined.FlightLand
    else -> Icons.Outlined.SettingsRemote
}

/**
 * The flight-state chip: owner and mode in its state colour, the secondary line below, and
 * the rule's button. Tapping the chip opens the Mode sheet; the chip dims while the pilot
 * state is stale.
 */
@Composable
fun FlightStateChip(
    ui: FlightStateChipUi,
    onOpen: () -> Unit,
    onButton: (ChipButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val tint = specColor(ui.primary.color)
    val openLabel = stringResource(R.string.flight_chip_open_mode_sheet)
    Surface(
        modifier = modifier
            .testTag(FLIGHT_CHIP_TAG)
            .defaultMinSize(minHeight = AdosTheme.touchMin)
            .graphicsLayer {
                alpha = if (ui.dim) DIM_ALPHA else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
        shape = RoundedCornerShape(AdosRadius.Md),
        color = colors.hudGlassStrong,
        border = BorderStroke(1.5.dp, tint),
        contentColor = colors.hudInk,
    ) {
        Row(
            Modifier.padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            Icon(chipIcon(ui.ruleId), contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconMd))
            Column {
                Text(
                    ui.primary.text,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.secondary.isNotEmpty()) {
                    Row {
                        ui.secondary.forEachIndexed { i, line ->
                            if (i > 0) Text(SEPARATOR, style = MaterialTheme.typography.labelSmall, color = colors.hudMuted)
                            Text(line.text, style = MaterialTheme.typography.labelSmall, color = specColor(line.color), maxLines = 1)
                        }
                    }
                }
            }
            ui.button?.let { b ->
                Spacer(Modifier.width(AdosSpacing.S1))
                ChipActionButton(b, tint) { onButton(b) }
            }
        }
    }
}

@Composable
private fun ChipActionButton(button: ChipButton, tint: Color, onClick: () -> Unit) {
    val label = when (button) {
        ChipButton.SetUpAircraft -> stringResource(R.string.flight_chip_set_up_aircraft)
        ChipButton.Resume -> stringResource(R.string.flight_chip_resume)
        ChipButton.TakeControl -> stringResource(R.string.flight_chip_take_control)
        ChipButton.Abort -> specText("flow.launch.abort")
    }
    Surface(
        modifier = Modifier
            .defaultMinSize(minHeight = AdosTheme.touchMin, minWidth = AdosTheme.touchMin)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(AdosRadius.Sm),
        color = tint.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, tint),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = AdosSpacing.S2, vertical = AdosSpacing.S1),
            maxLines = 1,
        )
    }
}

private const val SEPARATOR = " · "
private const val DIM_ALPHA = 0.55f
const val FLIGHT_CHIP_TAG = "flight_state_chip"
