package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import android.view.MotionEvent
import javax.inject.Inject

/** Router that consumes nothing: every event continues through normal view dispatch. */
class PassThroughInputRouter @Inject constructor() : InputRouter {
    override fun onMotion(event: MotionEvent): Boolean = false
    override fun onKey(event: KeyEvent): Boolean = false
}
