package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.cloud.mqtt.MqttPort
import com.altnautica.gcs.core.cloud.mqtt.MqttPortFactory
import com.altnautica.gcs.core.cloud.mqtt.PublishOutcome
import com.altnautica.gcs.core.cloud.mqtt.WillMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

class FakeAuth(signedIn: Boolean = true) : CloudAuthState {
    override val active = MutableStateFlow(true)
    override val state = MutableStateFlow<CloudSessionState>(if (signedIn) CloudSessionState.SignedIn else CloudSessionState.SignedOut)
}

data class GatewayCall(val kind: String, val name: String, val args: Map<String, Any?>)

class FakeGateway : ConvexGateway {
    val calls = mutableListOf<GatewayCall>()
    var onAction: suspend (String, Map<String, Any?>) -> JsonElement = { _, _ -> JsonNull }
    var onMutation: suspend (String, Map<String, Any?>) -> JsonElement = { _, _ -> JsonNull }
    val subscriptions = mutableMapOf<String, Flow<Result<JsonElement>>>()

    override fun subscribe(name: String, args: Map<String, Any?>): Flow<Result<JsonElement>> {
        calls += GatewayCall("subscribe", name, args)
        return subscriptions[name] ?: flowOf()
    }

    override suspend fun action(name: String, args: Map<String, Any?>): JsonElement {
        calls += GatewayCall("action", name, args)
        return onAction(name, args)
    }

    override suspend fun mutation(name: String, args: Map<String, Any?>): JsonElement {
        calls += GatewayCall("mutation", name, args)
        return onMutation(name, args)
    }
}

data class Published(val topic: String, val payload: ByteArray, val qos: Int)

/** An in-memory broker: records connects, subscriptions and publishes; publish outcomes are scripted. */
class FakeBroker : MqttPortFactory {
    val ports = mutableListOf<FakePort>()
    var publishOutcome: (Published) -> PublishOutcome = { PublishOutcome.Sent }

    /** Called after each publish, e.g. to answer an offer. */
    var onPublish: (FakePort, Published) -> Unit = { _, _ -> }

    override fun create(brokerUrl: String, clientId: String, will: WillMessage?): MqttPort =
        FakePort(this, brokerUrl, clientId, will).also { ports += it }
}

class FakePort(
    private val broker: FakeBroker,
    val brokerUrl: String,
    val clientId: String,
    val will: WillMessage?,
) : MqttPort {
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()
    var username: String? = null
    var password: String? = null
    var keepAlive: Int? = null
    val subscriptions = mutableMapOf<String, Pair<Int, (String, ByteArray) -> Unit>>()
    val published = mutableListOf<Published>()
    var disconnected = false

    override suspend fun connect(username: String, password: String, keepAliveS: Int) {
        this.username = username
        this.password = password
        keepAlive = keepAliveS
        _connected.value = true
    }

    override suspend fun subscribe(topic: String, qos: Int, listener: (String, ByteArray) -> Unit) {
        subscriptions[topic] = qos to listener
    }

    override suspend fun unsubscribe(topic: String) {
        subscriptions.remove(topic)
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int): PublishOutcome {
        val p = Published(topic, payload, qos)
        published += p
        val outcome = broker.publishOutcome(p)
        broker.onPublish(this, p)
        return outcome
    }

    override suspend fun disconnect() {
        disconnected = true
        _connected.value = false
    }

    fun deliver(topic: String, payload: ByteArray) {
        subscriptions[topic]?.second?.invoke(topic, payload)
    }
}
