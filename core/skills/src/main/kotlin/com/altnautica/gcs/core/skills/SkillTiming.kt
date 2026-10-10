package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.HeartbeatState

/** Timing constants of the skill pipeline and the confirm tiers. */
object SkillTiming {
    /** A heartbeat older than this means the flight controller link is stale. */
    const val LINK_STALE_MS = 3_000L

    /** How often heartbeat age is sampled for link freshness. */
    const val LINK_FRESH_POLL_MS = 500L

    /** One-shot debounce window that swallows a stuttered double press. */
    const val DEBOUNCE_MS = 750L

    /** Press-and-hold duration of the `hold` tier. */
    const val HOLD_MS = 800

    /** Hold that satisfies the `slide` tier from a gamepad or keyboard. */
    const val SLIDE_HOLD_MS = 1_500

    /** Hold that fires an armed `guarded` control. */
    const val GUARDED_HOLD_MS = 1_500

    /** How long a `guarded` control stays armed after its first activation. */
    const val GUARD_WINDOW_MS = 3_000L
}

/**
 * Heartbeat age at [nowMs], never negative. [tMs] and [nowMs] are both on the
 * `SystemClock.elapsedRealtime()` clock; a heartbeat stamped after a cached `now`
 * was read is age 0, not a negative age.
 */
fun heartbeatAgeMs(tMs: Long, nowMs: Long): Long = (nowMs - tMs).coerceAtLeast(0)

/** True when the heartbeat is no older than [SkillTiming.LINK_STALE_MS] at [nowMs]. */
fun isLinkFresh(heartbeat: HeartbeatState?, nowMs: Long): Boolean =
    heartbeat != null && heartbeatAgeMs(heartbeat.tMs, nowMs) <= SkillTiming.LINK_STALE_MS

/** Disabled-reason and notice keys produced by the skill pipeline. */
object SkillReason {
    const val NO_FC_LINK = "noFcLink"
    const val RECEIVE_ONLY = "receiveOnly"
    const val ALREADY_ARMED = "alreadyArmed"
    const val NOT_ARMED = "notArmed"
    const val ALREADY_DISARMED = "alreadyDisarmed"
    const val MODE_UNAVAILABLE = "modeUnavailable"
    const val NO_AUTONOMOUS_NAV = "noAutonomousNav"
    const val NO_CHARGES = "noCharges"
    const val COMMAND_REJECTED = "commandRejected"
    const val COMMAND_TIMEOUT = "commandTimeout"
    const val COMMAND_UNSUPPORTED = "commandUnsupported"
    const val COMMAND_DENIED = "commandDenied"
    const val COMMAND_QUEUED = "commandQueued"
    const val COMMAND_FAILED = "commandFailed"
    const val STATE_ERROR = "stateError"
    const val FLY_HERE_TARGET = "flyHereTarget"
}
