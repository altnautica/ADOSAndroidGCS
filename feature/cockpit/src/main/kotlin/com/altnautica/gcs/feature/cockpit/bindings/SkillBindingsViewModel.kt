package com.altnautica.gcs.feature.cockpit.bindings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.binding.BindResult
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CaptureKind { Key, Button }

data class Capture(val slotIndex: Int, val kind: CaptureKind)

sealed interface BindingsUiState {
    data object Loading : BindingsUiState

    data class Ready(
        val loadout: Loadout,
        /** Skills that may occupy a slot. */
        val skills: List<Skill>,
        val capture: Capture?,
    ) : BindingsUiState
}

enum class BindMessage { Reserved, OutOfRange }

/** Edits the Skill Bar loadout: which skill sits in each slot and its key chord and gamepad button. */
@HiltViewModel
class SkillBindingsViewModel @Inject constructor(
    private val store: BindingStore,
    registry: SkillRegistry,
    private val router: AdosInputRouter,
) : ViewModel() {
    private val capture = MutableStateFlow<Capture?>(null)
    private val _messages = MutableSharedFlow<BindMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<BindMessage> = _messages.asSharedFlow()

    private val bindable = registry.skills.map { all ->
        SkillRegistry.barSkills(all, SkillContext(null, null, null, false, false, null))
    }

    val state: StateFlow<BindingsUiState> = combine(store.loadout, bindable, capture) { loadout, skills, cap ->
        BindingsUiState.Ready(loadout, skills, cap)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BindingsUiState.Loading)

    private var captureJob: Job? = null

    private val inputCapture = object : InputCapture {
        override fun consumesChord(chord: String) = capture.value?.kind == CaptureKind.Key
        override val capturesEnter: Boolean get() = false
    }

    fun assign(slotIndex: Int, skillId: String?) {
        viewModelScope.launch { store.assign(slotIndex, skillId) }
    }

    fun clearKey(slotIndex: Int) {
        viewModelScope.launch { store.bindChord(slotIndex, null) }
    }

    fun clearButton(slotIndex: Int) {
        viewModelScope.launch { store.bindButton(slotIndex, null) }
    }

    fun reset() {
        viewModelScope.launch { store.resetToDefaults() }
    }

    /** Waits for the next keyboard chord or gamepad button and binds it to the slot. */
    fun startCapture(slotIndex: Int, kind: CaptureKind) {
        capture.value = Capture(slotIndex, kind)
        router.capture = inputCapture
        captureJob?.cancel()
        captureJob = viewModelScope.launch {
            router.events.collect { event ->
                val cap = capture.value ?: return@collect
                val result = when {
                    cap.kind == CaptureKind.Key && event is InputEvent.Chord -> store.bindChord(cap.slotIndex, event.chord)
                    cap.kind == CaptureKind.Button && event is InputEvent.Button && event.pressed ->
                        store.bindButton(cap.slotIndex, event.button)
                    else -> return@collect
                }
                when (result) {
                    BindResult.Reserved -> _messages.tryEmit(BindMessage.Reserved)
                    BindResult.OutOfRange -> _messages.tryEmit(BindMessage.OutOfRange)
                    else -> Unit
                }
                cancelCapture()
            }
        }
    }

    fun cancelCapture() {
        capture.value = null
        if (router.capture === inputCapture) router.capture = null
        captureJob?.cancel()
        captureJob = null
    }

    override fun onCleared() {
        cancelCapture()
    }
}
