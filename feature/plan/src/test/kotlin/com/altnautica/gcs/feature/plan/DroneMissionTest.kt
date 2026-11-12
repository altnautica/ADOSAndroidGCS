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
    private fun mirror(id: Long = 0, uploads: Long = 1, state: String = "loaded") = MissionMirror(
        rev = 1,
        count = 3,
        homeSlot = true,
        uploadsSeen = uploads,
        mission = MissionStatusWire(state = state, seq = 0, total = 3, id = id),
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

    @Test
    fun `another client's upload fails the plan-on-drone start check`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(13.0, 78.0))
            h.mission.opaqueId = 9
            vm.upload()
            runCurrent()
            vm.openStartMission()
            assertTrue(vm.state.value.startSheet!!.checks.single { it.kind == StartCheckKind.PlanOnVehicle }.ok)

            // Another client puts plan 7 on the drone while the sheet is open.
            h.mirror.value = mirror(id = 7)
            runCurrent()
            assertTrue(vm.state.value.inSyncWithVehicle)
            assertFalse(vm.state.value.planOnVehicle)
            assertFalse(vm.state.value.startSheet!!.checks.single { it.kind == StartCheckKind.PlanOnVehicle }.ok)
            assertFalse(vm.state.value.startSheet!!.canSlide)
        }
    }

    @Test
    fun `upload is refused while the drone flies its mission`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(13.0, 78.0))
            h.mirror.value = mirror(id = 7, state = "active")
            runCurrent()
            assertTrue(vm.state.value.missionInFlight)
            vm.upload()
            vm.retryUpload()
            runCurrent()
            assertTrue(h.mission.uploads.isEmpty())
            assertEquals(TransferState.Idle, vm.state.value.transfer)
        }
    }

    @Test
    fun `upload is refused while the drone's own heartbeat says it flies a mission`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(12.001, 77.001))
            h.heartbeat.value = com.altnautica.gcs.core.model.HeartbeatState(
                true, com.altnautica.gcs.core.model.FlightMode(3, "AUTO"), vehicleType = 2, autopilot = 3, tMs = h.now,
            )
            runCurrent()
            assertTrue(vm.state.value.missionInFlight)
            vm.upload()
            runCurrent()
            assertTrue(h.mission.uploads.isEmpty())

            h.heartbeat.value = com.altnautica.gcs.core.model.HeartbeatState(
                true, com.altnautica.gcs.core.model.FlightMode(5, "LOITER"), vehicleType = 2, autopilot = 3, tMs = h.now,
            )
            runCurrent()
            assertFalse(vm.state.value.missionInFlight)
        }
    }

    @Test
    fun `upload is refused while the arm state is unknown`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(12.001, 77.001))
            vm.onMapTap(GeoPoint(12.002, 77.002))
            runCurrent()
            assertFalse(vm.state.value.armStateUnknown)

            // The heartbeat goes stale: the drone may have armed since.
            h.now += 3_500
            testScheduler.advanceTimeBy(1_100)
            runCurrent()
            assertTrue(vm.state.value.armStateUnknown)
            vm.upload()
            runCurrent()
            assertTrue(h.mission.uploads.isEmpty())

            // No heartbeat at all.
            h.heartbeat.value = null
            runCurrent()
            assertTrue(vm.state.value.armStateUnknown)
            vm.upload()
            runCurrent()
            assertTrue(h.mission.uploads.isEmpty())

            // A fresh disarmed heartbeat lets the upload through.
            h.connect(vm)
            testScheduler.advanceTimeBy(1_100)
            runCurrent()
            assertFalse(vm.state.value.armStateUnknown)
            vm.upload()
            runCurrent()
            assertTrue(h.mission.uploads.isNotEmpty())
        }
    }

    @Test
    fun `the drone's fence parameters block points outside its limits`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(GeoPoint(12.0, 77.0)) // the first tap becomes the takeoff
            vm.onMapTap(GeoPoint(12.01, 77.0)) // a waypoint about 1.1 km north of the drone's home
            runCurrent()
            val kinds = { vm.state.value.issues.map { it.kind } }
            assertFalse(com.altnautica.gcs.feature.plan.model.PlanIssueKind.OutsideHomeCircleFence in kinds())
            // Circle (2) and ceiling (1) enforced; the floor bit is clear so FENCE_ALT_MIN is ignored.
            h.params("FENCE_ENABLE" to 1.0, "FENCE_TYPE" to 3.0, "FENCE_RADIUS" to 500.0, "FENCE_ALT_MAX" to 10.0, "FENCE_ALT_MIN" to 50.0)
            runCurrent()
            assertTrue(com.altnautica.gcs.feature.plan.model.PlanIssueKind.OutsideHomeCircleFence in kinds())
            assertTrue(com.altnautica.gcs.feature.plan.model.PlanIssueKind.AboveFenceCeiling in kinds())
            assertFalse(com.altnautica.gcs.feature.plan.model.PlanIssueKind.BelowFenceFloor in kinds())
            vm.upload()
            runCurrent()
            assertTrue(h.mission.uploads.isEmpty())
        }
    }
}
