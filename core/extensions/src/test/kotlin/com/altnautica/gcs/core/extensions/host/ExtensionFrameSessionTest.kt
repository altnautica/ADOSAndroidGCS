package com.altnautica.gcs.core.extensions.host

import com.altnautica.gcs.core.extensions.bridge.BridgeHandler
import com.altnautica.gcs.core.extensions.bridge.ExtensionEventBus
import com.altnautica.gcs.core.extensions.bridge.MountHandlers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionFrameSessionTest {

    private var created = 0
    private var disposed = 0
    private var started = 0
    private var cancelled = 0

    private fun handlers() = MountHandlers(
        mapOf<String, BridgeHandler>(
            "ping" to { _ ->
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            },
        ),
    ) { disposed++ }.also { created++ }

    private val envelope =
        """{"kind":"envelope","envelope":{"id":"r1","type":"request","method":"ping","capability":"","args":null,"version":1}}"""

    @Test
    fun detachingThePageDisposesItsHandlersAndCancelsInFlightRequests() = runTest {
        val sent = mutableListOf<String>()
        val session = ExtensionFrameSession(
            pluginId = "com.example.ext",
            newHandlers = ::handlers,
            granted = { emptySet() },
            bus = ExtensionEventBus(),
            agentStateOrigin = "agent",
            scope = backgroundScope,
        )
        session.attach { sent += it }
        session.onRelayMessage(envelope)
        runCurrent()
        assertEquals(1, started)

        session.detach()
        runCurrent()
        assertFalse(session.attached)
        assertEquals(1, disposed)
        assertEquals(1, cancelled)

        // Nothing dispatches or posts while detached.
        session.onRelayMessage(envelope)
        session.hostEvent("theme.changed", "", JsonPrimitive("x"))
        runCurrent()
        assertEquals(1, started)
        assertTrue(sent.isEmpty())

        // A re-attached page gets a fresh handler set.
        session.attach { sent += it }
        assertEquals(2, created)
        session.dispose()
        assertEquals(2, disposed)
    }
}
