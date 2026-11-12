package com.altnautica.gcs.feature.cockpit.chip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.chrome.STATUS_DOT
import com.altnautica.gcs.feature.cockpit.flow.specText

/** A spec colour (0xAARRGGBB) as a Compose colour. */
fun specColor(argb: Long): Color = Color(argb.toInt())

/**
 * The flight-state chip: an outline in its state colour with a leading dot, the owner and
 * mode in the chrome text, the secondary part inline after " · " in the dim text (it
 * truncates before the primary does), and the rule's button. Tapping the chip opens the
 * Mode sheet; the chip dims while the pilot state is stale.
 */
@Composable
fun FlightStateChip(
    ui: FlightStateChipUi,
    onOpen: () -> Unit,
    onButton: (ChipButton) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalChrome.current
    val tint = specColor(ui.primary.color)
    val openLabel = specText("flow.chip.open_mode_sheet")
    val shape = RoundedCornerShape(c.radiusSmall)
    val secondary = ui.secondary.joinToString(SEPARATOR) { it.text }
    Row(
        modifier
            .testTag(FLIGHT_CHIP_TAG)
            .minimumInteractiveComponentSize()
            .height(c.control)
            .graphicsLayer {
                alpha = if (ui.dim) DIM_ALPHA else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .clip(shape)
            .border(1.dp, tint.copy(alpha = OUTLINE_ALPHA), shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .padding(start = c.pad, end = if (ui.button != null) 4.dp else c.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(STATUS_DOT).background(tint, CircleShape).testTag(FLIGHT_CHIP_DOT_TAG))
        Text(ui.primary.text, style = c.chipText.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secondary.isNotEmpty()) {
            Text(
                SEPARATOR + secondary,
                style = c.chipText.copy(color = c.textDim, fontWeight = FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        ui.button?.let { b -> ChipActionButton(b, tint) { onButton(b) } }
    }
}

/** The chip's action: an outline in the state colour, no fill, a 48 dp touch target. */
@Composable
private fun ChipActionButton(button: ChipButton, tint: Color, onClick: () -> Unit) {
    val c = LocalChrome.current
    val label = when (button) {
        ChipButton.SetUpAircraft -> specText("setup.wizard.entry.set_up_aircraft")
        ChipButton.Resume -> specText("flow.resume")
        ChipButton.TakeControl -> specText("flow.take_control")
        ChipButton.Abort -> specText("flow.launch.abort")
    }
    val shape = RoundedCornerShape(AdosRadius.Sm)
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .height(c.control - 8.dp)
            .clip(shape)
            .border(1.dp, tint.copy(alpha = BUTTON_OUTLINE_ALPHA), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AdosSpacing.S2),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = c.chipText.copy(color = tint), maxLines = 1)
    }
}

private const val SEPARATOR = " · "
private const val DIM_ALPHA = 0.55f
private const val OUTLINE_ALPHA = 0.6f
private const val BUTTON_OUTLINE_ALPHA = 0.4f
const val FLIGHT_CHIP_TAG = "flight_state_chip"
const val FLIGHT_CHIP_DOT_TAG = "flight_state_chip_dot"
