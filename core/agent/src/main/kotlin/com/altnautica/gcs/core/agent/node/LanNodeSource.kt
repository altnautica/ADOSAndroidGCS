package com.altnautica.gcs.core.agent.node

import android.os.SystemClock
import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent nodes on the local network: every paired node in the vault (polled for
 * liveness) plus every unpaired node discovery sees. Discovery and polling run
 * only while [nodes] is collected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LanNodeSource @Inject constructor(
    private val vault: CredentialVault,
    private val discovery: LanDiscovery,
    private val status: AgentStatusRepository,
) : NodeSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val polls: Flow<Map<String, NodePoll>> = vault.nodes.flatMapLatest { stored ->
        if (stored.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(stored.map { cred -> status.observe(cred.id) }) { results ->
                stored.map { it.deviceId }.zip(results.toList()).toMap()
            }
        }
    }

    /** Re-evaluates liveness each second even when nothing else changes. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(TICK_MS)
        }
    }

    override val nodes: Flow<List<NodeSummary>> = combine(
        vault.nodes,
        // Discovery reports nothing until NSD resolves a service; paired nodes must not wait for it.
        discovery.browse().onStart { emit(emptyList()) }.onEach { rememberAddresses(it) },
        polls,
        ticker,
    ) { stored, discovered, polled, now ->
        LanNodeAssembler.assemble(stored, discovered, polled, now)
    }.distinctUntilChanged().shareIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), replay = 1)

    /** Persist a paired node's newly discovered address so it is reachable after discovery stops. */
    private fun rememberAddresses(discovered: List<DiscoveredAgent>) {
        val stored: Map<String, NodeCredential> = vault.nodes.value.associateBy { it.deviceId }
        for (agent in discovered) {
            val cred = stored[agent.deviceId ?: continue] ?: continue
            if (cred.lastIp != agent.host || cred.lastPort != agent.port) {
                scope.launch { vault.updateAddress(cred.id, agent.host, agent.port) }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val STOP_GRACE_MS = 5_000L
    }
}
