package com.altnautica.gcs.feature.settings.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.input.controller.ControllerCapabilities
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A vehicle allowed stick control in every flight mode; [name] is null when it is not listed. */
data class AllModesVehicle(val id: DeviceId, val name: String?)

data class ControllerUiState(
    val loaded: Boolean = false,
    /** The controller driving input, or null with none attached. */
    val controller: ControllerInfo? = null,
    val capabilities: ControllerCapabilities = ControllerCapabilities.None,
    val settings: ControllerSettings = ControllerSettings(),
    val allModesVehicles: List<AllModesVehicle> = emptyList(),
)

/** The controller's status tab: the attached controller (or the pairing guide) and its preferences. */
@HiltViewModel
class ControllerViewModel @Inject constructor(
    private val manager: ControllerManager,
    private val settings: ControllerSettingsRepository,
    nodeRepository: NodeRepository,
) : ViewModel() {

    val state: StateFlow<ControllerUiState> = combine(manager.active, settings.settings, nodeRepository.nodes) { active, s, nodes ->
        ControllerUiState(
            loaded = true,
            controller = active,
            capabilities = active?.let { manager.capabilities(it.deviceId) } ?: ControllerCapabilities.None,
            settings = s,
            allModesVehicles = s.allModesVehicles.sorted().map { value ->
                val id = DeviceId(value)
                AllModesVehicle(id, nodes?.firstOrNull { it.id == id }?.name)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControllerUiState())

    fun setRumble(on: Boolean) {
        viewModelScope.launch { settings.setRumble(on) }
    }

    fun setLightbar(on: Boolean) {
        viewModelScope.launch { settings.setLightbar(on) }
    }

    fun setExpo(pct: Int) {
        viewModelScope.launch { settings.setExpoPct(pct) }
    }

    fun removeAllModes(id: DeviceId) {
        viewModelScope.launch { settings.setAllModes(id, enabled = false) }
    }
}
