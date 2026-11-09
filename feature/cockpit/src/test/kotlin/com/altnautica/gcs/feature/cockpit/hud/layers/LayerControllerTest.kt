package com.altnautica.gcs.feature.cockpit.hud.layers

import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.MemoryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayerControllerTest {
    private val demands = ArrayList<JsonObject>()

    private fun TestScope.controller(scope: CoroutineScope = backgroundScope) = LayerController(
        scope, MutableStateFlow(DeviceId("drone-1")), CockpitSettingsRepository(MemoryPreferences()),
        engine = object : VisionEngine {
            override suspend fun demand(node: DeviceId, body: JsonObject) {
                demands += body
            }
            override suspend fun status(node: DeviceId): JsonObject? = null
            override suspend fun setDetector(node: DeviceId, modelId: String) = false
        },
        now = { testScheduler.currentTime },
        previewAllowed = { true },
        armed = { false },
    )

    @Test
    fun `each cockpit holds its demand leases under its own owner`() = runTest {
        val a = controller()
        val b = controller()
        assertNotEquals(a.owner, b.owner)
        assertTrue(a.owner.matches(Regex("android:[0-9a-f]{16}")))
        runCurrent()
        val release = a.attach("main")
        a.toggle(LayerSpec.DEPTH)
        runCurrent()
        assertTrue(demands.isNotEmpty())
        assertTrue(demands.all { it["owner"]!!.jsonPrimitive.content == a.owner })
        release()
    }

    @Test
    fun `turning a layer on sends its lease at once, not on the next renewal`() = runTest {
        val c = controller()
        val outbound = ArrayList<String>()
        backgroundScope.launch { c.outbound.collect { outbound += it } }
        runCurrent()
        val release = c.attach("main")
        runCurrent()
        val viewsBefore = outbound.size
        assertTrue(demands.none { it["kind"]?.jsonPrimitive?.content == "depth" })

        advanceTimeBy(300)
        c.toggle(LayerSpec.DEPTH)
        runCurrent()
        assertTrue("the depth lease goes out within the same tick", demands.any { it["kind"]?.jsonPrimitive?.content == "depth" })
        assertEquals(viewsBefore + 1, outbound.size)
        assertTrue(outbound.last().contains("\"depth\""))

        // And it still renews every 2 s.
        val n = outbound.size
        advanceTimeBy(LayerController.RENEW_MS + 1)
        runCurrent()
        assertEquals(n + 1, outbound.size)
        release()
    }

    @Test
    fun `a socket that connects late still gets the current view lease`() = runTest {
        val c = controller()
        runCurrent()
        val release = c.attach("main")
        runCurrent()
        val late = ArrayList<String>()
        backgroundScope.launch { c.outbound.collect { late += it } }
        runCurrent()
        assertTrue(late.single().contains("\"camera_id\":\"main\""))
        release()
    }

    @Test
    fun `a compact or portrait window starts at the route level until the operator picks one`() = runTest {
        val c = controller()
        runCurrent()
        assertEquals(LayerLevel.Full, c.flags.value.level)
        c.useDefaultLevel(LayerLevel.Route)
        assertEquals(LayerLevel.Route, c.flags.value.level)

        // A level the operator picked is saved and wins over the class default.
        c.setLevel(LayerLevel.Off)
        runCurrent()
        c.useDefaultLevel(LayerLevel.Full)
        runCurrent()
        assertEquals(LayerLevel.Off, c.flags.value.level)
    }

    @Test
    fun `inside the vision view the forced layers read on and cannot be switched off`() {
        val spec = LayerSpec.DEFAULT
        val forced = spec.forced(LayerSpec.VIEW_VISION)
        val base = spec.defaults()
        assertEquals(LayerSwitch(checked = true, enabled = false, forced = true), layerSwitch(LayerSpec.DEPTH, base, forced))
        assertEquals(LayerSwitch(checked = false, enabled = true, forced = false), layerSwitch(LayerSpec.DEPTH, base, spec.forced(LayerSpec.VIEW_PILOT)))
    }
}
