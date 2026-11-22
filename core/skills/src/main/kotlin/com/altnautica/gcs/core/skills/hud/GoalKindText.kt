package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.GoalKind
import com.altnautica.gcs.core.model.PilotState
import kotlin.math.roundToInt

/**
 * The goal chip's text, a port of the shared web module `hud/goal-kind.ts`: which goal the
 * flying skill sent the pilot and, for a follow intent, the viewpoint the pilot picked
 * (`Follow intent · view 15° 8 m`, `Ring`, `Orbit`). Text from the spec's string table only.
 */
class GoalKindText(private val text: SpecText) {

    /** `Follow intent`, `Ring`, `Orbit`, `Position` or `Vantage`. */
    fun label(k: GoalKind): String = text.text("pilot.goal_kind.${k.wire}")

    /** The chip text while a skill's goal flies, else null. */
    fun chip(p: PilotState?): String? {
        val kind = p?.goalKind ?: return null
        val view = p.view?.takeIf { kind == GoalKind.FollowIntent } ?: return label(kind)
        return text.text(
            "pilot.goal_kind.view",
            mapOf(
                "kind" to label(kind),
                "bearing" to "${view.bearingDeg.roundToInt().mod(360)}",
                "r" to "${view.radiusM.roundToInt()}",
            ),
        )
    }
}
