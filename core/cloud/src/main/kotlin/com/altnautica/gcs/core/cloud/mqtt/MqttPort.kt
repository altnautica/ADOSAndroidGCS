package com.altnautica.gcs.core.cloud.mqtt

import kotlinx.coroutines.flow.StateFlow

/** Outcome of one publish. */
sealed interface PublishOutcome {
    /** QoS 0 handed to the socket, or QoS 1 acknowledged with success. */
    data object Sent : PublishOutcome

    /** QoS 0 while disconnected: dropped, never queued for later. */
    data object Dropped : PublishOutcome

    /** The broker refused the publish with an MQTT 5 reason code (135 = not authorised). */
    data class Refused(val reasonCode: Int) : PublishOutcome

    data class Failed(val message: String) : PublishOutcome
}

/** A last-will message the broker publishes if the connection drops. */
class WillMessage(val topic: String, val payload: ByteArray, val qos: Int)

/** One MQTT 5 connection. The real one is HiveMQ over secure WebSocket; tests use a fake broker. */
interface MqttPort {
    val connected: StateFlow<Boolean>

    suspend fun connect(username: String, password: String, keepAliveS: Int = 30)

    suspend fun subscribe(topic: String, qos: Int, listener: (topic: String, payload: ByteArray) -> Unit)

    suspend fun unsubscribe(topic: String)

    suspend fun publish(topic: String, payload: ByteArray, qos: Int): PublishOutcome

    suspend fun disconnect()
}

fun interface MqttPortFactory {
    fun create(brokerUrl: String, clientId: String, will: WillMessage?): MqttPort
}

/** Host, port, path and TLS parsed from a `ws(s)://host[:port]/path` broker URL. */
data class BrokerAddress(val host: String, val port: Int, val path: String, val tls: Boolean) {
    companion object {
        fun parse(url: String): BrokerAddress {
            val uri = java.net.URI(url.trim())
            val tls = when (uri.scheme?.lowercase()) {
                "wss" -> true
                "ws" -> false
                else -> throw IllegalArgumentException("Broker URL must be ws:// or wss://")
            }
            val host = requireNotNull(uri.host) { "Broker URL has no host" }
            val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
            val path = uri.rawPath.orEmpty().trimStart('/')
            return BrokerAddress(host, port, path, tls)
        }
    }
}

/** Topic names and QoS levels shared with the agent's cloud bridge. */
object CloudTopics {
    const val QOS_MAVLINK = 0
    const val QOS_SIGNALING = 1
    const val QOS_PROBE = 1
    const val REASON_NOT_AUTHORIZED = 135

    /** Frames from the vehicle. */
    fun mavlinkTx(deviceId: String) = "ados/$deviceId/mavlink/tx"

    /** Frames to the vehicle. */
    fun mavlinkRx(deviceId: String) = "ados/$deviceId/mavlink/rx"

    fun webrtcOffer(deviceId: String) = "ados/$deviceId/webrtc/offer"

    fun webrtcAnswer(deviceId: String, sessionId: String) = "ados/$deviceId/webrtc/answer/$sessionId"

    /** `gcs-and-` plus 12 random hex characters. */
    fun clientId(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        return "gcs-and-" + bytes.joinToString("") { "%02x".format(it) }
    }
}
