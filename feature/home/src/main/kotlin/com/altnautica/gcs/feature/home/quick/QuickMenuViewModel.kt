package com.altnautica.gcs.feature.home.quick

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.feature.home.FleetCard
import com.altnautica.gcs.feature.home.HomeSurface
import com.altnautica.gcs.feature.home.HomeViewModel
import com.altnautica.gcs.feature.home.SurfaceCard
import com.altnautica.gcs.feature.home.surfaces
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Where the Quick menu sends the operator; the caller closes the menu and navigates. */
sealed interface QuickDestination {
    data object Home : QuickDestination
    data class Surface(val surface: HomeSurface, val nodeId: DeviceId?) : QuickDestination
}

data class QuickMenuUiState(
    /** The vehicle Home has selected, or null when none is listed. */
    val vehicle: FleetCard? = null,
    val surfaces: List<SurfaceCard> = surfaces(null),
    val controller: ControllerInfo? = null,
    /** The open cockpit's summary, or null when no cockpit is started. */
    val cockpit: CockpitSummary? = null,
)

@HiltViewModel
class QuickMenuViewModel @Inject constructor(
    nodeRepository: NodeRepository,
    vitalsRepository: NodeVitalsRepository,
    selectedStore: SelectedVehicleStore,
    controllerManager: ControllerManager,
    cockpitSummaries: CockpitSummaryRegistry,
) : ViewModel() {

    /** Same choice Home makes: the stored vehicle when listed, otherwise the first in row order. */
    private val selectedNode: Flow<NodeSummary?> = combine(
        nodeRepository.nodes,
        selectedStore.selected.onStart { emit(null) },
    ) { list, stored ->
        list?.sortedWith(HomeViewModel.FLEET_RANK)?.let { sorted -> HomeViewModel.pickSelected(sorted, stored) { it.id } }
    }

    /** Vitals restart only when the selected node or its reaches change, not on liveness ticks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val vitals: Flow<Pair<DeviceId, NodeVitals>?> = selectedNode
        .distinctUntilChanged { a, b -> a?.id == b?.id && a?.reaches == b?.reaches }
        .flatMapLatest { node ->
            if (node == null) {
                flowOf(null)
            } else {
                vitalsRepository.vitals(node).onStart { emit(NodeVitals.UNKNOWN) }.map { node.id to it }
            }
        }

    private val vehicle: Flow<FleetCard?> = combine(selectedNode, vitals.onStart { emit(null) }) { node, v ->
        node?.let { n -> HomeViewModel.card(n, v?.takeIf { it.first == n.id }?.second ?: NodeVitals.UNKNOWN) }
    }

    val uiState: StateFlow<QuickMenuUiState> = combine(
        vehicle,
        controllerManager.active,
        cockpitSummaries.summary,
    ) { card, controller, cockpit ->
        QuickMenuUiState(vehicle = card, surfaces = surfaces(card), controller = controller, cockpit = cockpit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), QuickMenuUiState())

    private companion object {
        const val STOP_GRACE_MS = 5_000L
    }
}
