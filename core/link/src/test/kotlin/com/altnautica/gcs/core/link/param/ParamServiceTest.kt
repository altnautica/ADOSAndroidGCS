package com.altnautica.gcs.core.link.param

import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.link.testing.demoHarness
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue as ParamValueMsg
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.model.DeviceId
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ParamServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val node = DeviceId("drone-1")

    /** The vehicle [ScriptedLink] speaks as. */
    private val target = VehicleTarget(1, 1, 3, 2)
    private val key = ParamCacheKey.of(node, target)

    private fun real(name: String, value: Double, index: Int) =
        ParamValue(name, value, MavParamType.MAV_PARAM_TYPE_REAL32, index)

    private fun TestScope.cache(dir: File = tmp.root) =
        ParamCache(dir, backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `a cached list shows complete before any download`() = runTest {
        val cache = cache()
        cache.put(key, 2, mapOf("A" to real("A", 1.0, 0), "B" to real("B", 2.0, 1)))
        val link = ScriptedLink(target)
        val params = MavlinkParamService(backgroundScope, link, cache, node)
        runCurrent()
        val snap = params.snapshot.value
        assertTrue(snap.complete)
        assertEquals(setOf("A", "B"), snap.values.keys)
        assertTrue(params.fromCache.value)
        assertTrue(link.sent.none { it is ParamRequestList })
    }

    @Test
    fun `a sync over a cached list keeps the values on screen and confirms them`() = runTest {
        val cache = cache()
        // C is gone from the flight controller since the list was cached.
        cache.put(key, 3, mapOf("A" to real("A", 1.0, 0), "B" to real("B", 2.0, 1), "C" to real("C", 3.0, 2)))
        val link = ScriptedLink(target) { m ->
            if (m is ParamRequestList) {
                reply(ParamValueMsg("A", 5f, MavParamType.MAV_PARAM_TYPE_REAL32, 2, 0))
                reply(ParamValueMsg("B", 2f, MavParamType.MAV_PARAM_TYPE_REAL32, 2, 1))
            }
        }
        val params = MavlinkParamService(backgroundScope, link, cache, node)
        runCurrent()
        assertTrue(params.fromCache.value)
        val seen = mutableListOf<ParamSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { params.snapshot.collect { seen += it } }

        assertEquals(ParamLoadResult.Complete(2), params.refresh(clear = false))

        assertTrue("values stay visible during the sync", seen.all { it.values.isNotEmpty() })
        assertTrue("coverage restarts during the sync", seen.any { !it.complete })
        assertFalse(params.fromCache.value)
        val snap = params.snapshot.value
        assertTrue(snap.complete)
        assertEquals(setOf("A", "B"), snap.values.keys)
        assertEquals(5.0, snap.values.getValue("A").value, 0.0)
    }

    @Test
    fun `a refresh that clears starts from an empty list`() = runTest {
        val cache = cache()
        cache.put(key, 1, mapOf("A" to real("A", 1.0, 0)))
        val link = ScriptedLink(target) { m ->
            if (m is ParamRequestList) reply(ParamValueMsg("A", 4f, MavParamType.MAV_PARAM_TYPE_REAL32, 1, 0))
        }
        val params = MavlinkParamService(backgroundScope, link, cache, node)
        runCurrent()
        val seen = mutableListOf<ParamSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { params.snapshot.collect { seen += it } }
        assertEquals(ParamLoadResult.Complete(1), params.refresh(clear = true))
        assertTrue(seen.any { it.values.isEmpty() })
        assertFalse(params.fromCache.value)
        assertEquals(4.0, params.snapshot.value.values.getValue("A").value, 0.0)
    }

    @Test
    fun `a downloaded list reaches disk and seeds the next link`() = runTest {
        val link = ScriptedLink(target) { m ->
            if (m is ParamRequestList) reply(ParamValueMsg("A", 7f, MavParamType.MAV_PARAM_TYPE_REAL32, 1, 0))
        }
        val first = cache()
        assertEquals(ParamLoadResult.Complete(1), MavlinkParamService(backgroundScope, link, first, node).refresh())
        advanceTimeBy(ParamCache.WRITE_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(File(tmp.root, key.fileName).exists())

        // A new process: only the disk copy is left.
        val params = MavlinkParamService(backgroundScope, ScriptedLink(target), cache(), node)
        runCurrent()
        assertTrue(params.snapshot.value.complete)
        assertEquals(7.0, params.snapshot.value.values.getValue("A").value, 0.0)
        assertTrue(params.fromCache.value)
    }

    @Test
    fun `an unreadable cache file is deleted and ignored`() = runTest {
        val file = File(tmp.root, key.fileName).apply { writeText("{not json") }
        assertNull(cache().load(key))
        assertFalse(file.exists())
    }

    @Test
    fun `a download goes on when the screen that asked for it goes away`() = runTest {
        val link = ScriptedLink(target) { m ->
            when (m) {
                is ParamRequestList -> reply(ParamValueMsg("A", 1f, MavParamType.MAV_PARAM_TYPE_REAL32, 2, 0))
                is ParamRequestRead -> reply(ParamValueMsg("B", 2f, MavParamType.MAV_PARAM_TYPE_REAL32, 2, 1))
                else -> Unit
            }
        }
        val params = MavlinkParamService(backgroundScope, link)
        val screen = launch { params.refresh() }
        runCurrent()
        screen.cancel()
        advanceTimeBy(MavlinkParamService.IDLE_MS * 3)
        runCurrent()

        assertTrue(params.snapshot.value.complete)
        assertEquals(setOf("A", "B"), params.snapshot.value.values.keys)
    }

    @Test
    fun `missing indices are re-requested after the stream goes quiet`() = runTest {
        val h = demoHarness(DemoOptions(droppedParamIndices = setOf(3, 17, 40)))
        val params = MavlinkParamService(backgroundScope, h.link)
        val result = params.refresh()
        val total = com.altnautica.gcs.core.link.demo.ArduCopterSimulator.defaultParams().size
        assertEquals(ParamLoadResult.Complete(total), result)
        val snap = params.snapshot.value
        assertTrue(snap.complete)
        assertEquals(total, snap.values.size)
        assertEquals(1500.0, snap.values.getValue("RTL_ALT").value, 0.0)
    }

    @Test
    fun `a load asked for before the first heartbeat waits for the vehicle`() = runTest {
        // The node screen asks for parameters as soon as the link opens, before the vehicle is identified.
        val link = TransportMavlinkLink(backgroundScope, ElapsedClock { testScheduler.currentTime })
        val params = MavlinkParamService(backgroundScope, link)
        val result = async { params.refresh() }
        runCurrent()
        assertTrue(result.isActive)
        link.attach(DemoTransport(backgroundScope.coroutineContext, DemoOptions()))
        val total = com.altnautica.gcs.core.link.demo.ArduCopterSimulator.defaultParams().size
        assertEquals(ParamLoadResult.Complete(total), result.await())
    }

    @Test
    fun `a load with no vehicle heartbeat fails as no link`() = runTest {
        val link = TransportMavlinkLink(backgroundScope, ElapsedClock { testScheduler.currentTime })
        assertEquals(ParamLoadResult.Failed(MavlinkParamService.REASON_NO_LINK), MavlinkParamService(backgroundScope, link).refresh())
    }

    @Test
    fun `only the absent indices are asked for, by index`() = runTest {
        val names = listOf("A", "B", "C", "D")
        var listed = false
        val link = ScriptedLink { m ->
            when (m) {
                is ParamRequestList -> if (!listed) {
                    listed = true
                    // Index 2 is lost on the way.
                    for (i in names.indices) if (i != 2) reply(ParamValueMsg(names[i], i.toFloat(), MavParamType.MAV_PARAM_TYPE_REAL32, names.size, i))
                }
                is ParamRequestRead -> reply(ParamValueMsg(names[m.paramIndex], m.paramIndex.toFloat(), MavParamType.MAV_PARAM_TYPE_REAL32, names.size, m.paramIndex))
                else -> Unit
            }
        }
        val params = MavlinkParamService(backgroundScope, link)
        assertEquals(ParamLoadResult.Complete(4), params.refresh())
        val reads = link.sent.filterIsInstance<ParamRequestRead>()
        assertEquals(listOf(2), reads.map { it.paramIndex })
        assertEquals("", reads.single().paramId)
    }

    @Test
    fun `gives up after three repair rounds`() = runTest {
        val link = ScriptedLink { m ->
            if (m is ParamRequestList) reply(ParamValueMsg("A", 1f, MavParamType.MAV_PARAM_TYPE_REAL32, 2, 0))
        }
        val result = MavlinkParamService(backgroundScope, link).refresh()
        assertEquals(ParamLoadResult.Incomplete(2, 1), result)
        assertEquals(3, link.sent.count { it is ParamRequestRead })
    }

    @Test
    fun `set is confirmed by the echoed value`() = runTest {
        val h = demoHarness()
        val params = MavlinkParamService(backgroundScope, h.link)
        params.refresh()
        val r = params.set("RTL_ALT", 2500.0)
        assertTrue(r is ParamSetResult.Confirmed)
        assertEquals(2500.0, (r as ParamSetResult.Confirmed).value.value, 0.0)
    }

    @Test
    fun `a clamped write is reported as a mismatch`() = runTest {
        val link = ScriptedLink { m ->
            when (m) {
                is ParamRequestRead -> reply(ParamValueMsg("ANGLE_MAX", 3000f, MavParamType.MAV_PARAM_TYPE_INT16, 1, 0))
                is ParamSet -> reply(ParamValueMsg("ANGLE_MAX", 8000f, MavParamType.MAV_PARAM_TYPE_INT16, 1, 0))
                else -> Unit
            }
        }
        val r = MavlinkParamService(backgroundScope, link).set("ANGLE_MAX", 9000.0)
        assertTrue(r is ParamSetResult.Mismatch)
        assertEquals(8000.0, (r as ParamSetResult.Mismatch).actual.value, 0.0)
    }
}
