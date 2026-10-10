package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Receives every generic motion and key event from the activity before the view
 * hierarchy does. Returning `true` consumes the event; `false` hands it back to
 * the normal dispatch path.
 */
interface InputRouter {
    fun onMotion(event: MotionEvent): Boolean
    fun onKey(event: KeyEvent): Boolean
}
