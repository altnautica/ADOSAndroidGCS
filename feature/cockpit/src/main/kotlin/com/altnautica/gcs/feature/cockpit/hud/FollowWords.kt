package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import kotlin.math.ceil

/**
 * The follow chrome's words from the spec strings: the what's-locked chip's phase word and the
 * range panel's recovery line. A recovering follow (predicting, moving to look, searching)
 * carries the whole seconds left of its search budget; holding for the operator reads
 * `Waiting for you`.
 */
internal object FollowWords {
    /** The chip's word for a fresh follow phase other than idle. */
    fun lockWord(f: FollowState, strings: SetupStringsSpec): String = when (f.phase) {
        FollowPhase.Following, FollowPhase.Acquiring -> sentence(strings.format(f.phase.wordKey))
        FollowPhase.Predicting, FollowPhase.Repositioning, FollowPhase.Searching -> {
            val word = strings.format(f.phase.wordKey)
            f.reacquireRemainingS?.let { "$word ${secondsLeft(it)} s" } ?: word
        }
        FollowPhase.Holding -> strings.format("lock.holding", mapOf("label" to (TargetState.holdReason(f.holdReason) ?: "")))
        FollowPhase.HoldingAwaitOperator -> strings.format(f.phase.wordKey)
        FollowPhase.Idle -> ""
    }

    /** `18 s · On the way · Behind a trunk or column` while recovering; null otherwise or with nothing to say. */
    fun recoveryLine(f: FollowState, strings: SetupStringsSpec): String? {
        if (!f.phase.recovering) return null
        val parts = listOfNotNull(
            f.reacquireRemainingS?.let { "${secondsLeft(it)} s" },
            f.vantageState?.let { strings.format("follow.vantage.$it") },
            f.occluder?.let { strings.format("follow.occluder.${it.kind}") },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** Whole seconds left, rounded up and never negative. */
    fun secondsLeft(s: Float): Int = ceil(s.coerceAtLeast(0f).toDouble()).toInt()

    private fun sentence(word: String) = word.lowercase().replaceFirstChar(Char::uppercase)
}
