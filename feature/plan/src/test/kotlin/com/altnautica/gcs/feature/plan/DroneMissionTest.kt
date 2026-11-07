package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.agent.mission.MirrorItem
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.mission.MissionStatusWire
import com.altnautica.gcs.core.map.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DroneMissionTest {

    private val h = PlannerHarness()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wp(seq: Int, lat: Double, lon: Double) =
        MirrorItem(seq = seq, command = 16, frame = 6, x = (lat * 1e7).toInt(), y = (lon * 1e7).toInt(), z = 20f)

    /** An ArduPilot mirror: home slot plus two waypoints. */
    private fun mirror(id: Long = 0, uploads: Long = 1) = MissionMirror(
        rev = 1,
        count = 3,
        homeSlot = true,
        uploadsSeen = uploads,
        mission = MissionStatusWire(state = "loaded", seq = 0, total = 3, id = id),
        items = listOf(wp(0, 12.0, 77.0), wp(1, 12.001, 77.001), wp(2, 12.002, 77.002)),
    )

    @Test
    fun `a non-zero vehicle plan id matches only the id returned on upload`() {
        assertTrue(PlanIdentity.matches(mirror(id = 42), PlanReceipt(42, 3, 1)))
        assertFalse(PlanIdentity.matches(mirror(id = 43), PlanReceipt(42, 3, 1)))
    }

    @Test
    fun `without plan ids the count matches only while no other client uploaded`() {
        assertTrue(PlanIdentity.matches(mirror(uploads = 1), PlanReceipt(0, 3, 1)))
        assertFalse(PlanIdentity.matches(mirror(uploads = 2), PlanReceipt(0, 3, 1)))
        assertFalse(PlanIdentity.matches(mirror(uploads = 1), PlanReceipt(0, 4, 1)))
        assertFalse(PlanIdentity.matches(mirror(), null))
    }

    @Test
    fun `the mirrored mission becomes a plan without its home slot`() {
        val plan = PlanIdentity.planOf(mirror()) { 1L }
        assertNotNull(plan)
        assertEquals(2, plan!!.items.size)
    }

    @Test
    fun `another plan on the drone is flagged and Load from drone takes it`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(13.0, 78.0))
            h.mirror.value = mirror(id = 7)
            runCurrent()
            assertTrue(vm.state.value.drone!!.differs)
            assertTrue(vm.state.value.toOverlays().lines.any { it.id == "drone-mission" })

            vm.loadFromDrone()
            runCurrent()
            assertEquals(2, vm.state.value.plan.items.size)
            assertFalse(vm.state.value.drone!!.differs)
            assertTrue(vm.state.value.toOverlays().lines.none { it.id == "drone-mission" })
        }
    }

    @Test
    fun `this device's upload is recognised by the plan id the vehicle returned`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(13.0, 78.0))
            h.mission.opaqueId = 9
            vm.upload()
            runCurrent()
            // The vehicle now reports plan 9 with items this device did not draw identically (rounded).
            h.mirror.value = mirror(id = 9)
            runCurrent()
            assertFalse(vm.state.value.drone!!.differs)
        }
    }
}
