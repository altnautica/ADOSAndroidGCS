package com.altnautica.gcs.core.cloud.mqtt

import com.altnautica.gcs.core.cloud.grant.GrantSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** No broker grant is held, so nothing may be published. */
class NoGrantException : IllegalStateException("No cloud control grant is held")

/**
 * The shared broker connection for MAVLink over the cloud, authenticated with
 * the held grant (username = principal, password = secret).
 *
 * Reference counted: the first [open] connects and the last [close]
 * disconnects. Subscriptions are owned here, so when a renewed grant brings a
 * new principal the connection is rebuilt and every topic re-subscribed. A
 * rebuild that fails to connect is retried with backoff
 * ([RETRY_INITIAL_MS] doubling to [RETRY_MAX_MS]) while the link has users.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudMqttLink(
    private val factory: MqttPortFactory,
    private val brokerUrl: suspend () -> String,
    private val grants: GrantSource,
    private val scope: CoroutineScope,
    private val clientId: () -> String = { CloudTopics.clientId() },
) {
    private class Sub(val qos: Int, val listener: (String, ByteArray) -> Unit)

    private val lock = Mutex()
    private var port: MqttPort? = null
    private var users = 0
    private var watcher: Job? = null
    private var retry: Job? = null
    private val subs = LinkedHashMap<String, Sub>()
    private val portFlow = MutableStateFlow<MqttPort?>(null)

    /** The principal the current connection authenticates as. */
    @Volatile
    var principal: String? = null
        private set

    /** True while the broker connection is up. */
    val connected: StateFlow<Boolean> = portFlow
        .flatMapLatest { it?.connected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun open() = lock.withLock {
        users++
        try {
            ensureConnected()
        } catch (e: Exception) {
            users--
            throw e
        }
        if (watcher == null) {
            watcher = scope.launch {
                grants.state.map { it.grant?.principal }.distinctUntilChanged().drop(1).collect { p ->
                    lock.withLock { if (users > 0 && p != null && p != principal) reconnect() }
                }
            }
        }
    }

    suspend fun close() = lock.withLock {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            watcher?.cancel()
            watcher = null
            retry?.cancel()
            retry = null
            subs.clear()
            port?.disconnect()
            port = null
            portFlow.value = null
            principal = null
        }
    }

    suspend fun subscribe(topic: String, qos: Int, listener: (String, ByteArray) -> Unit) = lock.withLock {
        subs[topic] = Sub(qos, listener)
        port?.subscribe(topic, qos, listener)
    }

    suspend fun unsubscribe(topic: String) = lock.withLock {
        subs.remove(topic)
        port?.unsubscribe(topic)
    }

    suspend fun publish(topic: String, payload: ByteArray, qos: Int): PublishOutcome {
        val p = lock.withLock { port }
            ?: return if (qos == 0) PublishOutcome.Dropped else PublishOutcome.Failed("Not connected")
        return p.publish(topic, payload, qos)
    }

    private suspend fun ensureConnected() {
        if (port != null) return
        val grant = grants.state.value.grant ?: throw NoGrantException()
        val p = factory.create(brokerUrl(), clientId(), null)
        try {
            p.connect(grant.principal, grant.secret)
        } catch (e: Exception) {
            p.disconnect()
            throw e
        }
        port = p
        // Principal before the port is published: observers of [connected] read it.
        principal = grant.principal
        portFlow.value = p
        for ((topic, sub) in subs) p.subscribe(topic, sub.qos, sub.listener)
    }

    /** Rebuilds the connection for a new principal; a failed connect is retried with backoff. */
    private suspend fun reconnect() {
        retry?.cancel()
        retry = null
        port?.disconnect()
        port = null
        portFlow.value = null
        if (runCatching { ensureConnected() }.isFailure) scheduleRetry()
    }

    /** Called under [lock]. Keeps dialling while anyone uses the link and it is down. */
    private fun scheduleRetry() {
        if (retry?.isActive == true) return
        retry = scope.launch {
            var backoff = RETRY_INITIAL_MS
            while (true) {
                delay(backoff)
                val done = lock.withLock {
                    if (users == 0 || port != null) return@withLock true
                    try {
                        ensureConnected()
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }
                if (done) break
                backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    companion object {
        const val RETRY_INITIAL_MS = 1_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
