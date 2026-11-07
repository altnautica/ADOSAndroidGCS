package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What a [SetupSession] knows: the overview with live step updates folded in. */
data class SetupSnapshotState(
    val overview: FcSetupOverview? = null,
    /** The latest update of every step seen, keyed `wizard/step`. */
    val steps: Map<String, SetupStep> = emptyMap(),
    val busy: Boolean = false,
    val problem: SetupProblem? = null,
) {
    fun step(wizard: String, step: String): SetupStep? =
        steps[key(wizard, step)] ?: overview?.wizard(wizard)?.steps?.firstOrNull { it.step == step }

    fun wizard(id: String): SetupWizard? = overview?.wizard(id)?.let { w ->
        w.copy(steps = w.steps.map { steps[key(id, it.step)] ?: it })
    }

    companion object {
        fun key(wizard: String, step: String) = "$wizard/$step"
    }
}

/**
 * One live connection to a vehicle's setup engine: loads the overview, follows the
 * step stream (reconnecting after a drop) and sends step actions. Runs in [scope].
 */
class SetupSession(private val backend: SetupBackend, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SetupSnapshotState())
    val state: StateFlow<SetupSnapshotState> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()

    fun open() {
        jobs += scope.launch { refresh() }
        jobs += scope.launch {
            while (true) {
                try {
                    backend.stream().collect(::apply)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The stream drops when the link does; the next connect resumes it.
                }
                delay(RECONNECT_MS)
                refresh()
            }
        }
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    suspend fun refresh() {
        when (val r = backend.overview()) {
            is AgentResult.Ok -> _state.update { it.copy(overview = r.value, steps = emptyMap(), problem = null) }
            is AgentResult.Err -> _state.update { it.copy(problem = r.error.setupProblem()) }
        }
    }

    internal fun apply(event: FcSetupEvent) = _state.update { s ->
        when (event) {
            is FcSetupEvent.Step -> s.copy(steps = s.steps + (SetupSnapshotState.key(event.step.wizard, event.step.step) to event.step))
            is FcSetupEvent.Wizard -> s.copy(
                overview = s.overview?.let { o ->
                    val replaced = o.wizards.map { if (it.id == event.wizard.id) event.wizard else it }
                    o.copy(wizards = if (o.wizard(event.wizard.id) == null) replaced + event.wizard else replaced)
                },
                steps = s.steps.filterKeys { k -> !k.startsWith("${event.wizard.id}/") || event.wizard.steps.isEmpty() },
            )
        }
    }

    fun start(wizard: String) = send { backend.start(wizard).also { r -> r.getOrNull()?.wizard?.let { apply(FcSetupEvent.Wizard(0, it)) } } }

    fun abort(wizard: String) = send { backend.abort(wizard).also { r -> r.getOrNull()?.wizard?.let { apply(FcSetupEvent.Wizard(0, it)) } } }

    fun runStep(wizard: String, step: String) =
        send { backend.runStep(wizard, step).also { r -> r.getOrNull()?.step?.let { apply(FcSetupEvent.Step(0, it)) } } }

    fun action(wizard: String, step: String, action: String, args: JsonObject? = null) =
        send { backend.action(wizard, step, action, args).also { r -> r.getOrNull()?.step?.let { apply(FcSetupEvent.Step(0, it)) } } }

    fun revert(snapshot: String) = send {
        backend.revert(snapshot).also { if (it is AgentResult.Ok) refresh() }
    }

    private fun send(call: suspend () -> AgentResult<*>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, problem = null) }
        scope.launch {
            try {
                val r = call()
                if (r is AgentResult.Err) _state.update { it.copy(problem = r.error.setupProblem()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val RECONNECT_MS = 2_000L
    }
}
