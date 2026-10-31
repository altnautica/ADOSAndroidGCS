package com.altnautica.gcs.feature.settings.controller

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ControllerGlyph
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.settings.R

/** The console glyph of a standard gamepad button index, or null for the D-pad. */
internal fun consoleButtonFor(button: Int): ConsoleButton? = when (button) {
    GamepadButton.A -> ConsoleButton.Confirm
    GamepadButton.B -> ConsoleButton.Back
    GamepadButton.X -> ConsoleButton.More
    GamepadButton.Y -> ConsoleButton.Details
    GamepadButton.L1 -> ConsoleButton.PrevTab
    GamepadButton.R1 -> ConsoleButton.NextTab
    GamepadButton.L2 -> ConsoleButton.PrevPage
    GamepadButton.R2 -> ConsoleButton.NextPage
    GamepadButton.SELECT -> ConsoleButton.Capture
    GamepadButton.START -> ConsoleButton.Menu
    GamepadButton.L3 -> ConsoleButton.StickL
    GamepadButton.R3 -> ConsoleButton.StickR
    GamepadButton.HOME -> ConsoleButton.Home
    else -> null
}

/** One input in a button map: a controller button glyph or a named control (D-pad, sticks). */
internal sealed interface MapInput {
    data class Button(val button: ConsoleButton) : MapInput

    data class Named(@StringRes val label: Int) : MapInput
}

/** The map input of a standard gamepad button index. */
internal fun mapInputFor(button: Int): MapInput = consoleButtonFor(button)?.let { MapInput.Button(it) } ?: MapInput.Named(
    when (button) {
        GamepadButton.DPAD_UP -> R.string.settings_controller_input_dpad_up
        GamepadButton.DPAD_DOWN -> R.string.settings_controller_input_dpad_down
        GamepadButton.DPAD_LEFT -> R.string.settings_controller_input_dpad_left
        else -> R.string.settings_controller_input_dpad_right
    },
)

/** Draws [input]; [lit] fills it with the accent colour (the live input test). */
@Composable
internal fun MapInputGlyph(input: MapInput, lit: Boolean = false, size: Dp = 20.dp) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(size / 2)
    val fill = if (lit) colors.accentPrimary else Color.Transparent
    when (input) {
        is MapInput.Button -> ControllerGlyph(input.button, Modifier.background(fill, shape).padding(2.dp), size = size)
        is MapInput.Named -> Text(
            text = stringResource(input.label),
            style = MaterialTheme.typography.labelSmall,
            color = if (lit) colors.accentForeground else colors.fgPrimary,
            modifier = Modifier
                .height(size + 4.dp)
                .defaultMinSize(minWidth = size)
                .background(fill, shape)
                .border(1.dp, colors.borderStrong, shape)
                .padding(horizontal = AdosSpacing.S2)
                .wrapContentSize(),
        )
    }
}
