package com.altnautica.gcs.core.agent.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import com.altnautica.gcs.core.agent.http.AgentHttp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wait up to [timeoutMs] for this flow to emit; true when it did. A flow that completes
 * without emitting (an empty kick source) waits out the full time instead of returning early.
 */
internal suspend fun Flow<Unit>.awaitWake(timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { merge(this@awaitWake, flow<Unit> { awaitCancellation() }).first() } != null

/**
 * Watches the phone's default network. A change (a new network, new addresses on it,
 * or its loss) drops idle pooled connections and emits on [kicks], so status polls and
 * MAVLink sockets redial at once instead of waiting out a timeout on a dead route.
 */
@Singleton
class LanNetworkWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: AgentHttp,
) {
    private val started = AtomicBoolean(false)
    private val _network = MutableStateFlow<Long?>(null)
    private val _kicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** `Network.networkHandle` of the current default network; null when there is none. */
    val network: StateFlow<Long?> = _network.asStateFlow()

    /** One emission per network change; nothing is replayed to a late collector. */
    val kicks: SharedFlow<Unit> = _kicks.asSharedFlow()

    /** Register the network callback; later calls do nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            Log.w(TAG, "net_watch_unavailable")
            return
        }
        runCatching { cm.registerDefaultNetworkCallback(Callback()) }
            .onFailure { Log.w(TAG, "net_watch_failed ${it.message}") }
    }

    private fun kick(reason: String, network: Network) {
        Log.i(TAG, "net_change reason=$reason net=${network.networkHandle}")
        http.evictPools()
        _kicks.tryEmit(Unit)
    }

    /** Callbacks arrive one at a time on the connectivity thread, so [addresses] needs no lock. */
    private inner class Callback : ConnectivityManager.NetworkCallback() {
        private var addresses: List<LinkAddress>? = null

        override fun onAvailable(network: Network) {
            addresses = null
            _network.value = network.networkHandle
            kick("available", network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val now = linkProperties.linkAddresses
            val before = addresses
            addresses = now
            // The first report follows onAvailable, which has already kicked.
            if (before != null && before.toSet() != now.toSet()) kick("addresses", network)
        }

        override fun onLost(network: Network) {
            addresses = null
            if (_network.value == network.networkHandle) _network.value = null
            kick("lost", network)
        }
    }

    private companion object {
        const val TAG = "AdosLink"
    }
}
