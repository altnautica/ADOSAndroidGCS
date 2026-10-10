package com.altnautica.gcs.core.cloud.link

import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.grant.GrantSource
import com.altnautica.gcs.core.cloud.mqtt.CloudMqttLink
import com.altnautica.gcs.core.cloud.mqtt.CloudTopics
import com.altnautica.gcs.core.cloud.mqtt.PublishOutcome
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MAVLink to one device through the cloud broker: frames from the vehicle on
 * `ados/{id}/mavlink/tx`, frames to it on `ados/{id}/mavlink/rx`, both QoS 0.
 *
 * A QoS 0 publish the broker refuses vanishes without an error, so before
 * anything is written the grant is proven with an empty QoS 1 publish to the
 * rx topic. A PUBACK of 135 (not authorised) means the broker has not yet
 * learned this grant: writes answer [WriteResult.NotAuthorized] and the probe
 * is repeated at most every [REPROBE_MS]. The first accepted probe is reported
 * to the grant holder as a confirmed write.
 */
class CloudMavlinkTransport(
    private val deviceId: String,
    private val link: CloudMqttLink,
    private val grants: GrantSource,
    private val clock: WallClock,
    private val scope: CoroutineScope,
) : MavlinkByteTransport {

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val probeLock = Mutex()

    @Volatile
    private var authorized = false
    private var lastProbeMs = Long.MIN_VALUE
    private var watch: Job? = null

    private val tx = CloudTopics.mavlinkTx(deviceId)
    private val rx = CloudTopics.mavlinkRx(deviceId)

    suspend fun open() {
        _state.value = TransportState.Connecting
        try {
            link.open()
            link.subscribe(tx, CloudTopics.QOS_MAVLINK) { _, payload -> _incoming.tryEmit(payload) }
        } catch (e: Exception) {
            _state.value = TransportState.Failed(e.message ?: "Cloud link unavailable")
            throw e
        }
        probe()
        _state.value = TransportState.Open
        watch = scope.launch {
            link.connected.collect { up ->
                val s = _state.value
                if (s is TransportState.Closed || s is TransportState.Failed) return@collect
                _state.value = if (up) TransportState.Open else TransportState.Connecting
            }
        }
    }

    /** Whether the broker has accepted a write under the current grant. */
    val writeAuthorized: Boolean get() = authorized

    private suspend fun probe() = probeLock.withLock {
        lastProbeMs = clock.nowMs()
        when (link.publish(rx, ByteArray(0), CloudTopics.QOS_PROBE)) {
            PublishOutcome.Sent -> {
                authorized = true
                val principal = link.principal ?: grants.state.value.grant?.principal
                if (principal != null) runCatching { grants.confirmWrite(principal) }
            }
            is PublishOutcome.Refused, PublishOutcome.Dropped -> authorized = false
            is PublishOutcome.Failed -> {
                // A transport fault proves nothing about the grant; probe again on the next write.
                authorized = false
                lastProbeMs = Long.MIN_VALUE
            }
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        val s = _state.value
        if (s is TransportState.Closed || s is TransportState.Failed) return WriteResult.Failed("Link closed")
        if (!authorized) {
            if (lastProbeMs == Long.MIN_VALUE || clock.nowMs() - lastProbeMs >= REPROBE_MS) probe()
            if (!authorized) return WriteResult.NotAuthorized
        }
        return when (val o = link.publish(rx, bytes, CloudTopics.QOS_MAVLINK)) {
            PublishOutcome.Sent -> WriteResult.Sent
            PublishOutcome.Dropped -> WriteResult.Failed("Cloud link offline")
            is PublishOutcome.Refused ->
                if (o.reasonCode == CloudTopics.REASON_NOT_AUTHORIZED) {
                    authorized = false
                    WriteResult.NotAuthorized
                } else {
                    WriteResult.Failed("Broker refused (${o.reasonCode})")
                }
            is PublishOutcome.Failed -> WriteResult.Failed(o.message)
        }
    }

    override suspend fun close() {
        if (_state.value is TransportState.Closed) return
        watch?.cancel()
        _state.value = TransportState.Closed(null)
        runCatching { link.unsubscribe(tx) }
        link.close()
    }

    companion object {
        const val REPROBE_MS = 5_000L
    }
}

/** Opens [CloudMavlinkTransport] for [Reach.CloudLive] while the operator is signed in. */
@Singleton
class CloudMavlinkTransportProvider @Inject constructor(
    private val link: CloudMqttLink,
    private val grants: GrantSource,
    private val auth: CloudAuthState,
    private val clock: WallClock,
    @param:CloudScope private val scope: CoroutineScope,
) : TransportProvider {

    override fun supports(reach: Reach): Boolean = reach == Reach.CloudLive

    override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport {
        check(auth.active.value && auth.state.value == CloudSessionState.SignedIn) { "Cloud is not signed in" }
        return CloudMavlinkTransport(node.id.value, link, grants, clock, scope).also { it.open() }
    }
}
