package com.altnautica.gcs.core.cloud.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5PubAckException
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5PublishResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** HiveMQ MQTT 5 over (secure) WebSocket, clean start, automatic reconnect with resubscribe. */
class HiveMqPort(brokerUrl: String, clientId: String, private val will: WillMessage?) : MqttPort {

    private val address = BrokerAddress.parse(brokerUrl)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val client: Mqtt5AsyncClient = MqttClient.builder()
        .useMqttVersion5()
        .identifier(clientId)
        .serverHost(address.host)
        .serverPort(address.port)
        .let { if (address.tls) it.sslWithDefaultConfig() else it }
        .webSocketConfig()
        .serverPath(address.path)
        .subprotocol("mqtt")
        .applyWebSocketConfig()
        .automaticReconnect()
        .initialDelay(1, TimeUnit.SECONDS)
        .maxDelay(10, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .addConnectedListener { _connected.value = true }
        .addDisconnectedListener { ctx ->
            _connected.value = false
            ctx.reconnector.resubscribeIfSessionExpired(true).republishIfSessionExpired(false)
        }
        .buildAsync()

    override suspend fun connect(username: String, password: String, keepAliveS: Int) {
        val builder = client.connectWith()
            .cleanStart(true)
            .keepAlive(keepAliveS)
            .simpleAuth()
            .username(username)
            .password(password.toByteArray(Charsets.UTF_8))
            .applySimpleAuth()
        val connect = will?.let {
            builder.willPublish()
                .topic(it.topic)
                .qos(qosOf(it.qos))
                .payload(it.payload)
                .applyWillPublish()
        } ?: builder
        connect.send().await()
    }

    override suspend fun subscribe(topic: String, qos: Int, listener: (topic: String, payload: ByteArray) -> Unit) {
        client.subscribeWith()
            .topicFilter(topic)
            .qos(qosOf(qos))
            .callback { listener(it.topic.toString(), it.payloadAsBytes) }
            .send()
            .await()
    }

    override suspend fun unsubscribe(topic: String) {
        runCatching { client.unsubscribeWith().topicFilter(topic).send().await() }
    }

    override suspend fun publish(topic: String, payload: ByteArray, qos: Int): PublishOutcome {
        if (qos == 0 && !_connected.value) return PublishOutcome.Dropped
        return try {
            val result = client.publishWith().topic(topic).qos(qosOf(qos)).payload(payload).send().await()
            outcomeOf(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failureOf(e)
        }
    }

    override suspend fun disconnect() {
        _connected.value = false
        runCatching { client.disconnect().await() }
    }

    private fun outcomeOf(result: Mqtt5PublishResult): PublishOutcome {
        result.error.orElse(null)?.let { return failureOf(it) }
        if (result is Mqtt5PublishResult.Mqtt5Qos1Result) {
            val code = result.pubAck.reasonCode
            if (code.isError) return PublishOutcome.Refused(code.code)
        }
        return PublishOutcome.Sent
    }

    private fun failureOf(e: Throwable): PublishOutcome {
        val cause = if (e is CompletionException) e.cause ?: e else e
        return if (cause is Mqtt5PubAckException) {
            PublishOutcome.Refused(cause.mqttMessage.reasonCode.code)
        } else {
            PublishOutcome.Failed(cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun qosOf(q: Int) = when (q) {
        0 -> MqttQos.AT_MOST_ONCE
        1 -> MqttQos.AT_LEAST_ONCE
        else -> MqttQos.EXACTLY_ONCE
    }
}

class HiveMqPortFactory @Inject constructor() : MqttPortFactory {
    override fun create(brokerUrl: String, clientId: String, will: WillMessage?): MqttPort =
        HiveMqPort(brokerUrl, clientId, will)
}
