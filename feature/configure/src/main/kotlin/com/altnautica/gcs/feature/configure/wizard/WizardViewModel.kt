package com.altnautica.gcs.feature.configure.wizard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.model.DeviceId
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** Where the aircraft setup surface is: the wizard list, or one wizard (optionally one step of it). */
data class WizardFocus(val wizard: String, val step: String? = null)

data class WizardUiState(
    /** Null while resolving; false when the vehicle is reached straight over MAVLink. */
    val agent: Boolean? = null,
    val setup: SetupSnapshotState = SetupSnapshotState(),
    val focus: WizardFocus? = null,
) {
    val wizard: SetupWizard? get() = focus?.let { setup.wizard(it.wizard) }

    /** The step on screen: the focused one, else the running one, else the first unfinished one. */
    val step: SetupStep? get() {
        val f = focus ?: return null
        f.step?.let { return setup.step(f.wizard, it) }
        val w = wizard ?: return null
        val running = setup.overview?.running?.takeIf { it.wizard == f.wizard }?.step
        running?.let { id -> w.steps.firstOrNull { it.step == id }?.let { return it } }
        if (w.state == SetupWizardState.NotStarted || w.state == SetupWizardState.Aborted) return null
        return w.steps.firstOrNull { it.state != SetupStepState.Done && it.state != SetupStepState.Skipped }
    }

    /** The wizard ran through: show its results and the snapshot revert. */
    val showResults: Boolean get() = focus?.step == null && wizard?.state == SetupWizardState.Done
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = WizardViewModel.Factory::class)
class WizardViewModel internal constructor(
    nodeId: DeviceId,
    initial: WizardFocus?,
    backends: SetupBackendSource,
    /** Setup strings for the screen. */
    val text: SetupText,
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        @Assisted("wizard") wizard: String?,
        @Assisted("step") step: String?,
        backends: SetupBackendSource,
        text: SetupText,
    ) : this(DeviceId(nodeId), wizard?.let { WizardFocus(it, step) }, backends, text)

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String, @Assisted("wizard") wizard: String?, @Assisted("step") step: String?): WizardViewModel
    }

    private val focus = MutableStateFlow(initial)
    private var session: SetupSession? = null

    /** Null until the backend source first answers; then the session, or a holder of null for a direct link. */
    private val sessions: StateFlow<Resolved?> = backends.observe(nodeId).map { backend ->
        session?.close()
        session = backend?.let { SetupSession(it, viewModelScope).also(SetupSession::open) }
        Resolved(session)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private class Resolved(val session: SetupSession?)

    val state: StateFlow<WizardUiState> = combine(
        sessions.flatMapLatest { r -> r?.session?.state ?: flowOf(null) },
        focus,
        sessions,
    ) { snapshot, f, resolved ->
        WizardUiState(agent = resolved?.let { it.session != null }, setup = snapshot ?: SetupSnapshotState(), focus = f)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WizardUiState())

    fun open(wizard: String, step: String? = null) = focus.update { WizardFocus(wizard, step) }

    fun back() = focus.update { null }

    fun start(wizard: String) {
        focus.update { WizardFocus(wizard) }
        session?.start(wizard)
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
