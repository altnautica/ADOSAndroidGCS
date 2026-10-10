package com.altnautica.gcs.feature.fleet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.fleet.data.NodeVitals
import com.altnautica.gcs.feature.fleet.data.NodeVitalsRepository
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
import kotlinx.coroutines.launch

data class FleetCard(
    val node: NodeSummary,
    val vitals: NodeVitals,
    /** The node can open a cockpit: a drone, or a ground station carrying one, on a usable link. */
    val canFly: Boolean,
    /** A live drone whose agent can serve a video still. */
    val showThumbnail: Boolean,
)

sealed interface FleetUiState {
    data object Loading : FleetUiState
    data object Empty : FleetUiState
    data class Nodes(val cards: List<FleetCard>) : FleetUiState
}

@HiltViewModel
class FleetViewModel @Inject constructor(
    nodeRepository: NodeRepository,
    private val vitalsRepository: NodeVitalsRepository,
    private val lanNodes: LanNodeSource,
) : ViewModel() {

    private val nodes = nodeRepository.nodes

    /**
     * True while the phone's stored node keys cannot be read: paired nodes may be
     * missing from the list, so the fleet says so and offers [retrySavedNodes]
     * rather than presenting them as never paired.
     */
    val savedNodesUnreadable: StateFlow<Boolean> = lanNodes.savedNodesUnreadable

    fun retrySavedNodes() {
        viewModelScope.launch { lanNodes.retrySavedNodes() }
    }

    /** Vitals depend only on a node's id and reaches, so liveness ticks do not restart them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val vitals: Flow<Map<DeviceId, NodeVitals>> = nodes
        .map { list -> list?.map { it.id to it.reaches } }
        .distinctUntilChanged()
        .flatMapLatest { keys ->
            if (keys.isNullOrEmpty()) {
                flowOf(emptyMap())
            } else {
                val current = nodes.value.orEmpty().associateBy { it.id }
                combine(
                    keys.map { (id, reaches) ->
                        val node = current[id] ?: placeholder(id, reaches)
                        vitalsRepository.vitals(node).onStart { emit(NodeVitals.UNKNOWN) }.map { id to it }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }

    val uiState: StateFlow<FleetUiState> = combine(nodes, vitals.onStart { emit(emptyMap()) }) { list, v ->
        when {
            list == null -> FleetUiState.Loading
            list.isEmpty() -> FleetUiState.Empty
            else -> FleetUiState.Nodes(list.map { card(it, v[it.id] ?: NodeVitals.UNKNOWN) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), FleetUiState.Loading)

    /** Video stills for one card; collected only while that card is on screen. */
    fun thumbnails(id: DeviceId): Flow<ByteArray?> = vitalsRepository.thumbnails(id)

    private fun placeholder(id: DeviceId, reaches: List<Reach>) = NodeSummary(
        id = id, name = id.value, profile = NodeProfile.Unknown, reaches = reaches, liveness = Liveness.Offline,
        agentVersion = null, fcVariant = com.altnautica.gcs.core.model.FcVariant.Unknown, carriedDroneId = null, lastSeenMs = null,
    )

    companion object {
        private const val STOP_GRACE_MS = 5_000L

        fun card(node: NodeSummary, vitals: NodeVitals): FleetCard {
            val usable = node.reaches.firstOrNull()?.let { it !is Reach.Blocked } == true
            val flyable = when (node.profile) {
                NodeProfile.Drone -> true
                NodeProfile.GroundStation -> node.carriedDroneId != null
                NodeProfile.Workstation -> false
                NodeProfile.Unknown -> Reach.DirectFc in node.reaches
            }
            val agentReach = node.reaches.any { it == Reach.Lan || it is Reach.ViaGround }
            return FleetCard(
                node = node,
                vitals = vitals,
                canFly = usable && flyable,
                showThumbnail = node.profile == NodeProfile.Drone && node.liveness == Liveness.Live && agentReach,
            )
        }
    }
}
