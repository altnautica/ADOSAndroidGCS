package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import com.altnautica.gcs.core.model.AvoidState
import com.altnautica.gcs.core.model.PilotState

/**
 * The pilot's plan in map NED metres (north, east): the path from the drone to the goal and
 * the goal, while a go-to or a skill flies. A port of the shared web `localPlan`.
 */
internal data class LocalPlan(
    val points: List<Pair<Float, Float>>,
    val goal: Pair<Float, Float>?,
    /** The pilot reports the way blocked: drawn in `tokens.json` `state.blocked`. */
    val blocked: Boolean,
) {
    companion object {
        /** The plan [pilot] reports, or null when it reports none; pass null for a stale frame. */
        fun of(pilot: PilotState?): LocalPlan? {
            if (pilot == null) return null
            val goal = pilot.goalNed?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
            if (pilot.planNed.isEmpty() && goal == null) return null
            return LocalPlan(pilot.planNed, goal, pilot.avoidState == AvoidState.Blocked)
        }
    }
}

/** A [LocalPlan] in screen pixels. */
internal data class ScreenPlan(val points: List<Offset>, val goal: Offset?, val blocked: Boolean)

/** Projects [plan] through [pt] (north, east metres to a screen point). */
internal fun screenPlan(plan: LocalPlan, pt: (Float, Float) -> Offset): ScreenPlan =
    ScreenPlan(plan.points.map { (n, e) -> pt(n, e) }, plan.goal?.let { (n, e) -> pt(n, e) }, plan.blocked)
