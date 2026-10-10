package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.agent.telemetry.TelemetrySnapshotSource
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.link.di.LinkScope
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.link.queue.QueuedVehicle
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.mavlink.MavlinkSigning
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleProvider
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [VehicleProvider] and [VehicleServicesProvider] over every [NodeSource] and
 * [TransportProvider] in the graph.
 *
 * Observing a vehicle opens its best reach (the first in
 * [NodeSummary.reaches] that some provider supports). When the transport
 * fails, or the vehicle stops sending while another reach exists, the link
 * falls to the next reach after 3 s; while on a worse reach the best one is
 * probed every 10 s and taken back when it opens. The link closes 5 s after
 * the last observer leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VehicleRepository @Inject constructor(
    private val nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    private val providers: Set<@JvmSuppressWildcards TransportProvider>,
    private val snapshots: TelemetrySnapshotSource,
    private val queueSinks: Set<@JvmSuppressWildcards QueuedCommandSink>,
    private val vault: CredentialVault,
    private val activity: LinkActivity,
    private val replays: ReplayVehicles,
    private val clock: ElapsedClock,
    @param:LinkScope private val scope: CoroutineScope,
) : VehicleProvider, VehicleServicesProvider {

    private val sessions = ConcurrentHashMap<DeviceId, Flow<Vehicle?>>()

    /** Links currently open; empty when no vehicle is connected. */
    val openLinks: StateFlow<List<OpenLink>> get() = activity.openLinks

    private val _openVehicles = MutableStateFlow<Map<DeviceId, Vehicle>>(emptyMap())

    /**
     * Vehicles whose link is open right now (an attached MAVLink transport, or
     * a live agent snapshot feed). Reading them does not keep a link open; only
     * collecting [vehicle] does.
     */
    val openVehicles: StateFlow<Map<DeviceId, Vehicle>> = _openVehicles.asStateFlow()

    private fun markOpen(v: Vehicle) = _openVehicles.update { it + (v.id to v) }

    private fun markClosed(id: DeviceId) = _openVehicles.update { it - id }

    private val heldClosed = MutableStateFlow<Set<DeviceId>>(emptySet())

    /**
     * Closes the link to [id] and keeps it closed while current observers stay;
     * the vehicle reports [Reach.Blocked] ([BlockReason.Offline]). The hold ends
     * with [connect] or when the link is next opened from scratch (all
     * observers gone, then a new one).
     */
    fun disconnect(id: DeviceId) {
        heldClosed.update { it + id }
    }

    /** Lifts a [disconnect] hold. */
    fun connect(id: DeviceId) {
        heldClosed.update { it - id }
    }

    override fun vehicle(id: DeviceId): Flow<Vehicle?> {
        val live = sessions.getOrPut(id) {
            session(id).shareIn(scope, SharingStarted.WhileSubscribed(KEEP_OPEN_MS, 0), replay = 1)
        }
        // An open replay takes the id over until it is closed.
        return replays.active.map { it[id] }.distinctUntilChanged().flatMapLatest { replay ->
            if (replay != null) flowOf(replay) else live
        }
    }

    override fun services(id: DeviceId): Flow<VehicleServices?> = vehicle(id).map { it as? MavlinkVehicle }

    /** The node as every source together describes it, reaches merged best-first. */
    fun node(id: DeviceId): Flow<NodeSummary?> {
        if (nodeSources.isEmpty()) return flowOf(null)
        return combine(nodeSources.map { s -> s.nodes.onStart { emit(emptyList()) } }) { lists ->
            mergeSummaries(lists.flatMap { list -> list.filter { it.id == id } })
        }.distinctUntilChanged()
    }

    private enum class Kind { Mavlink, Snapshot, Queue }

    private fun session(id: DeviceId): Flow<Vehicle?> = channelFlow {
        connect(id)
        val nodes = node(id).stateIn(this, SharingStarted.Eagerly, null)
        nodes.map { n -> n?.let(::kindOf) }
            .distinctUntilChanged()
            .collectLatestCompat { kind ->
                when (kind) {
                    null -> send(null)
                    Kind.Queue -> {
                        val n = nodes.value ?: return@collectLatestCompat
                        val (reach, sink) = queueRouteOf(n) ?: return@collectLatestCompat
                        send(QueuedVehicle(id, reach, sink))
                        awaitCancellation()
                    }
                    Kind.Snapshot -> {
                        val n = nodes.value ?: return@collectLatestCompat
                        val reach = n.reaches.firstOrNull { it == Reach.Lan || it is Reach.ViaGround } ?: n.reaches.firstOrNull()
                            ?: Reach.Blocked(BlockReason.Offline)
                        val v = SnapshotVehicle(id, reach, this, snapshots, clock)
                        send(v)
                        markOpen(v)
                        try {
                            awaitCancellation()
                        } finally {
                            markClosed(id)
                            v.shutdown()
                        }
                    }
                    Kind.Mavlink -> {
                        val v = MavlinkVehicle(id, this, clock, activity)
                        vault.mavlinkSigningKey(id)?.let { key -> v.link.signing = MavlinkSigning(key, linkId = 0) }
                        send(v)
                        try {
                            supervise(v, nodes)
                        } finally {
                            withContext(NonCancellable) {
                                activity.closed(id)
                                markClosed(id)
                                v.shutdown()
                            }
                        }
                    }
                }
            }
    }

    /**
     * MSP flight controllers use the agent snapshot; nodes with a reach some
     * transport supports get a MAVLink link; nodes whose only usable reach is a
     * command queue get a [QueuedVehicle].
     */
    private fun kindOf(n: NodeSummary): Kind = when {
        n.fcVariant == FcVariant.Msp -> Kind.Snapshot
        candidatesOf(n).isNotEmpty() -> Kind.Mavlink
        queueRouteOf(n) != null -> Kind.Queue
        else -> Kind.Mavlink
    }

    private fun queueRouteOf(n: NodeSummary): Pair<Reach, QueuedCommandSink>? {
        for (reach in n.reaches) {
            if (reach is Reach.Blocked) continue
            val sink = queueSinks.firstOrNull { it.supports(reach) } ?: continue
            return reach to sink
        }
        return null
    }

    private sealed interface Outcome {
        data object Failed : Outcome
        data object Upgrade : Outcome
        data object ReachesChanged : Outcome
    }

    private suspend fun supervise(v: MavlinkVehicle, nodes: StateFlow<NodeSummary?>) {
        var index = 0
        while (true) {
            if (v.id in heldClosed.value) {
                v.link.detach()
                activity.closed(v.id)
                markClosed(v.id)
                v.setReach(Reach.Blocked(BlockReason.Offline))
                heldClosed.first { v.id !in it }
                index = 0
                continue
            }
            val node = nodes.value ?: nodes.filter { it != null }.first()!!
            val candidates = candidatesOf(node)
            if (candidates.isEmpty()) {
                v.link.detach()
                activity.closed(v.id)
                markClosed(v.id)
                v.setReach(node.reaches.firstOrNull { it is Reach.Blocked } ?: Reach.Blocked(BlockReason.Offline))
                nodes.first { it != null && candidatesOf(it).isNotEmpty() }
                index = 0
                continue
            }
            if (index >= candidates.size) index = 0
            val reach = candidates[index]
            val transport = openOrNull(node, reach)
            if (transport == null || transport.state.value.isTerminal()) {
                transport?.close()
                delay(FALLBACK_DELAY_MS)
                index = (index + 1) % candidates.size
                continue
            }
            v.link.attach(transport)
            v.setReach(reach)
            activity.opened(v.id, reach)
            markOpen(v)
            when (monitor(v, transport, nodes, candidates, index, node)) {
                Outcome.Failed -> {
                    v.link.detach()
                    activity.closed(v.id)
                    markClosed(v.id)
                    delay(FALLBACK_DELAY_MS)
                    index = (index + 1) % candidates.size
                }
                Outcome.Upgrade, Outcome.ReachesChanged -> {
                    v.link.detach()
                    index = 0
                }
            }
        }
    }

    private suspend fun monitor(
        v: MavlinkVehicle,
        transport: MavlinkByteTransport,
        nodes: StateFlow<NodeSummary?>,
        candidates: List<Reach>,
        index: Int,
        node: NodeSummary,
    ): Outcome {
        val openedAt = clock.nowMs()
        val failed = transport.state.filter { it.isTerminal() }.map { Outcome.Failed }
        val held = heldClosed.filter { v.id in it }.map { Outcome.ReachesChanged }
        val changed = nodes.filter { it != null && candidatesOf(it) != candidates }.map { Outcome.ReachesChanged }
        val upgrade = if (index > 0) {
            flow {
                while (true) {
                    delay(UPGRADE_PROBE_MS)
                    if (probe(node, candidates[0])) emit(Outcome.Upgrade)
                }
            }
        } else {
            emptyFlow()
        }
        val silent = if (candidates.size > 1) {
            flow {
                while (true) {
                    delay(SILENCE_CHECK_MS)
                    val last = v.link.lastTargetFrameAtMs.value ?: openedAt
                    if (clock.nowMs() - maxOf(last, openedAt) > SILENCE_FALLBACK_MS) emit(Outcome.Failed)
                }
            }
        } else {
            emptyFlow()
        }
        return merge(failed, held, changed, upgrade, silent).first()
    }

    /** True when [reach] opens within 5 s; the probe transport is closed again. */
    private suspend fun probe(node: NodeSummary, reach: Reach): Boolean {
        val t = openOrNull(node, reach) ?: return false
        return try {
            withTimeoutOrNull(PROBE_OPEN_MS) { t.state.first { it == TransportState.Open || it.isTerminal() } } == TransportState.Open
        } finally {
            t.close()
        }
    }

    private suspend fun openOrNull(node: NodeSummary, reach: Reach): MavlinkByteTransport? {
        val provider = providers.firstOrNull { it.supports(reach) } ?: return null
        return try {
            provider.open(node, reach)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun candidatesOf(node: NodeSummary): List<Reach> =
        node.reaches.filter { r -> r !is Reach.Blocked && providers.any { it.supports(r) } }

    private fun TransportState.isTerminal() = this is TransportState.Closed || this is TransportState.Failed

    companion object {
        const val KEEP_OPEN_MS = 5_000L
        const val FALLBACK_DELAY_MS = 3_000L
        const val UPGRADE_PROBE_MS = 10_000L
        const val PROBE_OPEN_MS = 5_000L
        const val SILENCE_CHECK_MS = 1_000L
        const val SILENCE_FALLBACK_MS = 5_000L

        private fun rank(r: Reach): Int = when (r) {
            Reach.DirectFc -> 0
            Reach.Lan -> 1
            is Reach.ViaGround -> 2
            Reach.PhoneRadio -> 3
            Reach.CloudLive -> 4
            Reach.CloudQueue -> 5
            is Reach.Blocked -> 6
        }

        /** Unions the reaches of every summary of one node; the first summary supplies the rest. */
        internal fun mergeSummaries(summaries: List<NodeSummary>): NodeSummary? {
            val first = summaries.firstOrNull() ?: return null
            val reaches = summaries.flatMap { it.reaches }.distinct().sortedBy(::rank)
            val variant = summaries.map { it.fcVariant }.firstOrNull { it != FcVariant.Unknown } ?: FcVariant.Unknown
            return first.copy(
                reaches = reaches,
                fcVariant = variant,
                liveness = summaries.minOf { it.liveness },
                agentVersion = summaries.firstNotNullOfOrNull { it.agentVersion },
                carriedDroneId = summaries.firstNotNullOfOrNull { it.carriedDroneId },
                lastSeenMs = summaries.mapNotNull { it.lastSeenMs }.maxOrNull(),
            )
        }
    }
}

/** `collectLatest` whose block gets a scope, so vehicles launched in it end when the next value arrives. */
private suspend fun <T> Flow<T>.collectLatestCompat(block: suspend CoroutineScope.(T) -> Unit) =
    collectLatest { value -> coroutineScope { block(value) } }
