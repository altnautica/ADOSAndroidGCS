package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The cockpit's node, the vehicle it flies, and that vehicle's telemetry and services. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitNodeFlows(
    scope: CoroutineScope,
    nodeId: DeviceId,
    nodeSources: Set<NodeSource>,
    vehicles: VehicleProvider,
    services: VehicleServicesProvider,
) {
    private val allNodes: Flow<List<NodeSummary>> =
        if (nodeSources.isEmpty()) flowOf(emptyList())
        else combine(nodeSources.map { src -> src.nodes }) { lists -> lists.flatMap { it.toList() } }

    private fun merged(nodes: List<NodeSummary>, id: DeviceId): NodeSummary? {
        val matches = nodes.filter { it.id == id }
        if (matches.isEmpty()) return null
        val reaches = matches.flatMap { it.reaches }.distinct().sortedBy { reachRank(it) }
        return matches.first().copy(reaches = reaches)
    }

    /** The node the cockpit was opened for. */
    val node: StateFlow<NodeSummary?> = allNodes.map { merged(it, nodeId) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * The vehicle node actually flown: the node itself, or a ground station's carried
     * drone. A vehicle no link source lists (a log replay) is flown by its own id.
     */
    val vehicleNodeId: StateFlow<DeviceId?> = node.map { n ->
        when {
            n == null -> nodeId
            n.profile == NodeProfile.GroundStation -> n.carriedDroneId
            else -> n.id
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, nodeId)

    val vehicleNode: StateFlow<NodeSummary?> = combine(allNodes, vehicleNodeId) { nodes, id ->
        id?.let { merged(nodes, it) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    val vehicle: StateFlow<Vehicle?> = vehicleNodeId.flatMapLatest { id ->
        if (id == null) flowOf(null) else vehicles.vehicle(id)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Per-topic telemetry for the HUD, which reads each flow inside its draw pass. */
    val telemetry: StateFlow<VehicleTelemetry?> = vehicle.map { it?.telemetry }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val vehicleServices: StateFlow<VehicleServices?> = vehicleNodeId.flatMapLatest { id ->
        if (id == null) flowOf(null) else services.services(id)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val nodesLoaded: StateFlow<Boolean> = allNodes.map { true }
        .stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    val status: StateFlow<CockpitStatus> = combine(node, vehicleNodeId, vehicle, nodesLoaded) { n, vid, v, loaded ->
        when {
            n?.profile == NodeProfile.GroundStation && vid == null -> CockpitStatus.NoCarriedDrone
            v != null -> CockpitStatus.Ready
            n == null && loaded -> CockpitStatus.UnknownNode
            else -> CockpitStatus.Connecting
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), CockpitStatus.Connecting)

    companion object {
        /** Commandable links first: direct, LAN, via ground, phone radio, cloud live, cloud queue. */
        fun reachRank(r: Reach): Int = when (r) {
            Reach.DirectFc -> 0
            Reach.Lan -> 1
            is Reach.ViaGround -> 2
            Reach.PhoneRadio -> 3
            Reach.CloudLive -> 4
            Reach.CloudQueue -> 5
            is Reach.Blocked -> 6
        }
    }
}
