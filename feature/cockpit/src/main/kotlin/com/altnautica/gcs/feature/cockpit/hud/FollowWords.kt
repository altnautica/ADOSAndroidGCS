package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.core.model.OverheadAhead
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The follow chrome's words from the spec strings: the what's-locked chip's phase word, the
 * range panel's recovery line, and its leaf-clearance lines (the height lowered under leaves,
 * the overhead clearance ahead). A recovering follow (predicting, moving to look, searching)
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
        FollowPhase.Holding -> strings.format("lock.holding", mapOf("label" to holdLabel(f.holdReason, strings)))
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

    /** `Flying under leaves: 3.9 m` and why, while the follow height is lowered; else null. */
    fun heightLine(f: FollowState, strings: SetupStringsSpec): Pair<String, String?>? {
        val h = f.heightSetpointM?.takeIf { f.heightLowered } ?: return null
        val text = strings.format("follow.height_lowered", mapOf("v" to HudGeometry.oneDecimal(h)))
        return text to f.heightReason?.let { strings.format("follow.height_reason.$it") }
    }

    /** `Overhead clearance ahead: 3.9 m in 3 m` while the pilot reports one; else null. */
    fun overheadLine(o: OverheadAhead?, strings: SetupStringsSpec): String? = o?.let {
        strings.format("pilot.overhead_ahead", mapOf("h" to HudGeometry.oneDecimal(it.flyUnderAglM), "d" to it.aheadM.roundToInt().toString()))
    }

    /** `Following on phone position` or `Predicted` while the follow flies on that subject source; else null. */
    fun sourceLine(f: FollowState, strings: SetupStringsSpec): String? =
        f.subjectSource?.takeIf { it == "beacon" || it == "predicted" }?.let { strings.format("follow.subject_source.$it") }

    /** Whole seconds left, rounded up and never negative. */
    fun secondsLeft(s: Float): Int = ceil(s.coerceAtLeast(0f).toDouble()).toInt()

    /** A follow hold reason's spec label (`follow.hold_reason.<reason>`, e.g. "Camera lost"); an unlabelled reason reads as its wire word. */
    fun holdLabel(reason: String?, strings: SetupStringsSpec): String =
        reason?.let { strings.strings["follow.hold_reason.$it"] } ?: TargetState.holdReason(reason).orEmpty()

    private fun sentence(word: String) = word.lowercase().replaceFirstChar(Char::uppercase)
}
