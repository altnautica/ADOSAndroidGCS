package com.altnautica.gcs.core.agent

import android.util.Log
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.model.AgentVersion
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PhoneRadioUplink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** `/api/version` answers per endpoint, kept for [TTL_MS]. */
@Singleton
class VersionCache @Inject constructor() {
    private data class Entry(val version: AgentVersion, val atMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    internal var clock: () -> Long = System::currentTimeMillis

    fun get(endpoint: AgentEndpoint): AgentVersion? {
        val entry = entries[key(endpoint)] ?: return null
        return entry.version.takeIf { clock() - entry.atMs < TTL_MS }
    }

    fun put(endpoint: AgentEndpoint, version: AgentVersion) {
        entries[key(endpoint)] = Entry(version, clock())
    }

    private fun key(e: AgentEndpoint) = e.baseUrlString + "|" + e.pathPrefix

    companion object {
        const val TTL_MS = 5 * 60_000L
    }
}

/**
 * Resolves where a node is reachable right now: the address it was last
 * discovered at on the LAN, else, when the phone radio hears it and holds
 * the uplink, the radio's relay request lane, else the address stored at
 * pairing time, else its mDNS host name. A scoped link-local IPv6 address
 * (`fe80::…%wlan0`) is never used: it does not survive a network change.
 */
@Singleton
class AgentEndpoints @Inject constructor(
    private val vault: CredentialVault,
    private val discovery: LanDiscovery,
    private val radio: Optional<PhoneRadioUplink> = Optional.empty(),
) {
    /** The last reason logged per node for a null endpoint, so a poll loop logs each change once. */
    private val nullReasons = ConcurrentHashMap<DeviceId, String>()

    /** The endpoint for [node], or null when it is neither paired nor reached over the phone radio. */
    suspend fun endpointFor(node: DeviceId): AgentEndpoint? = withContext(Dispatchers.IO) {
        val discovered = discovery.agents.value.firstOrNull { it.deviceId == node.value }
        val uplink = radio.orElse(null)
        if (discovered == null && uplink != null && uplink.transmitting.value && uplink.hears(node)) {
            return@withContext AgentEndpoint.overRadio(node)
        }
        val credential = vault.node(node) ?: return@withContext none(node, "not_paired")
        val usable = discovered?.takeUnless { isLinkLocal(it.host) }
        val lastIp = credential.lastIp?.takeUnless { isLinkLocal(it) }
        val host = when {
            usable != null -> "${hostLiteral(usable.host)}:${usable.port}"
            lastIp != null -> "${hostLiteral(lastIp)}:${credential.lastPort ?: AgentEndpoint.CONTROL_PORT}"
            else -> credential.mdnsHost ?: return@withContext none(node, "no_address")
        }
        val endpoint = AgentEndpoint.forHost(host, credential.apiKey, node) ?: return@withContext none(node, "bad_host")
        nullReasons.remove(node)
        endpoint
    }

    private fun none(node: DeviceId, reason: String): AgentEndpoint? {
        if (nullReasons.put(node, reason) != reason) Log.w(TAG, "endpoint_null node=${node.value} reason=$reason")
        return null
    }

    private fun hostLiteral(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    companion object {
        private const val TAG = "AdosLink"

        /** A scoped (`%iface`) or `fe80::` link-local address: valid on one interface of one network only. */
        fun isLinkLocal(host: String): Boolean = host.contains('%') || host.startsWith("fe80", ignoreCase = true)
    }
}

/** Builds [AgentClient]s for paired nodes, manual hosts, and carried drones. */
@Singleton
class AgentClientFactory @Inject constructor(
    private val http: AgentHttp,
    private val endpoints: AgentEndpoints,
    private val versionCache: VersionCache,
) {

    fun forEndpoint(endpoint: AgentEndpoint): AgentClient = AgentClient(endpoint, http, versionCache)

    /** A client for a host the operator typed (`192.168.1.50`, `node.local:8080`, a URL). */
    fun forHost(host: String, apiKey: String? = null, deviceId: DeviceId? = null): AgentClient? =
        AgentEndpoint.forHost(host, apiKey, deviceId)?.let(::forEndpoint)

    /** A client for a paired node, or null when it is not paired or has no known address. */
    suspend fun forNode(node: DeviceId): AgentClient? = endpoints.endpointFor(node)?.let(::forEndpoint)

    /**
     * A client for [droneId] carried by the ground station behind [ground]: the
     * ground station's root and key, every path under its relay proxy, 15 s timeouts.
     */
    fun forCarried(ground: AgentClient, droneId: DeviceId): AgentClient {
        val g = ground.endpoint
        return forEndpoint(
            AgentEndpoint(
                deviceId = droneId,
                baseUrl = g.baseUrl,
                apiKey = g.apiKey,
                pathPrefix = "$RELAY_PREFIX/${AgentClient.segment(droneId.value)}",
                relayedVia = g.deviceId,
            ),
        )
    }

    suspend fun forCarried(groundId: DeviceId, droneId: DeviceId): AgentClient? =
        forNode(groundId)?.let { forCarried(it, droneId) }

    companion object {
        const val RELAY_PREFIX = "api/v1/ground-station/relay-proxy"
    }
}
