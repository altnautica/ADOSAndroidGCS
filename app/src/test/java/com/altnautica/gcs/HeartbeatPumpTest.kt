package com.altnautica.gcs

import com.altnautica.gcs.data.mavlink.HeartbeatPump
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPumpTest {

    @Test
    fun `pump sends heartbeats at roughly 1Hz while connected`() = runBlocking {
        val store = TelemetryStore()
        val sender = mockk<MavLinkCommandSender>(relaxed = true)
        coEvery { sender.sendHeartbeat() } returns Unit

        val pump = HeartbeatPump(store, sender)
        pump.start()

        // Pump starts only after a CONNECTED state arrives.
        store.updateConnection(ConnectionState(ConnectionStatus.CONNECTED, "Test"))

        // Let three full intervals pass. Each iteration is 1000ms apart.
        delay(3_500L)

        // Expect about 4 calls in 3.5s: t=0, t=1s, t=2s, t=3s.
        coVerify(atLeast = 3, atMost = 5) { sender.sendHeartbeat() }

        // After disconnect, the pump should stop sending.
        store.updateConnection(ConnectionState(ConnectionStatus.DISCONNECTED, "Bye"))
        delay(200L)
        assertFalse("pump should stop after disconnect", pump.isBeating())

        pump.release()
    }

    @Test
    fun `pump does not send when never connected`() = runBlocking {
        val store = TelemetryStore()
        val sender = mockk<MavLinkCommandSender>(relaxed = true)
        coEvery { sender.sendHeartbeat() } returns Unit

        val pump = HeartbeatPump(store, sender)
        pump.start()
        delay(2_000L)

        coVerify(exactly = 0) { sender.sendHeartbeat() }
        pump.release()
    }

    @Test
    fun `pump records lastGcsHeartbeatSentMs while running`() = runBlocking {
        val store = TelemetryStore()
        val sender = mockk<MavLinkCommandSender>(relaxed = true)
        coEvery { sender.sendHeartbeat() } returns Unit

        assertEquals(0L, store.lastGcsHeartbeatSentMs)

        val pump = HeartbeatPump(store, sender)
        pump.start()
        store.updateConnection(ConnectionState(ConnectionStatus.CONNECTED, "Test"))
        delay(1_200L)

        assertTrue(
            "lastGcsHeartbeatSentMs should be stamped after heartbeat",
            store.lastGcsHeartbeatSentMs > 0L,
        )
        pump.release()
    }
}
