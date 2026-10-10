package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.mqtt.PublishOutcome
import com.altnautica.gcs.core.cloud.video.P2pSignaling
import com.altnautica.gcs.core.cloud.video.P2pSignalingException
import com.altnautica.gcs.core.cloud.video.P2pTimeouts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2pSignalingTest {

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    /** A broker whose agent answers every offer with [answer]. */
    private fun answering(answer: String) = FakeBroker().apply {
        onPublish = { port, p ->
            if (p.topic == "ados/d1/webrtc/offer") {
                val obj = json(p.payload)
                if (obj["sdp"] != null) {
                    val sid = obj["sessionId"]!!.jsonPrimitive.content
                    port.deliver("ados/d1/webrtc/answer/$sid", answer.toByteArray())
                }
            }
        }
    }

    @Test
    fun offerEnvelopeAnswerAndRelease() = runTest {
        val broker = answering("v=0 answer")
        val session = P2pSignaling(broker).exchange("wss://broker.invalid/mqtt", "d1", "gcs-op-1", "secret", "v=0 offer")

        val port = broker.ports.single()
        assertEquals("gcs-op-1", port.username)
        assertEquals(P2pTimeouts.KEEPALIVE_S, port.keepAlive)
        assertTrue(session.sessionId.matches(Regex("[0-9a-f]{32}")))
        assertEquals(1, port.subscriptions["ados/d1/webrtc/answer/${session.sessionId}"]?.first)

        val offer = port.published.single()
        assertEquals("ados/d1/webrtc/offer", offer.topic)
        assertEquals(1, offer.qos)
        val env = json(offer.payload)
        assertEquals(session.sessionId, env["sessionId"]!!.jsonPrimitive.content)
        assertEquals("v=0 offer", env["sdp"]!!.jsonPrimitive.content)
        assertEquals("v=0 answer", session.answerSdp)

        val will = port.will!!
        assertEquals("ados/d1/webrtc/offer", will.topic)
        assertEquals(1, will.qos)
        assertTrue(json(will.payload)["close"]!!.jsonPrimitive.boolean)

        session.release()
        val close = port.published.last()
        assertEquals(session.sessionId, json(close.payload)["sessionId"]!!.jsonPrimitive.content)
        assertTrue(json(close.payload)["close"]!!.jsonPrimitive.boolean)
        assertTrue(port.disconnected)
    }

    @Test
    fun jsonErrorWithStatus404MeansNoPipeline() = runTest {
        val broker = answering("""{"error":"no stream","status":404}""")
        val e = runCatching {
            P2pSignaling(broker).exchange("wss://broker.invalid/mqtt", "d1", "u", "p", "offer")
        }.exceptionOrNull() as P2pSignalingException
        assertTrue(e.noPipeline)
        // The failed session still tells the agent to tear down and disconnects.
        val port = broker.ports.single()
        assertTrue(port.disconnected)
        assertTrue(json(port.published.last().payload)["close"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun otherJsonErrorIsReported() {
        val e = runCatching { P2pSignaling.parseAnswer("""{"error":"whep failed","status":502}""") }.exceptionOrNull()
        assertTrue(e is P2pSignalingException && !e.noPipeline && e.message!!.contains("502"))
        assertEquals("""{"sdp_like":true}""", P2pSignaling.parseAnswer("""{"sdp_like":true}"""))
    }

    @Test
    fun refusedOfferFails() = runTest {
        val broker = FakeBroker().apply { publishOutcome = { PublishOutcome.Refused(135) } }
        val e = runCatching { P2pSignaling(broker).exchange("wss://b.invalid/mqtt", "d1", "u", "p", "o") }.exceptionOrNull()
        assertTrue(e is P2pSignalingException)
    }

    @Test
    fun noAnswerTimesOutAfterTwelveSeconds() = runTest {
        val broker = FakeBroker()
        val result = async { runCatching { P2pSignaling(broker).exchange("wss://b.invalid/mqtt", "d1", "u", "p", "o") } }
        runCurrent()
        advanceTimeBy(P2pTimeouts.ANSWER_MS - 1)
        runCurrent()
        assertTrue(result.isActive)
        advanceTimeBy(2)
        assertTrue(result.await().exceptionOrNull() is P2pSignalingException)
    }
}
