package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Gamepad buttons in the standard (W3C) layout, so bindings and the
 * `MANUAL_CONTROL` button bitmask match every other ADOS ground station.
 */
object GamepadButton {
    const val A = 0
    const val B = 1
    const val X = 2
    const val Y = 3
    const val L1 = 4
    const val R1 = 5
    const val L2 = 6
    const val R2 = 7
    const val SELECT = 8
    const val START = 9
    const val L3 = 10
    const val R3 = 11
    const val DPAD_UP = 12
    const val DPAD_DOWN = 13
    const val DPAD_LEFT = 14
    const val DPAD_RIGHT = 15
    const val HOME = 16

    /** Highest button index a binding may use. */
    const val MAX_BINDABLE = 15

    /** Standard index of an Android gamepad key code, or null when the key is not a gamepad button. */
    fun fromKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> A
        KeyEvent.KEYCODE_BUTTON_B -> B
        KeyEvent.KEYCODE_BUTTON_X -> X
        KeyEvent.KEYCODE_BUTTON_Y -> Y
        KeyEvent.KEYCODE_BUTTON_L1 -> L1
        KeyEvent.KEYCODE_BUTTON_R1 -> R1
        KeyEvent.KEYCODE_BUTTON_L2 -> L2
        KeyEvent.KEYCODE_BUTTON_R2 -> R2
        KeyEvent.KEYCODE_BUTTON_SELECT -> SELECT
        KeyEvent.KEYCODE_BUTTON_START -> START
        KeyEvent.KEYCODE_BUTTON_THUMBL -> L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> R3
        KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
        KeyEvent.KEYCODE_BUTTON_MODE -> HOME
        else -> null
    }
}

/** Inputs the cockpit owns; skills can never be bound to them. */
object ReservedInputs {
    /** L1+R1 quick settings, Select skill drawer, Start exit, D-pad stream cycle. */
    val GAMEPAD_BUTTONS: Set<Int> = setOf(
        GamepadButton.L1, GamepadButton.R1, GamepadButton.SELECT, GamepadButton.START,
        GamepadButton.DPAD_UP, GamepadButton.DPAD_DOWN, GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT,
    )

    /** Palette, parameter shortcuts, stream digits and cycle, picture-in-picture, quick settings. */
    val CHORDS: Set<String> = setOf(
        "escape", "meta+k", "ctrl+k", "ctrl+s", "meta+s", "ctrl+shift+s", "meta+shift+s", "ctrl+r", "meta+r",
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "`", "p", "shift+<", "shift+;",
    )

    fun isReservedButton(button: Int) = button in GAMEPAD_BUTTONS

    fun isReservedChord(chord: String) = chord in CHORDS

    private val RESERVED_MASK: Int = GAMEPAD_BUTTONS.fold(0) { acc, b -> acc or (1 shl b) }

    /** `MANUAL_CONTROL` button bitmask: the first 16 buttons minus every cockpit-reserved button. */
    fun manualControlButtonMask(pressed: Int): Int = pressed and 0xFFFF and RESERVED_MASK.inv()
}

/**
 * Removes the stick's resting noise: values within [flat] of centre read 0 and the
 * remaining travel is rescaled so full deflection still reads ±1.
 */
fun applyDeadZone(value: Float, flat: Float): Float {
    val f = flat.coerceIn(0f, 0.99f)
    val v = value.coerceIn(-1f, 1f)
    if (abs(v) <= f) return 0f
    return sign(v) * (abs(v) - f) / (1f - f)
}

/** Stick positions after the dead-zone, each -1..1. Y is positive with the stick pushed up. */
data class GamepadAxes(
    val leftX: Float,
    val leftY: Float,
    val rightX: Float,
    val rightY: Float,
) {
    companion object {
        val Neutral = GamepadAxes(0f, 0f, 0f, 0f)
    }
}

/**
 * Canonical keyboard chord: `ctrl+alt+shift+meta+<base>` with modifiers in that fixed
 * order. Digits and function keys come from the key code; letters are the lower-case
 * letter; other printable keys are the character the layout produces (so Shift+comma
 * is `shift+<` on a US layout). Returns null for keys with no bindable base.
 */
fun canonicalChord(keyCode: Int, metaState: Int, unicodeChar: Int): String? {
    val base: String = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "f" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        else -> {
            if (unicodeChar <= 0x20 || Character.isISOControl(unicodeChar)) return null
            String(Character.toChars(unicodeChar)).lowercase()
        }
    }
    return buildString {
        if (metaState and KeyEvent.META_CTRL_ON != 0) append("ctrl+")
        if (metaState and KeyEvent.META_ALT_ON != 0) append("alt+")
        if (metaState and KeyEvent.META_SHIFT_ON != 0) append("shift+")
        if (metaState and KeyEvent.META_META_ON != 0) append("meta+")
        append(base)
    }
}
