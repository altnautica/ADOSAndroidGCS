package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import kotlin.math.abs

/** A More (X) press shorter than this is a tap; longer is a hold that ends with only More-up. */
internal const val MORE_TAP_MS = 300L

/** Start and Home presses at least this long are holds. */
internal const val LONG_PRESS_MS = 600L

/** Stick deflection that starts a navigation direction. */
internal const val STICK_NAV_ENGAGE = 0.6f

/** Stick deflection below which a held navigation direction releases. */
internal const val STICK_NAV_RELEASE = 0.4f

/** True for every gamepad button key code (face, shoulder, trigger, stick, menu and numbered buttons). */
internal fun isGamepadButtonKey(keyCode: Int): Boolean =
    keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE ||
        keyCode in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16

internal fun isDpadKey(keyCode: Int): Boolean =
    keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

/**
 * What a gamepad button change means while the UI navigates (no flight capture).
 * [heldMs] is the press duration on release, 0 on press. D-pad, L3, R3 and every
 * other button mean nothing here.
 */
internal fun uiInputsForButton(button: Int, pressed: Boolean, heldMs: Long): List<UiInput> = when (button) {
    GamepadButton.A -> listOf(UiInput.Activate(pressed))
    GamepadButton.B -> if (pressed) listOf(UiInput.Back) else emptyList()
    GamepadButton.Y -> if (pressed) emptyList() else command(UiCommand.Details)
    GamepadButton.X -> when {
        pressed -> command(UiCommand.MoreDown)
        heldMs < MORE_TAP_MS -> listOf(UiInput.Command(UiCommand.MoreUp), UiInput.Command(UiCommand.More))
        else -> command(UiCommand.MoreUp)
    }
    GamepadButton.L1 -> if (pressed) command(UiCommand.PrevTab) else emptyList()
    GamepadButton.R1 -> if (pressed) command(UiCommand.NextTab) else emptyList()
    GamepadButton.L2 -> if (pressed) command(UiCommand.PrevPage) else emptyList()
    GamepadButton.R2 -> if (pressed) command(UiCommand.NextPage) else emptyList()
    GamepadButton.START -> if (pressed) emptyList() else command(if (heldMs < LONG_PRESS_MS) UiCommand.Menu else UiCommand.QuickMenu)
    GamepadButton.SELECT -> if (pressed) command(UiCommand.Capture) else emptyList()
    GamepadButton.HOME -> homeRelease(pressed, heldMs)
    else -> emptyList()
}

/**
 * The app-level commands a gamepad button still produces while the cockpit captures
 * flight input: Home opens the Quick menu (tap) or goes Home (hold); a Start hold opens
 * the Quick menu. Every other button belongs to the cockpit.
 */
internal fun captureUiInputsForButton(button: Int, pressed: Boolean, heldMs: Long): List<UiInput> = when (button) {
    GamepadButton.HOME -> homeRelease(pressed, heldMs)
    GamepadButton.START -> if (!pressed && heldMs >= LONG_PRESS_MS) command(UiCommand.QuickMenu) else emptyList()
    else -> emptyList()
}

private fun homeRelease(pressed: Boolean, heldMs: Long): List<UiInput> =
    if (pressed) emptyList() else command(if (heldMs < LONG_PRESS_MS) UiCommand.QuickMenu else UiCommand.Home)

private fun command(c: UiCommand): List<UiInput> = listOf(UiInput.Command(c))

/** Hat direction from the hat axes (-1..1, Y positive down); the dominant axis wins. */
internal fun hatDirection(hatX: Float, hatY: Float): NavDirection? = when {
    abs(hatX) < 0.5f && abs(hatY) < 0.5f -> null
    abs(hatX) >= abs(hatY) -> if (hatX < 0f) NavDirection.Left else NavDirection.Right
    else -> if (hatY < 0f) NavDirection.Up else NavDirection.Down
}

/**
 * Left-stick navigation with hysteresis: a direction engages at [STICK_NAV_ENGAGE] on the
 * dominant axis and holds until the deflection drops below [STICK_NAV_RELEASE].
 */
internal class StickNavigator {
    var direction: NavDirection? = null
        private set

    /** [x] and [y] are -1..1 with Y positive when the stick is pushed up. */
    fun update(x: Float, y: Float): NavDirection? {
        val m = maxOf(abs(x), abs(y))
        val dominant = if (abs(x) >= abs(y)) {
            if (x < 0f) NavDirection.Left else NavDirection.Right
        } else {
            if (y > 0f) NavDirection.Up else NavDirection.Down
        }
        direction = when {
            m >= STICK_NAV_ENGAGE -> dominant
            direction != null && m >= STICK_NAV_RELEASE -> direction
            else -> null
        }
        return direction
    }

    fun reset() {
        direction = null
    }
}
