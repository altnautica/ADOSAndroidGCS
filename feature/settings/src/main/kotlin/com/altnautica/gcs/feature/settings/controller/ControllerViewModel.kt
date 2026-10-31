package com.altnautica.gcs.feature.settings.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.controller.AxisRange
import com.altnautica.gcs.core.skills.input.controller.ControllerCapabilities
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.input.controller.Rumble
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A vehicle allowed stick control in every flight mode; [name] is null when it is not listed. */
data class AllModesVehicle(val id: DeviceId, val name: String?)

data class ControllerUiState(
    val loaded: Boolean = false,
    /** The controller driving input, or null with none attached. */
    val controller: ControllerInfo? = null,
    val capabilities: ControllerCapabilities = ControllerCapabilities.None,
    val ranges: List<AxisRange> = emptyList(),
    val settings: ControllerSettings = ControllerSettings(),
    val allModesVehicles: List<AllModesVehicle> = emptyList(),
)

/** The controller surface: attached controller, its preferences and the live input test. */
@HiltViewModel
class ControllerViewModel @Inject constructor(
    private val manager: ControllerManager,
    private val settings: ControllerSettingsRepository,
    private val router: AdosInputRouter,
    nodeRepository: NodeRepository,
) : ViewModel() {

    val state: StateFlow<ControllerUiState> = combine(manager.active, settings.settings, nodeRepository.nodes) { active, s, nodes ->
        ControllerUiState(
            loaded = true,
            controller = active,
            capabilities = active?.let { manager.capabilities(it.deviceId) } ?: ControllerCapabilities.None,
            ranges = active?.let { manager.motionRanges(it.deviceId) }.orEmpty(),
            settings = s,
            allModesVehicles = s.allModesVehicles.sorted().map { value ->
                val id = DeviceId(value)
                AllModesVehicle(id, nodes?.firstOrNull { it.id == id }?.name)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllerUiState())

    /** Pressed standard buttons (bit n = [GamepadButton] n); updates while the UI navigates too. */
    val buttons: StateFlow<Int> = router.buttons

    val axes: StateFlow<GamepadAxes> = router.axes

    private val _leave = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits when the back button has been held long enough to leave the input test. */
    val leave: SharedFlow<Unit> = _leave.asSharedFlow()

    private var testJob: Job? = null
    private var lightJob: Job? = null

    fun setRumble(on: Boolean) {
        viewModelScope.launch { settings.setRumble(on) }
    }

    fun setLightbar(on: Boolean) {
        viewModelScope.launch { settings.setLightbar(on) }
    }

    fun setDeadZone(pct: Int) {
        viewModelScope.launch { settings.setDeadZonePct(pct) }
    }

    fun setExpo(pct: Int) {
        viewModelScope.launch { settings.setExpoPct(pct) }
    }

    fun removeAllModes(id: DeviceId) {
        viewModelScope.launch { settings.setAllModes(id, enabled = false) }
    }

    /**
     * While the input test shows, the left stick reads as a stick instead of moving focus,
     * and holding back for [LEAVE_HOLD_MS] emits [leave]; a short press only lights the diagram.
     */
    fun setTestActive(active: Boolean) {
        router.suppressStickNavigation = active
        testJob?.cancel()
        testJob = if (!active) {
            null
        } else {
            viewModelScope.launch {
                buttons.map { it and (1 shl GamepadButton.B) != 0 }.distinctUntilChanged().collectLatest { held ->
                    if (held) {
                        delay(LEAVE_HOLD_MS)
                        _leave.tryEmit(Unit)
                    }
                }
            }
        }
    }

    /** Rumbles once, even with rumble turned off, so the hardware can be checked. */
    fun testRumble() {
        manager.rumble(Rumble.Warning, force = true)
    }

    /** Red, green and blue in turn, then back to [restoreArgb] (or the controller default with status colours off). */
    fun cycleLightbar(restoreArgb: Int) {
        lightJob?.cancel()
        lightJob = viewModelScope.launch {
            try {
                for (argb in TEST_COLOURS) {
                    manager.setLightbar(argb, force = true)
                    delay(LIGHT_STEP_MS)
                }
            } finally {
                restoreLightbar(restoreArgb)
            }
        }
    }

    private fun restoreLightbar(restoreArgb: Int) {
        manager.setLightbar(if (state.value.settings.lightbar) restoreArgb else null, force = true)
    }

    override fun onCleared() {
        if (testJob != null) router.suppressStickNavigation = false
        testJob?.cancel()
        lightJob?.cancel()
    }

    companion object {
        const val LEAVE_HOLD_MS = 1_000L
        private const val LIGHT_STEP_MS = 700L

        /** Pure red, green and blue: a hardware check, not themed status colours. */
        private val TEST_COLOURS = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    }
}
