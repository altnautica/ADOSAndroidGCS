package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.grant.GrantSource
import com.altnautica.gcs.core.cloud.grant.GrantState
import com.altnautica.gcs.core.cloud.grant.HeldGrant
import com.altnautica.gcs.core.cloud.link.CloudMavlinkTransport
import com.altnautica.gcs.core.cloud.mqtt.BrokerAddress
import com.altnautica.gcs.core.cloud.mqtt.CloudMqttLink
import com.altnautica.gcs.core.cloud.mqtt.CloudTopics
import com.altnautica.gcs.core.cloud.mqtt.PublishOutcome
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMavlinkTransportTest {

    private class Grants : GrantSource {
        override val state = MutableStateFlow(GrantState(grant = HeldGrant("gcs-op-1", "secret", listOf("d1"), Long.MAX_VALUE, false)))
        val confirmed = mutableListOf<String>()
        override suspend fun confirmWrite(principal: String) {
            confirmed += principal
            state.value = state.value.copy(grant = state.value.grant!!.copy(writeConfirmed = true))
        }
    }

    @Test
    fun topicsAndQualityOfService() {
        assertEquals("ados/d1/mavlink/tx", CloudTopics.mavlinkTx("d1"))
        assertEquals("ados/d1/mavlink/rx", CloudTopics.mavlinkRx("d1"))
        assertEquals("ados/d1/webrtc/offer", CloudTopics.webrtcOffer("d1"))
        assertEquals("ados/d1/webrtc/answer/abc", CloudTopics.webrtcAnswer("d1", "abc"))
        assertEquals(0, CloudTopics.QOS_MAVLINK)
        assertEquals(1, CloudTopics.QOS_SIGNALING)
        assertTrue(CloudTopics.clientId().matches(Regex("gcs-and-[0-9a-f]{12}")))
        assertEquals(BrokerAddress("mqtt.altnautica.com", 443, "mqtt", true), BrokerAddress.parse("wss://mqtt.altnautica.com/mqtt"))
        assertEquals(BrokerAddress("10.0.0.2", 9001, "", false), BrokerAddress.parse("ws://10.0.0.2:9001"))
    }

    @Test
    fun notAuthorizedUntilTheBrokerLearnsTheGrantThenConfirms() = runTest {
        val broker = FakeBroker()
        var refuse = true
        broker.publishOutcome = { p ->
            if (p.qos == 1 && refuse) PublishOutcome.Refused(CloudTopics.REASON_NOT_AUTHORIZED) else PublishOutcome.Sent
        }
        val grants = Grants()
        val link = CloudMqttLink(broker, { "wss://broker.invalid/mqtt" }, grants, backgroundScope, { "gcs-and-test" })
        val transport = CloudMavlinkTransport("d1", link, grants, WallClock { testScheduler.currentTime }, backgroundScope)

        transport.open()
        val port = broker.ports.single()
        assertEquals("gcs-op-1", port.username)
        assertEquals("secret", port.password)
        assertEquals(0, port.subscriptions["ados/d1/mavlink/tx"]?.first)
        val probe = port.published.single()
        assertEquals("ados/d1/mavlink/rx", probe.topic)
        assertEquals(1, probe.qos)
        assertEquals(0, probe.payload.size)
        assertEquals(TransportState.Open, transport.state.value)

        // Refused probe: writes report NotAuthorized and nothing reaches the rx topic at QoS 0.
        assertEquals(WriteResult.NotAuthorized, transport.write(byteArrayOf(1, 2)))
        assertTrue(port.published.none { it.qos == 0 })
        assertTrue(grants.confirmed.isEmpty())

        // The broker learns the grant; after the re-probe interval the probe passes and the write goes out.
        refuse = false
        advanceTimeBy(CloudMavlinkTransport.REPROBE_MS)
        assertEquals(WriteResult.Sent, transport.write(byteArrayOf(1, 2)))
        assertEquals(listOf("gcs-op-1"), grants.confirmed)
        val frame = port.published.last()
        assertEquals("ados/d1/mavlink/rx", frame.topic)
        assertEquals(0, frame.qos)
        assertArrayEquals(byteArrayOf(1, 2), frame.payload)

        // Frames from the vehicle arrive on incoming.
        val got = async { transport.incoming.first() }
        runCurrent()
        port.deliver("ados/d1/mavlink/tx", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), got.await())

        transport.close()
        assertTrue(port.disconnected)
        assertTrue(transport.state.value is TransportState.Closed)
    }

    @Test
    fun qosZeroWhileOfflineIsDroppedNotQueued() = runTest {
        val broker = FakeBroker()
        val grants = Grants()
        val link = CloudMqttLink(broker, { "wss://broker.invalid/mqtt" }, grants, backgroundScope)
        assertEquals(PublishOutcome.Dropped, link.publish("ados/d1/mavlink/rx", byteArrayOf(1), 0))
        assertTrue(broker.ports.isEmpty())
    }

    @Test
    fun renewedGrantReconnectsAndResubscribes() = runTest {
        val broker = FakeBroker()
        val grants = Grants()
        val link = CloudMqttLink(broker, { "wss://broker.invalid/mqtt" }, grants, backgroundScope)
        link.open()
        link.subscribe("ados/d1/mavlink/tx", 0) { _, _ -> }
        runCurrent()
        grants.state.value = GrantState(grant = HeldGrant("gcs-op-2", "s2", listOf("d1"), Long.MAX_VALUE, false))
        runCurrent()
        assertEquals(2, broker.ports.size)
        assertTrue(broker.ports[0].disconnected)
        assertEquals("gcs-op-2", broker.ports[1].username)
        assertEquals(0, broker.ports[1].subscriptions["ados/d1/mavlink/tx"]?.first)
        link.close()
    }

    @Test
    fun renewedGrantIsProbedAndConfirmedAgainUnderTheNewPrincipal() = runTest {
        val broker = FakeBroker()
        var refuse = false
        broker.publishOutcome = { p ->
            if (p.qos == 1 && refuse) PublishOutcome.Refused(CloudTopics.REASON_NOT_AUTHORIZED) else PublishOutcome.Sent
        }
        val grants = Grants()
        val link = CloudMqttLink(broker, { "wss://broker.invalid/mqtt" }, grants, backgroundScope)
        val transport = CloudMavlinkTransport("d1", link, grants, WallClock { testScheduler.currentTime }, backgroundScope)
        transport.open()
        runCurrent()
        assertEquals(listOf("gcs-op-1"), grants.confirmed)

        // Renewal: the broker has not learned the new principal yet.
        refuse = true
        grants.state.value = GrantState(grant = HeldGrant("gcs-op-2", "s2", listOf("d1"), Long.MAX_VALUE, false))
        runCurrent()
        val renewed = broker.ports.last()
        assertEquals("gcs-op-2", renewed.username)
        assertTrue("the new principal is probed", renewed.published.any { it.qos == 1 })
        assertEquals(false, transport.writeAuthorized)
        assertEquals(WriteResult.NotAuthorized, transport.write(byteArrayOf(1)))
        assertTrue("no QoS 0 frame vanishes before the broker learns the grant", renewed.published.none { it.qos == 0 })

        refuse = false
        advanceTimeBy(CloudMavlinkTransport.REPROBE_MS)
        assertEquals(WriteResult.Sent, transport.write(byteArrayOf(1)))
        assertEquals(listOf("gcs-op-1", "gcs-op-2"), grants.confirmed)
        transport.close()
    }

    @Test
    fun failedReconnectAfterRenewalIsRetriedWithBackoff() = runTest {
        val broker = FakeBroker()
        val grants = Grants()
        val link = CloudMqttLink(broker, { "wss://broker.invalid/mqtt" }, grants, backgroundScope)
        val transport = CloudMavlinkTransport("d1", link, grants, WallClock { testScheduler.currentTime }, backgroundScope)
        transport.open()
        runCurrent()

        broker.failConnects = 2
        grants.state.value = GrantState(grant = HeldGrant("gcs-op-2", "s2", listOf("d1"), Long.MAX_VALUE, false))
        runCurrent()
        assertEquals(TransportState.Connecting, transport.state.value)
        assertEquals(2, broker.ports.size)

        advanceTimeBy(CloudMqttLink.RETRY_INITIAL_MS + 1)
        runCurrent()
        assertEquals("second attempt fails too", 3, broker.ports.size)
        assertEquals(TransportState.Connecting, transport.state.value)

        advanceTimeBy(2 * CloudMqttLink.RETRY_INITIAL_MS + 1)
        runCurrent()
        val up = broker.ports.last()
        assertEquals(4, broker.ports.size)
        assertEquals("gcs-op-2", up.username)
        assertEquals(0, up.subscriptions["ados/d1/mavlink/tx"]?.first)
        assertEquals(TransportState.Open, transport.state.value)
        assertEquals(WriteResult.Sent, transport.write(byteArrayOf(1)))
        transport.close()
    }
}
