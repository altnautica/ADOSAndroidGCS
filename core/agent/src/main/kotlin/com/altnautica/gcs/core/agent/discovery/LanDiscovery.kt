package com.altnautica.gcs.core.agent.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Continuous DNS-SD browse of `_ados._tcp`. Every found service is resolved
 * (one at a time: older NSD stacks refuse concurrent resolves), its TXT parsed,
 * and the result deduplicated by `device_id`. Browsing runs while [browse] has
 * a collector and holds a Wi-Fi multicast lock for that time.
 */
@Singleton
class LanDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val table = DiscoveryTable()
    private val _agents = MutableStateFlow<List<DiscoveredAgent>>(emptyList())

    /** The latest browse result; empty when nothing is browsing. */
    val agents: StateFlow<List<DiscoveredAgent>> = _agents.asStateFlow()

    private val shared: SharedFlow<List<DiscoveredAgent>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            Log.w(TAG, "nsd_unavailable")
            send(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val lock = acquireMulticastLock()
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        fun publish(list: List<DiscoveredAgent>) {
            _agents.value = list
            trySend(list)
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                found.trySend(service)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                publish(table.remove(service.serviceName))
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start_discovery_failed code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        val resolver = launch {
            for (service in found) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(nsd, service) } ?: continue
                val host = resolved.host?.hostAddress ?: continue
                val txt = AgentTxt.parse(resolved.attributes ?: emptyMap())
                publish(
                    table.upsert(
                        AgentTxt.toAgent(resolved.serviceName, host, resolved.port, txt, SystemClock.elapsedRealtime()),
                    ),
                )
            }
        }

        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discover_threw ${it.message}") }

        awaitClose {
            resolver.cancel()
            found.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { if (lock?.isHeld == true) lock.release() }
            table.clear()
            _agents.value = emptyList()
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), replay = 1)

    /** Discovered agents, updated as services appear and disappear. */
    fun browse(): Flow<List<DiscoveredAgent>> = shared

    /** The discovered unpaired node advertising [code], if any. */
    fun findByCode(code: String): DiscoveredAgent? {
        val wanted = PairingCode.normalize(code)
        return _agents.value.firstOrNull { it.code == wanted }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo)
                }
            }
            try {
                nsd.resolveService(service, listener)
            } catch (t: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (t: SecurityException) {
        Log.w(TAG, "multicast_lock_failed ${t.message}")
        null
    }

    private companion object {
        const val TAG = "LanDiscovery"
        const val SERVICE_TYPE = "_ados._tcp."
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val STOP_GRACE_MS = 5_000L
        const val MULTICAST_LOCK_TAG = "ados-lan-discovery"
    }
}
