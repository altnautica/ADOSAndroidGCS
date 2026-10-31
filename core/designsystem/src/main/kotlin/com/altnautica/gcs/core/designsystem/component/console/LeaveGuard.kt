package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets the visible surface hold app-level navigation (Quick menu, Home) until the
 * operator confirms. A surface sets [intercept] while shown and clears it when it
 * leaves; the intercept runs `proceed` at once or after its own confirmation.
 */
class LeaveGuard {
    var intercept: ((proceed: () -> Unit) -> Unit)? = null

    /** Runs [action] through the current intercept, or straight away when there is none. */
    fun navigate(action: () -> Unit) {
        val guard = intercept
        if (guard != null) guard(action) else action()
    }
}

val LocalLeaveGuard = staticCompositionLocalOf { LeaveGuard() }
