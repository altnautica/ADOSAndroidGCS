package com.altnautica.gcs.core.cloud.video

import com.altnautica.gcs.core.cloud.mqtt.CloudTopics
import com.altnautica.gcs.core.cloud.mqtt.MqttPort
import com.altnautica.gcs.core.cloud.mqtt.MqttPortFactory
import com.altnautica.gcs.core.cloud.mqtt.PublishOutcome
import com.altnautica.gcs.core.cloud.mqtt.WillMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

object P2pTimeouts {
    const val ICE_GATHER_MS = 8_000L
    const val CONNECT_MS = 8_000L
    const val ANSWER_MS = 12_000L
    const val FIRST_FRAME_MS = 8_000L
    const val KEEPALIVE_S = 15
}

/** Why a signalling exchange failed. [noPipeline] marks the agent's "no video pipeline" answer. */
class P2pSignalingException(message: String, val noPipeline: Boolean = false) : Exception(message)

@Serializable
internal data class OfferEnvelope(val sessionId: String, val sdp: String)

@Serializable
internal data class CloseEnvelope(val sessionId: String, val close: Boolean = true)

@Serializable
internal data class AnswerError(val error: String? = null, val status: Int? = null)

/** An answered exchange. [release] tells the agent the session is over and drops the connection. */
class SignalingSession(val sessionId: String, val answerSdp: String, private val port: MqttPort, private val offerTopic: String) {
    suspend fun release() = withContext(NonCancellable) {
        runCatching {
            port.publish(offerTopic, P2pSignaling.closeMessage(sessionId), CloudTopics.QOS_SIGNALING)
        }
        port.disconnect()
    }
}

/**
 * The SDP exchange for a cloud peer-to-peer stream, over its own short-lived
 * broker connection authenticated with the operator's grant:
 *
 * 1. connect with a last will of `{sessionId, close:true}` on the offer topic,
 *    so the agent tears its leg down if this connection dies;
 * 2. subscribe `ados/{id}/webrtc/answer/{sessionId}` (QoS 1);
 * 3. publish `{sessionId, sdp}` on `ados/{id}/webrtc/offer` (QoS 1);
 * 4. the answer payload is the raw SDP, or a JSON `{error, status}` when the
 *    agent could not answer (status 404 = no video pipeline).
 */
class P2pSignaling(
    private val factory: MqttPortFactory,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun exchange(
        brokerUrl: String,
        deviceId: String,
        username: String,
        password: String,
        offerSdp: String,
    ): SignalingSession {
        val sessionId = newSessionId(random)
        val offerTopic = CloudTopics.webrtcOffer(deviceId)
        val answerTopic = CloudTopics.webrtcAnswer(deviceId, sessionId)
        val port = factory.create(
            brokerUrl,
            CloudTopics.clientId(random),
            WillMessage(offerTopic, closeMessage(sessionId), CloudTopics.QOS_SIGNALING),
        )
        try {
            try {
                withTimeout(P2pTimeouts.CONNECT_MS) { port.connect(username, password, P2pTimeouts.KEEPALIVE_S) }
            } catch (e: TimeoutCancellationException) {
                throw P2pSignalingException("Message broker did not answer within ${P2pTimeouts.CONNECT_MS / 1000} s")
            }
            val answer = CompletableDeferred<String>()
            port.subscribe(answerTopic, CloudTopics.QOS_SIGNALING) { topic, payload ->
                if (topic == answerTopic) answer.complete(payload.toString(Charsets.UTF_8))
            }
            val offer = JSON.encodeToString(OfferEnvelope.serializer(), OfferEnvelope(sessionId, offerSdp)).toByteArray()
            when (val o = port.publish(offerTopic, offer, CloudTopics.QOS_SIGNALING)) {
                PublishOutcome.Sent -> Unit
                is PublishOutcome.Refused -> throw P2pSignalingException("Broker refused the video offer (${o.reasonCode})")
                is PublishOutcome.Failed -> throw P2pSignalingException("Video offer not delivered: ${o.message}")
                PublishOutcome.Dropped -> throw P2pSignalingException("Video offer not delivered: broker offline")
            }
            val raw = try {
                withTimeout(P2pTimeouts.ANSWER_MS) { answer.await() }
            } catch (e: TimeoutCancellationException) {
                throw P2pSignalingException("No video answer within ${P2pTimeouts.ANSWER_MS / 1000} s")
            }
            return SignalingSession(sessionId, parseAnswer(raw), port, offerTopic)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { port.publish(offerTopic, closeMessage(sessionId), CloudTopics.QOS_SIGNALING) }
                port.disconnect()
            }
            throw e
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 32 lowercase hex characters. */
        fun newSessionId(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun closeMessage(sessionId: String): ByteArray =
            JSON.encodeToString(CloseEnvelope.serializer(), CloseEnvelope(sessionId)).toByteArray()

        /** The answer SDP, or a [P2pSignalingException] for the agent's JSON error payload. */
        fun parseAnswer(raw: String): String {
            if (raw.startsWith("{")) {
                val err = runCatching { JSON.decodeFromString(AnswerError.serializer(), raw) }.getOrNull()
                if (err?.error != null) {
                    val status = err.status ?: 0
                    if (status == 404) throw P2pSignalingException("The vehicle has no video pipeline running", noPipeline = true)
                    throw P2pSignalingException("Vehicle video relay error: ${err.error} (status $status)")
                }
            }
            return raw
        }
    }
}
