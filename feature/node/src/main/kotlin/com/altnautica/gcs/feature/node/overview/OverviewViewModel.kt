package com.altnautica.gcs.feature.node.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.groundstation.StationStatus
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.node.AgentStatusRepository
import com.altnautica.gcs.core.agent.node.NodePoll
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.LinkQuality
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.node.NodeTabs
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.PageProblem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Live flight-controller values for a node reached directly (no agent). */
data class VehicleSnapshot(
    val heartbeat: HeartbeatState?,
    val battery: BatteryState?,
    val gps: GpsState?,
    val link: LinkQuality?,
)

sealed interface StationLoad {
    data object Loading : StationLoad
    data class Ready(val status: StationStatus) : StationLoad
    data class Failed(val problem: PageProblem) : StationLoad
}

data class OverviewUiState(
    val node: NodeSummary? = null,
    /** Agent `/api/status/full` poll; null when the node has no agent link. */
    val agent: NodePoll? = null,
    /** Ground-station `/status`; null for other profiles. */
    val station: StationLoad? = null,
    /** Direct flight-controller values; null when the node has an agent. */
    val vehicle: VehicleSnapshot? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = OverviewViewModel.Factory::class)
class OverviewViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    nodes: NodeRepository,
    private val agentStatus: AgentStatusRepository,
    private val vehicles: VehicleProvider,
    private val clients: NodeClients,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): OverviewViewModel
    }

    private val id = DeviceId(nodeId)

    /** The parts of the node that decide which feeds run. */
    private data class Shape(val node: NodeSummary, val agent: Boolean, val ground: Boolean, val direct: Boolean)

    private val shape: Flow<Shape> = nodes.node(id).filterNotNull().map { n ->
        Shape(
            node = n,
            agent = NodeTabs.hasAgentLink(n),
            ground = n.profile == NodeProfile.GroundStation,
            direct = n.reaches.firstOrNull() == Reach.DirectFc,
        )
    }

    private val feedKey = shape.distinctUntilChanged { a, b -> a.agent == b.agent && a.ground == b.ground && a.direct == b.direct }

    val state: StateFlow<OverviewUiState> = combine(
        shape.map { it.node },
        feedKey.flatMapLatest { s -> if (s.agent) agentStatus.observe(id) else flowOf(null) },
        feedKey.flatMapLatest { s -> if (s.agent && s.ground) stationPoll(s.node) else flowOf(null) },
        feedKey.flatMapLatest { s -> if (s.direct && !s.agent) vehicleFeed() else flowOf(null) },
    ) { node, poll, station, vehicle ->
        OverviewUiState(node = node, agent = poll, station = station, vehicle = vehicle)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewUiState())

    private fun stationPoll(node: NodeSummary): Flow<StationLoad> = flow {
        emit(StationLoad.Loading)
        var last: StationLoad = StationLoad.Loading
        while (true) {
            val client = clients.clientFor(node)
            val next = when (val r = client?.groundStation?.status()) {
                null -> StationLoad.Failed(PageProblem.NoAgentLink)
                is AgentResult.Ok -> StationLoad.Ready(r.value)
                // Keep the last good reading on a transient failure; the agent poll shows staleness.
                is AgentResult.Err -> if (last is StationLoad.Ready) last else StationLoad.Failed(PageProblem.from(r.error))
            }
            last = next
            emit(next)
            delay(STATION_POLL_MS)
        }
    }

    private fun vehicleFeed(): Flow<VehicleSnapshot?> = vehicles.vehicle(id).flatMapLatest { v ->
        if (v == null) {
            flowOf(null)
        } else {
            combine(v.telemetry.heartbeat, v.telemetry.battery, v.telemetry.gps, v.telemetry.linkQuality) { hb, b, g, l ->
                VehicleSnapshot(hb, b, g, l)
            }
        }
    }

    private companion object {
        const val STATION_POLL_MS = 3_000L
    }
}
