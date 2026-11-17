package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Keyboard and controller focus for the follow waiting prompt. */
internal class FollowPromptFocus(private val autonomy: CockpitAutonomy, private val overlays: CockpitOverlays) {
    private val _requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Requests to move focus into the follow waiting prompt (the controller's select while it is shown). */
    val requests: SharedFlow<Unit> = _requests.asSharedFlow()

    /** The prompt reports whether focus is inside it. */
    fun setFocused(focused: Boolean) {
        overlays.followPrompt.value = focused
    }

    /** The controller's select while the waiting prompt is shown: focus moves into it. False with no prompt shown. */
    fun enter(): Boolean {
        if (autonomy.followPrompt() == null) return false
        _requests.tryEmit(Unit)
        return true
    }

    /** The bare keys the waiting prompt answers while focus is inside it; empty otherwise, so they stay the cockpit's. */
    fun keys(): Set<String> {
        if (!overlays.followPrompt.value) return emptySet()
        return autonomy.followPrompt()?.actions?.mapTo(HashSet()) { it.key.toString() }.orEmpty()
    }
}
