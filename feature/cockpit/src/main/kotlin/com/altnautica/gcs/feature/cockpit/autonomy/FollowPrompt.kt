package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.feature.cockpit.CockpitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * The waiting-for-instructions prompt's actions, in the order a prompt lists them. [key] is
 * the bare hardware key that runs it while the prompt has focus.
 */
enum class FollowPromptAction(val wire: String, val key: Char) {
    Resume("resume", 'r'),
    Redesignate("redesignate", 'p'),
    Rth("rth", 'h'),
    Land("land", 'l'),
    Stop("stop", 'x'),
    ;

    /** The spec key of the action's label. */
    val labelKey: String get() = "follow.prompt.action.$wire"
}

/** The prompt as spec keys: its title, a reason-specific body and the actions offered. */
data class FollowPrompt(val titleKey: String, val bodyKey: String, val actions: List<FollowPromptAction>)

/**
 * The operator prompt while follow-me holds and waits for instructions
 * (`holding-await-operator`), the same rule every surface renders; the shared table is
 * `follow-prompt-cases.json`.
 */
object FollowPrompts {
    const val TITLE_KEY = "follow.prompt.await_title"
    private const val BODY_LOCK_LOST = "follow.prompt.await_body.lock_lost"

    /** The body for each hold reason the follow waits with; any other reason reads as a lost lock. */
    private val BODY = mapOf(
        "awaiting-operator" to BODY_LOCK_LOST,
        "search-exhausted" to "follow.prompt.await_body.search_exhausted",
        "predictive-search-off" to "follow.prompt.await_body.predictive_search_off",
        "no-range" to "follow.prompt.await_body.no_range",
    )

    /**
     * The prompt for [s]; null in every phase but `holding-await-operator`. Resume is not
     * offered in strict re-acquire (a resumed search gives up again at once) nor when there is
     * no prediction left to search from (`no-range`).
     */
    fun of(s: FollowState): FollowPrompt? {
        if (s.phase != FollowPhase.HoldingAwaitOperator) return null
        val canResume = s.reacquireMode != "strict" && s.holdReason != "no-range"
        val actions = FollowPromptAction.entries.filter { canResume || it != FollowPromptAction.Resume }
        return FollowPrompt(TITLE_KEY, BODY[s.holdReason] ?: BODY_LOCK_LOST, actions)
    }

    /** The offered action a bare key names, or null (a modifier held, or a key no offered action uses). */
    fun actionForKey(key: Char, modified: Boolean, offered: List<FollowPromptAction>): FollowPromptAction? {
        if (modified) return null
        return offered.firstOrNull { it.key == key.lowercaseChar() }
    }
}

/** The transports a prompt action goes through; injected, so the dispatch is pure. */
interface FollowActionTransport {
    /** Writes one follow-me config key; true once the agent took it. */
    suspend fun writeFollowConfig(key: String, value: JsonPrimitive): Boolean

    /** Sends a pilot action (`rth`, `land`). */
    fun pilot(action: String)

    /** Arms picking a new target on the video. */
    fun startPick()
}

/**
 * Runs [action] through its transport: search again writes the next `operator_resume_seq`
 * ([seq] + 1) to the follow-me config, pick a target arms designation, return home and land go
 * to the pilot, and stop writes `active = false`. False when a config write was refused.
 */
suspend fun runFollowAction(action: FollowPromptAction, seq: Int, t: FollowActionTransport): Boolean = when (action) {
    FollowPromptAction.Resume -> t.writeFollowConfig(FollowConfig.RESUME_SEQ, JsonPrimitive(seq + 1))
    FollowPromptAction.Redesignate -> {
        t.startPick()
        true
    }
    FollowPromptAction.Rth, FollowPromptAction.Land -> {
        t.pilot(action.wire)
        true
    }
    FollowPromptAction.Stop -> t.writeFollowConfig(FollowConfig.ACTIVE, JsonPrimitive(false))
}

/** Follow-me config keys the cockpit writes. */
object FollowConfig {
    const val RESUME_SEQ = "operator_resume_seq"
    const val ACTIVE = "active"
}

/**
 * The cockpit's prompt transports: config writes to the flown node's follow-me, pilot actions
 * through [pilot], and pick-a-target as dropping the designation (the next box tapped on the
 * video designates afresh). A refused write reads as a rejected command.
 */
internal class FollowPromptRunner(
    private val scope: CoroutineScope,
    private val autonomy: CockpitAutonomy,
    private val pilotAction: (String) -> Unit,
    private val events: MutableSharedFlow<CockpitEvent>,
) : FollowActionTransport {
    override suspend fun writeFollowConfig(key: String, value: JsonPrimitive): Boolean = autonomy.writeFollowConfig(key, value)

    override fun pilot(action: String) = pilotAction(action)

    override fun startPick() {
        autonomy.release()
        events.tryEmit(CockpitEvent.PickTarget)
    }

    /** Runs [action] against the follow state's [seq]. */
    fun run(action: FollowPromptAction, seq: Int) {
        scope.launch {
            if (!runFollowAction(action, seq, this@FollowPromptRunner)) {
                events.tryEmit(CockpitEvent.TargetRefused(CockpitAutonomy.REASON_REFUSED))
            }
        }
    }
}
