package com.altnautica.gcs.core.agent.node

import android.os.SystemClock
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One shared `/api/status/full` poller per paired node, running only while
 * observed: every 3 s on the LAN, every 10 s through a relay. A ground station
 * is also asked for its carried drone.
 */
@Singleton
class AgentStatusRepository @Inject constructor(
    private val clients: AgentClientFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pollers = ConcurrentHashMap<DeviceId, Flow<NodePoll>>()

    internal var clock: () -> Long = SystemClock::elapsedRealtime

    fun observe(node: DeviceId): Flow<NodePoll> = pollers.getOrPut(node) {
        poll(node).shareIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), replay = 1)
    }

    private fun poll(node: DeviceId): Flow<NodePoll> = flow {
        var last = NodePoll(status = null, lastOkMs = null, carriedDroneId = null, agentVersion = null)
        emit(last)
        while (true) {
            val client = clients.forNode(node)
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
                    }
                    is AgentResult.Err -> Unit
                }
                emit(last)
            }
            delay(if (client?.endpoint?.isRelayed == true) RELAY_INTERVAL_MS else LAN_INTERVAL_MS)
        }
    }

    companion object {
        const val LAN_INTERVAL_MS = 3_000L
        const val RELAY_INTERVAL_MS = 10_000L
        const val STOP_GRACE_MS = 5_000L
    }
}
