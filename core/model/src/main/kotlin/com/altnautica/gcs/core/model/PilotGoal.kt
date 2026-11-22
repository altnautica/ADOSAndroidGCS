package com.altnautica.gcs.core.model

/** The kind of goal a skill's lease sent the pilot, by its wire name (`goal_kind`). */
enum class GoalKind(val wire: String) {
    /** The pilot picks the viewpoint that keeps the subject in view. */
    FollowIntent("follow_intent"),

    /** The skill's own point on its ring round the subject. */
    Follow("follow"),

    /** An orbit round the subject. */
    Orbit("orbit"),

    /** A point to hold. */
    Position("position"),

    /** A point that sees a lost subject's predicted area. */
    Vantage("vantage");

    companion object {
        fun of(wire: String?): GoalKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The viewpoint the pilot's view planner picked for a follow intent (`view`): its bearing from
 * the subject ([bearingDeg], degrees, north clockwise), distance ([radiusM], m), height
 * ([heightM], m) and the share of the subject's predicted positions it sees ([visibility], 0-1).
 */
data class FollowView(val bearingDeg: Float, val radiusM: Float, val heightM: Float, val visibility: Float)
