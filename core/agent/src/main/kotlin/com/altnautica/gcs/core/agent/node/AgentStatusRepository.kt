package com.altnautica.gcs.core.agent.node

import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.net.LanNetworkWatcher
import com.altnautica.gcs.core.agent.net.awaitWake
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shared `/api/status/full` poller per paired node, running only while
 * observed: every 3 s on the LAN, every 10 s through a relay. A ground station
 * is also asked for its carried drone.
 *
 * A refused key is reported on the poll ([NodePoll.keyRefused]) and asked again
 * only every [UNAUTHORIZED_INTERVAL_MS]; an unreachable node is asked less often
 * after each failure, up to [UNREACHABLE_MAX_MS]. A network change ([kicks]), or
 * discovery first seeing a node that had no address, polls at once.
 */
@Singleton
class AgentStatusRepository internal constructor(
    private val clients: AgentClientFactory,
    private val kicks: Flow<Unit> = emptyFlow(),
    private val discovered: Flow<List<DiscoveredAgent>> = flowOf(emptyList()),
) {
    @Inject
    constructor(clients: AgentClientFactory, discovery: LanDiscovery, watcher: LanNetworkWatcher) :
        this(clients, watcher.kicks, discovery.agents)

    internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = ConcurrentHashMap<DeviceId, SharedFlow<NodePoll>>()

    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /** Nodes with a live poller entry; a poller leaves the map once its last observer has gone. */
    internal val pollerCount: Int get() = pollers.size

    fun observe(node: DeviceId): Flow<NodePoll> = pollers.computeIfAbsent(node) { id ->
        lateinit var shared: SharedFlow<NodePoll>
        shared = poll(id)
            .onCompletion { pollers.remove(id, shared) }
            .shareIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), replay = 1)
        shared
    }

    private fun poll(node: DeviceId): Flow<NodePoll> = flow {
        var last = NodePoll(status = null, lastOkMs = null, carriedDroneId = null, agentVersion = null)
        emit(last)
        // Logged once per change, not every poll.
        var warnedNoEndpoint = false
        var lastError: String? = null
        var unreachable = 0
        while (true) {
            val client = clients.forNode(node)
            val relayed = client?.endpoint?.isRelayed == true
            if (client == null && !warnedNoEndpoint) Log.w(TAG, "status_no_endpoint node=${node.value}")
            warnedNoEndpoint = client == null
            val interval = if (relayed) RELAY_INTERVAL_MS else LAN_INTERVAL_MS
            var wait = interval
            if (client != null) {
                when (val r = client.statusFull()) {
                    is AgentResult.Ok -> {
                        val status = r.value
                        val paired = if (LanNodeAssembler.profile(status.profile) == NodeProfile.GroundStation) {
                            client.groundStation.status().getOrNull()?.pairedDrone
                        } else {
                            null
                        }
                        val version = client.version().getOrNull()?.agentVersion
                        last = NodePoll(status, clock(), paired?.deviceId, version ?: status.version, paired)
                        lastError = null
                        unreachable = 0
                    }
                    is AgentResult.Err -> {
                        val error = r.error.toString()
                        if (error != lastError) Log.w(TAG, "status_err node=${node.value} err=$error")
                        lastError = error
                        when (r.error) {
                            AgentError.Unauthorized -> {
                                last = last.copy(keyRefused = true)
                                wait = UNAUTHORIZED_INTERVAL_MS
                            }
                            is AgentError.Network -> {
                                unreachable++
                                wait = (interval shl minOf(unreachable, MAX_BACKOFF_SHIFT)).coerceAtMost(UNREACHABLE_MAX_MS)
                            }
                            else -> Unit
                        }
                    }
                }
                last = last.copy(maxAgeMs = if (relayed) RELAY_MAX_POLL_AGE_MS else LAN_MAX_POLL_AGE_MS)
                emit(last)
            }
            // A node without an address is asked again as soon as discovery newly sees it.
            val seen: Flow<Unit> = if (client == null) {
                discovered.map { l -> l.any { it.deviceId == node.value } }.distinctUntilChanged().drop(1).filter { it }.map { }
            } else {
                emptyFlow()
            }
            if (merge(kicks, seen).awaitWake(wait)) unreachable = 0
        }
    }

    companion object {
        const val LAN_INTERVAL_MS = 3_000L
        const val RELAY_INTERVAL_MS = 10_000L
        const val STOP_GRACE_MS = 5_000L

        /** Wait after a refused key before asking again (the key will not start working by itself). */
        const val UNAUTHORIZED_INTERVAL_MS = 30_000L

        /** Longest wait between polls of a node that does not answer. */
        const val UNREACHABLE_MAX_MS = 60_000L
        private const val MAX_BACKOFF_SHIFT = 5
        private const val TAG = "AdosLink"

        /**
         * Oldest poll still shown as current: two status intervals plus one request timeout,
         * so a single slow or failed poll does not blank the values. A relayed node polls less
         * often and through slower calls, so its poll may be older.
         */
        const val LAN_MAX_POLL_AGE_MS = 2 * LAN_INTERVAL_MS + AgentHttp.LAN_TIMEOUT_S * 1_000L
        const val RELAY_MAX_POLL_AGE_MS = 2 * RELAY_INTERVAL_MS + AgentHttp.RELAY_TIMEOUT_S * 1_000L
    }
}
