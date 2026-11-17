package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PilotState
import kotlin.math.ceil

/**
 * The no-RC posture's text (`rc_receiver: absent`): the "No RC" chip, the strip naming what
 * stops the drone, the leave warning and the lock-screen warning. Each is null unless the
 * pilot declares no RC. `{action}` and `{s}` come from the pilot's `link_loss_action` and
 * `link_loss_s`, else a return after 10 s, the pilot's defaults.
 */
class NoRcText(private val text: SpecText) {

    /** The "No RC" chip text, beside the "Confined" chip. */
    fun chip(p: PilotState?): String? = if (p?.noRc == true) text.text("chip.no_rc") else null

    /** The strip: Pause, Land and Return stop the drone, and what a lost link does. */
    fun strip(p: PilotState?): String? = posture(p, "follow.no_rc_strip")

    /** Leaving the cockpit while armed ends in the link-loss action; null while disarmed or with an RC transmitter. */
    fun leave(p: PilotState?): String? = if (p?.armed == false) null else posture(p, "shell.leave_no_rc")

    /** The pre-arm warning when the lock screen hides the link notification. */
    fun lockScreenHidden(p: PilotState?, hidden: Boolean): String? = if (hidden) posture(p, "shell.lockscreen_hidden_no_rc") else null

    private fun posture(p: PilotState?, key: String): String? {
        if (p?.noRc != true) return null
        val s = p.linkLossS?.takeIf { it.isFinite() && it > 0f }?.let { ceil(it.toDouble()).toInt() } ?: DEFAULT_LINK_LOSS_S
        return text.text(key, mapOf("action" to (p.linkLossAction ?: DEFAULT_ACTION), "s" to s))
    }

    companion object {
        /** The pilot's default link-loss action and window. */
        const val DEFAULT_ACTION = "rth"
        const val DEFAULT_LINK_LOSS_S = 10

        /** The pre-arm checklist row's id for the lock-screen warning. */
        const val LOCKSCREEN_ITEM = "lockscreen_hidden"
    }
}
