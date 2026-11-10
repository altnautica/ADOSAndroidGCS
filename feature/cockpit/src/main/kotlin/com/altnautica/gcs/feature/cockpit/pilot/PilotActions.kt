package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.refusalText
import com.altnautica.gcs.feature.cockpit.CockpitEvent

/**
 * A Mode-sheet change (profile, control mode, avoidance, hand to FC): [stopMotion] stops
 * the running motion skill first, then [action] goes to the pilot on [link] and its
 * answer comes back.
 */
internal suspend fun sendModeSheetAction(action: PilotAction, link: PilotLink?, stopMotion: suspend () -> Unit): PilotActionResult {
    stopMotion()
    return link?.action(action) ?: PilotActionResult.Unavailable
}

/**
 * The cockpit event that reports a pilot action's answer, or null when it was accepted. A
 * refusal carries the spec's sentence for its code, never the raw code.
 */
internal fun pilotActionEvent(result: PilotActionResult, text: SpecText): CockpitEvent? = when (result) {
    PilotActionResult.Accepted -> null
    is PilotActionResult.Refused -> CockpitEvent.PilotRefused(text.refusalText(result.reason))
    PilotActionResult.NoAnswer -> CockpitEvent.PilotNoAnswer
    PilotActionResult.Unavailable -> CockpitEvent.PilotUnavailable
}
