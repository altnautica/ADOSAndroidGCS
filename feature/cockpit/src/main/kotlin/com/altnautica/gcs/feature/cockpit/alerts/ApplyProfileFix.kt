package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.preflight.BatteryFailsafeFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import kotlin.math.max

/** One row the ADOS profile step writes: the profile's [value] and the FC's [have] (null when not reported). */
data class ProfileRow(val name: String, val value: String, val have: Double?) {
    /** The FC already holds the value; a float read back from it may carry single-precision noise. */
    val alreadySet: Boolean get() {
        val want = value.toDoubleOrNull() ?: return false
        val h = have ?: return false
        return abs(h - want) <= 1e-6 * max(1.0, abs(want))
    }
}

/** What a write of the ADOS profile changes, what the FC already holds, and whether it restarts the FC. */
data class ProfileReview(val actionId: String?, val changes: List<ProfileRow>, val same: List<ProfileRow>, val reboots: Boolean) {
    companion object {
        /** The review of the profile step's view: its `args.params` rows (`{name, value, have}`) and `args.reboots`. */
        fun of(step: SetupStep): ProfileReview {
            val rows = (step.args["params"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val value = (o["value"] as? JsonPrimitive)?.let { p -> p.doubleOrNull?.let(::number) ?: p.contentOrNull } ?: return@mapNotNull null
                ProfileRow(name, value, (o["have"] as? JsonPrimitive)?.doubleOrNull)
            }
            val action = step.actions.firstOrNull { it.style == SetupActionStyle.Primary } ?: step.actions.firstOrNull()
            return ProfileReview(
                actionId = action?.id,
                changes = rows.filterNot { it.alreadySet },
                same = rows.filter { it.alreadySet },
                reboots = (step.args["reboots"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        /** A parameter value as the FC holds it: whole numbers without a decimal point. */
        fun number(v: Double): String = if (v == Math.rint(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()
    }
}

/** Why a write did not happen when the spec has no words for it. */
enum class ApplyFailure {
    /** The engine refused without a code the spec words. */
    Refused,

    /** The vehicle did not answer. */
    Unreachable,

    /** The step itself ended failed. */
    Failed,
}

/** Where the inline "Apply recommended values" of an alert row is. */
sealed interface ApplyPhase {
    data object Idle : ApplyPhase
    data object Reading : ApplyPhase
    data class Review(val review: ProfileReview) : ApplyPhase
    data object Writing : ApplyPhase
    data object Done : ApplyPhase

    /** [key]: the setup spec key that words it; null when only [local] can. */
    data class Error(val key: String?, val local: ApplyFailure) : ApplyPhase
}

/** The apply flow and the alert row ([owner], its snooze key) that started it. */
data class ApplyState(val owner: String? = null, val phase: ApplyPhase = ApplyPhase.Idle)

/**
 * "Apply recommended values" from an alert row: run the ground wizard's ADOS profile step (it
 * writes nothing yet and lists what it would write), show what changes, and write on a hold.
 * The engine refuses while armed and snapshots every parameter before the first write. While
 * the review shows, the engine holds that step open: [cancel], or [leave] (the list closing,
 * the cockpit going away), aborts the ground wizard so Aircraft setup is not left blocked by a
 * step nothing shows. A step already waiting from an earlier review (`wizard_running` while it
 * is the running step) is picked up rather than refused. [abortScope] outlives [scope], so the
 * abort still goes out when the owner of [scope] is cleared.
 */
class ApplyProfileFix(
    private val scope: CoroutineScope,
    private val strings: SetupStringsSpec,
    private val abortScope: CoroutineScope = scope,
    private val backend: suspend () -> SetupBackend?,
) {
    private val _state = MutableStateFlow(ApplyState())
    val state: StateFlow<ApplyState> = _state.asStateFlow()
    private var job: Job? = null

    /** Read the profile step for [owner]'s row. */
    fun read(owner: String) {
        job?.cancel()
        _state.value = ApplyState(owner, ApplyPhase.Reading)
        job = scope.launch {
            val b = backend() ?: return@launch set(owner, ApplyPhase.Error(null, ApplyFailure.Unreachable))
            set(owner, when (val r = b.runStep(WIZARD, STEP)) {
                is AgentResult.Ok -> r.value.step?.takeIf { r.value.ok }?.let(::reviewOrFail) ?: ApplyPhase.Error(null, ApplyFailure.Refused)
                is AgentResult.Err ->
                    if (code(r.error) == WIZARD_RUNNING) openReview(b)?.let(::reviewOrFail) ?: error(r.error) else error(r.error)
            })
        }
    }

    /** Write the reviewed values, then follow the step until it ends. */
    fun write() {
        val s = _state.value
        val review = (s.phase as? ApplyPhase.Review)?.review ?: return
        val owner = s.owner ?: return
        val action = review.actionId ?: return
        job?.cancel()
        _state.value = ApplyState(owner, ApplyPhase.Writing)
        job = scope.launch {
            val b = backend() ?: return@launch set(owner, ApplyPhase.Error(null, ApplyFailure.Unreachable))
            val step = when (val r = b.action(WIZARD, STEP, action)) {
                is AgentResult.Ok -> r.value.step?.takeIf { r.value.ok } ?: return@launch set(owner, ApplyPhase.Error(null, ApplyFailure.Refused))
                is AgentResult.Err -> return@launch set(owner, error(r.error))
            }
            set(owner, ended(step) ?: follow(b))
        }
    }

    /** Cancel the review: back to the button, and the ground wizard aborted. */
    fun cancel() {
        val wasReview = _state.value.phase is ApplyPhase.Review
        job?.cancel()
        _state.value = ApplyState()
        if (wasReview) abort()
    }

    /**
     * The row's screen went away: a review still open is aborted. A write in progress keeps
     * running on the vehicle; only following it here stops.
     */
    fun leave() {
        when (_state.value.phase) {
            is ApplyPhase.Review, ApplyPhase.Reading -> cancel()
            ApplyPhase.Writing -> {
                job?.cancel()
                _state.value = ApplyState()
            }
            else -> _state.value = ApplyState()
        }
    }

    private fun abort() {
        abortScope.launch { backend()?.abort(WIZARD) }
    }

    private fun set(owner: String, phase: ApplyPhase) {
        if (_state.value.owner == owner) _state.value = ApplyState(owner, phase)
    }

    private fun reviewOrFail(step: SetupStep): ApplyPhase =
        if (step.state == SetupStepState.Failed) failed(step) else ApplyPhase.Review(ProfileReview.of(step))

    private fun failed(step: SetupStep): ApplyPhase.Error =
        ApplyPhase.Error(step.blockers.firstNotNullOfOrNull(strings::errorKey), ApplyFailure.Failed)

    private fun ended(step: SetupStep): ApplyPhase? = when (step.state) {
        SetupStepState.Done -> ApplyPhase.Done
        SetupStepState.Failed -> failed(step)
        else -> null
    }

    /** The profile step waiting for the operator from an earlier review, here or on another client; null otherwise. */
    private suspend fun openReview(b: SetupBackend): SetupStep? {
        val o = (b.overview() as? AgentResult.Ok)?.value ?: return null
        if (o.running?.wizard != WIZARD || o.running?.step != STEP) return null
        return o.wizard(WIZARD)?.steps?.firstOrNull { it.step == STEP }?.takeIf { it.state == SetupStepState.WaitingUser }
    }

    private suspend fun follow(b: SetupBackend): ApplyPhase = b.stream()
        .mapNotNull { e -> (e as? FcSetupEvent.Step)?.step?.takeIf { it.wizard == WIZARD && it.step == STEP } }
        .onStart { (b.overview() as? AgentResult.Ok)?.value?.wizard(WIZARD)?.steps?.firstOrNull { it.step == STEP }?.let { emit(it) } }
        .mapNotNull(::ended)
        .first()

    private fun error(e: AgentError): ApplyPhase.Error {
        val key = code(e)?.let(strings::errorKey)
        return when {
            key != null -> ApplyPhase.Error(key, ApplyFailure.Refused)
            e is AgentError.Armed -> ApplyPhase.Error(strings.errorKey(ARMED), ApplyFailure.Refused)
            e is AgentError.Server && e.code in 400..499 -> ApplyPhase.Error(null, ApplyFailure.Refused)
            else -> ApplyPhase.Error(null, ApplyFailure.Unreachable)
        }
    }

    private fun code(e: AgentError): String? = (e as? AgentError.Server)?.errorCode

    companion object {
        const val WIZARD = BatteryFailsafeFix.WIZARD
        const val STEP = BatteryFailsafeFix.STEP
        private const val WIZARD_RUNNING = "wizard_running"
        private const val ARMED = "armed"
    }
}
