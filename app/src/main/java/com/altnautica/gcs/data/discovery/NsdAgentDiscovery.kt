package com.altnautica.gcs.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * mDNS / DNS-SD lookup for the agent's REST/WS endpoint.
 *
 * The agent advertises `_ados._tcp` on its AP interface. This class
 * runs an NSD discovery + resolve cycle with a short timeout and
 * exposes the most recently resolved endpoint as a StateFlow.
 *
 * If discovery times out or fails, the flow stays at its previous
 * value (or null on first failure) and callers fall back to the
 * hardcoded AP address.
 *
 * NSD is supported from API 16 onwards; the app's minSdk is 29 so
 * no version gate is required for the public API surface. A
 * MulticastLock is acquired during the discovery window because some
 * devices and APs filter multicast traffic when the lock is not held.
 */
@Singleton
class NsdAgentDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "NsdAgentDiscovery"
        private const val SERVICE_TYPE = "_ados._tcp."
        private const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val MULTICAST_LOCK_TAG = "ados-nsd-lock"
    }

    data class AgentEndpoint(
        val host: String,
        val port: Int,
        val profile: String?,
        val version: String?,
        val deviceId: String?,
        val path: String?,
    ) {
        fun baseUrl(): String = "http://$host:$port/"
    }

    private val _lastResolved = MutableStateFlow<AgentEndpoint?>(null)
    val lastResolved: StateFlow<AgentEndpoint?> = _lastResolved.asStateFlow()

    /**
     * Run a discovery cycle with the given timeout. Returns the first
     * resolved endpoint that decodes cleanly, or null on timeout. The
     * StateFlow is updated on success.
     *
     * Safe to call from a coroutine. Uses Android NsdManager which
     * dispatches its callbacks on a binder thread; this method
     * synchronizes via CompletableDeferred so the caller sees a
     * single result.
     */
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): AgentEndpoint? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            Log.w(TAG, "NSD service unavailable on this device")
            return null
        }

        val multicastLock = acquireMulticastLock()
        val deferred = CompletableDeferred<AgentEndpoint?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "discovery_started type=$serviceType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (deferred.isCompleted) return
                Log.d(TAG, "service_found name=${service.serviceName}")
                resolve(nsdManager, service, deferred)
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "service_lost name=${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "discovery_stopped type=$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start_discovery_failed code=$errorCode")
                deferred.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop_discovery_failed code=$errorCode")
            }
        }

        return try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
            val resolved = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (resolved != null) {
                _lastResolved.value = resolved
            }
            resolved
        } catch (t: Throwable) {
            Log.w(TAG, "discovery_threw ${t.message}")
            null
        } finally {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Throwable) {
                // Listener already torn down by the framework on resolve.
            }
            multicastLock?.let {
                if (it.isHeld) {
                    try {
                        it.release()
                    } catch (_: Throwable) {
                        // Lock already released by the framework.
                    }
                }
            }
        }
    }

    private fun resolve(
        nsdManager: NsdManager,
        service: NsdServiceInfo,
        deferred: CompletableDeferred<AgentEndpoint?>,
    ) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve_failed code=$errorCode")
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (deferred.isCompleted) return
                val host = serviceInfo.host?.hostAddress
                if (host.isNullOrBlank()) {
                    Log.w(TAG, "resolve_no_host")
                    return
                }
                val port = serviceInfo.port
                val txt = readAttributes(serviceInfo)
                val endpoint = AgentEndpoint(
                    host = host,
                    port = port,
                    profile = txt["profile"],
                    version = txt["version"],
                    deviceId = txt["device_id"],
                    path = txt["path"],
                )
                Log.d(TAG, "resolved host=$host port=$port profile=${endpoint.profile}")
                deferred.complete(endpoint)
            }
        }
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(service, resolveListener)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve_threw ${t.message}")
        }
    }

    private fun readAttributes(serviceInfo: NsdServiceInfo): Map<String, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return emptyMap()
        val raw = serviceInfo.attributes ?: return emptyMap()
        val out = mutableMapOf<String, String>()
        for ((k, v) in raw) {
            if (v == null) continue
            out[k] = String(v, Charsets.UTF_8)
        }
        return out
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            lock
        } catch (t: Throwable) {
            Log.w(TAG, "multicast_lock_failed ${t.message}")
            null
        }
    }
}
