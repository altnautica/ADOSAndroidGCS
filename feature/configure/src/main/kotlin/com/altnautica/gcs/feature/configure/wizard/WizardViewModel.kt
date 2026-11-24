package com.altnautica.gcs.feature.configure.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.link.node.SetupBackendSource
import com.altnautica.gcs.core.link.node.PilotReadinessStore
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.ReadinessItem
import kotlinx.coroutines.flow.Flow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Where the aircraft setup surface is: the wizard list, or one wizard (optionally one step of it). */
data class WizardFocus(val wizard: String, val step: String? = null)

data class WizardUiState(
    /** Null while resolving; false when the vehicle is reached straight over MAVLink. */
    val agent: Boolean? = null,
    val setup: SetupSnapshotState = SetupSnapshotState(),
    val focus: WizardFocus? = null,
    /** The setup steps the flight controller's readiness flags, with the reason line ([SetupOverview.readinessFlags]). */
    val flags: Map<String, String> = emptyMap(),
) {
    val wizard: SetupWizard? get() = focus?.let { setup.wizard(it.wizard) }

    /** The step on screen: the focused one, else the running one, else the first unfinished one. */
    val step: SetupStep? get() {
        val f = focus ?: return null
        f.step?.let { return setup.step(f.wizard, it) }
        val w = wizard ?: return null
        val running = setup.overview?.running?.takeIf { it.wizard == f.wizard }?.step
        running?.let { id -> w.steps.firstOrNull { it.step == id }?.let { return it } }
        // No live run: the overview offers Start, or Continue for a run the agent lost on a restart.
        if (w.state == SetupWizardState.NotStarted || w.state == SetupWizardState.Aborted || w.state == SetupWizardState.Interrupted) return null
        return w.steps.firstOrNull { it.state != SetupStepState.Done && it.state != SetupStepState.Skipped }
    }

    /** The wizard ran through and no run of it is in progress: show its results and the snapshot revert. */
    val showResults: Boolean get() =
        focus?.step == null && wizard?.state == SetupWizardState.Done && setup.overview?.running?.wizard != focus?.wizard
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = WizardViewModel.Factory::class)
class WizardViewModel internal constructor(
    nodeId: DeviceId,
    initial: WizardFocus?,
    backends: SetupBackendSource,
    /** Setup strings for the screen. */
    val text: SetupText,
    /** Run the [initial] step once its first overview loads; the step's own actions still wait for the operator. */
    runInitialStep: Boolean = false,
    /** The onboard pilot's readiness for this vehicle, as the cockpit last saw it. */
    readiness: Flow<List<ReadinessItem>> = flowOf(emptyList()),
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        @Assisted("wizard") wizard: String?,
        @Assisted("step") step: String?,
        @Assisted run: Boolean,
        backends: SetupBackendSource,
        text: SetupText,
        readinessStore: PilotReadinessStore,
    ) : this(DeviceId(nodeId), wizard?.let { WizardFocus(it, step) }, backends, text, run, readinessStore.observe(DeviceId(nodeId)))

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String, @Assisted("wizard") wizard: String?, @Assisted("step") step: String?, run: Boolean): WizardViewModel
    }

    private val focus = MutableStateFlow(initial)
    private var session: SetupSession? = null

    /** The step still to run on opening, cleared once sent so a reconnect never runs it again. */
    private var pendingRun: WizardFocus? = initial?.takeIf { runInitialStep && it.step != null }

    /** Null until the backend source first answers; then the session, or a holder of null for a direct link. */
    private val sessions: StateFlow<Resolved?> = backends.observe(nodeId).map { backend ->
        session?.close()
        session = backend?.let { SetupSession(it, viewModelScope).also(SetupSession::open) }
        session?.let(::runPending)
        Resolved(session)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun runPending(s: SetupSession) {
        val run = pendingRun ?: return
        viewModelScope.launch {
            s.state.first { it.overview != null && !it.actionsBlocked }
            if (pendingRun == null || session !== s) return@launch
            pendingRun = null
            s.runStep(run.wizard, run.step!!)
        }
    }

    private class Resolved(val session: SetupSession?)

    val state: StateFlow<WizardUiState> = combine(
        sessions.flatMapLatest { r -> r?.session?.state ?: flowOf(null) },
        focus,
        sessions,
        readiness,
    ) { snapshot, f, resolved, items ->
        WizardUiState(
            agent = resolved?.let { it.session != null },
            setup = snapshot ?: SetupSnapshotState(),
            focus = f,
            flags = SetupOverview.readinessFlags(items),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WizardUiState())

    fun open(wizard: String, step: String? = null) = focus.update { WizardFocus(wizard, step) }

    fun back() = focus.update { null }

    fun start(wizard: String) {
        focus.update { WizardFocus(wizard) }
        session?.start(wizard)
    }

    /** Continue an interrupted [wizard] at its first step not done or skipped. */
    fun resume(wizard: String) {
        focus.update { WizardFocus(wizard) }
        session?.resume(wizard)
    }

    fun runStep(wizard: String, step: String) {
        focus.update { WizardFocus(wizard, step) }
        session?.runStep(wizard, step)
    }

    fun action(step: SetupStep, action: String, args: JsonObject?) {
        session?.action(step.wizard, step.step, action, args)
    }

    fun abort() {
        val f = focus.value ?: return
        session?.abort(f.wizard)
    }

    fun revert(snapshot: String) {
        session?.revert(snapshot)
    }

    override fun onCleared() {
        session?.close()
        super.onCleared()
    }
}
