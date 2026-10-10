package com.altnautica.gcs.core.link.param

import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.link.testing.demoHarness
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue as ParamValueMsg
import kotlinx.coroutines.test.runTest
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.transport.DemoTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamServiceTest {

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
