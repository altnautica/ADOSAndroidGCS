package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import android.view.MotionEvent

sealed interface InputEvent {
    /** A standard-layout gamepad button changed; [heldMs] is the press duration on release, 0 on press. */
    data class Button(val button: Int, val pressed: Boolean, val heldMs: Long) : InputEvent

    /** A hardware keyboard chord (see [canonicalChord]). Emitted on key down only. */
    data class Chord(val chord: String) : InputEvent

    /** Enter or numpad Enter, forwarded only while a confirm surface captures it. */
    data class Enter(val pressed: Boolean) : InputEvent
}

/** The screen currently taking hardware input (the cockpit); decides what the router consumes. */
interface InputCapture {
    /** True when the screen handles [chord]; the key event is then consumed. */
    fun consumesChord(chord: String): Boolean

    /** True while a confirm surface uses Enter as its hold source. */
    val capturesEnter: Boolean

    /**
     * True while the screen shows an overlay navigated like the rest of the app: buttons
     * and the hat then become [UiInput] while the sticks keep updating [AdosInputRouter.axes].
     */
    val uiNavigation: Boolean
}

/**
 * Receives every generic motion and key event from the activity before the view
 * hierarchy does. Returning `true` consumes the event; `false` hands it back to
 * the normal dispatch path.
 */
interface InputRouter {
    fun onMotion(event: MotionEvent): Boolean
    fun onKey(event: KeyEvent): Boolean

    /** The screen was touched: touch, not a controller, is now the last input. */
    fun onTouch()
}
