package com.altnautica.gcs.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import kotlinx.coroutines.flow.runningFold
import com.altnautica.gcs.core.link.node.canFly
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
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
    /** The node has an agent link (LAN or through its ground station), so it can be unpaired. */
    val hasAgentLink: Boolean,
    /**
     * The node's vitals can only come from its direct flight-controller link, and that link is
     * closed: nothing is connecting, so Home shows no vitals and no "Connecting…".
     */
    val directLinkClosed: Boolean,
)

data class HomeUiState(
    val vehicles: List<FleetCard> = emptyList(),
    /** The stored selection when it is listed, otherwise the first vehicle. */
    val selected: FleetCard? = null,
    val loading: Boolean = true,
    /**
     * True while the phone's stored node keys cannot be read: paired nodes may be
     * missing, so Home says so and offers a retry rather than presenting them as never paired.
     */
    val savedNodesUnreadable: Boolean = false,
    val demoEnabled: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    nodeRepository: NodeRepository,
    private val vitalsRepository: NodeVitalsRepository,
    private val lanNodes: LanNodeSource,
    private val selectedStore: SelectedVehicleStore,
    private val demoSettings: DemoSettings,
    vehicleRepository: VehicleRepository,
) : ViewModel() {

    private val nodes = nodeRepository.nodes

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
                        val live = vitalsRepository.vitals(node).onStart { emit(NodeVitals.UNKNOWN) }
                        // A direct link reports nothing once closed: keep what it last reported.
                        (if (vitalsNeedOpenLink(node)) live.keepLastKnown() else live).map { id to it }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }

    private val openLinks: Flow<Set<DeviceId>> = vehicleRepository.openVehicles.map { it.keys }.distinctUntilChanged()

    private val cards: Flow<List<FleetCard>?> = combine(nodes, vitals.onStart { emit(emptyMap()) }, openLinks) { list, v, open ->
        list?.sortedWith(FLEET_RANK)?.map { card(it, v[it.id] ?: NodeVitals.UNKNOWN, linkOpen = it.id in open) }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        cards,
        selectedStore.selected.onStart { emit(null) },
        lanNodes.savedNodesUnreadable,
        demoSettings.enabled.onStart { emit(false) },
    ) { list, stored, unreadable, demo ->
        HomeUiState(
            vehicles = list.orEmpty(),
            selected = list?.let { l -> pickSelected(l, stored) { it.node.id } },
            loading = list == null,
            savedNodesUnreadable = unreadable,
            demoEnabled = demo,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), HomeUiState())

    fun select(id: DeviceId) {
        viewModelScope.launch { selectedStore.select(id) }
    }

    /** Moves the selection [delta] vehicles along the row, wrapping at either end. */
    fun selectNext(delta: Int) {
        val state = uiState.value
        val list = state.vehicles
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.node.id == state.selected?.node?.id }.coerceAtLeast(0)
        select(list[(index + delta).mod(list.size)].node.id)
    }

    /** Turns demo mode on and selects the demo vehicle. */
    fun enableDemo() {
        viewModelScope.launch { selectedStore.select(demoSettings.enable()) }
    }

    fun retrySavedNodes() {
        viewModelScope.launch { lanNodes.retrySavedNodes() }
    }

    /** Video stills for one vehicle; collected only while it is on screen. */
    fun thumbnails(id: DeviceId): Flow<ByteArray?> = vitalsRepository.thumbnails(id)

    companion object {
        private const val STOP_GRACE_MS = 5_000L

        /**
         * Order of the vehicle row: liveness first (as merged), then profile so
         * flyable vehicles lead and infrastructure trails. The sort is stable,
         * so the merger's alphabetical order holds inside each group.
         */
        internal val FLEET_RANK = compareBy<NodeSummary>({ it.liveness.ordinal }, { profileRank(it.profile) })

        /** Drone 0, Visor 1, Workstation 2, Ground station 3, unknown last. */
        fun profileRank(profile: NodeProfile): Int = when (profile) {
            NodeProfile.Drone -> 0
            NodeProfile.Visor -> 1
            NodeProfile.Workstation -> 2
            NodeProfile.GroundStation -> 3
            NodeProfile.Unknown -> 4
        }

        /**
         * [linkOpen] says whether the node's vehicle link is open now; it matters only for a node
         * whose vitals come from that link alone (see [vitalsNeedOpenLink]).
         */
        fun card(node: NodeSummary, vitals: NodeVitals, linkOpen: Boolean): FleetCard {
            val agentReach = node.reaches.any { it == Reach.Lan || it is Reach.ViaGround }
            return FleetCard(
                node = node,
                vitals = vitals,
                canFly = node.canFly(),
                showThumbnail = node.profile == NodeProfile.Drone && node.liveness == Liveness.Live && agentReach,
                hasAgentLink = agentReach,
                directLinkClosed = vitalsNeedOpenLink(node) && !linkOpen,
            )
        }

        /** No agent reports this node's vitals; they arrive only while its direct flight-controller link is open. */
        fun vitalsNeedOpenLink(node: NodeSummary): Boolean =
            node.reaches.none { it == Reach.Lan || it is Reach.ViaGround } && node.reaches.firstOrNull() == Reach.DirectFc

        /** The stored vehicle when it is in [items], otherwise the first one. */
        internal fun <T> pickSelected(items: List<T>, stored: DeviceId?, id: (T) -> DeviceId): T? =
            items.firstOrNull { id(it) == stored } ?: items.firstOrNull()

        internal fun placeholder(id: DeviceId, reaches: List<Reach>) = NodeSummary(
            id = id, name = id.value, profile = NodeProfile.Unknown, reaches = reaches, liveness = Liveness.Offline,
            agentVersion = null, fcVariant = FcVariant.Unknown, carriedDroneId = null, lastSeenMs = null,
        )
    }
}

/** Replaces an all-unknown reading (the link closed) with the last reading that knew something. */
internal fun Flow<NodeVitals>.keepLastKnown(): Flow<NodeVitals> =
    runningFold(NodeVitals.UNKNOWN) { last, v -> if (v == NodeVitals.UNKNOWN) last else v }.distinctUntilChanged()
