package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.agent.mission.MirrorItem
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.mission.MissionStatusWire
import com.altnautica.gcs.core.link.mission.AgentMissionGapsSource
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.WaypointKind
import com.altnautica.gcs.core.model.MissionGap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The pilot's skipped waypoints show on the planner's map where the vehicle's plan puts them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkippedWaypointsTest {
    private val h = PlannerHarness()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wp(seq: Int, lat: Double, lon: Double, command: Int = 16) =
        MirrorItem(seq = seq, command = command, frame = 6, x = (lat * 1e7).toInt(), y = (lon * 1e7).toInt(), z = 20f)

    /** An ArduPilot mirror: home slot, three waypoints and a positionless speed change. */
    private val mirror = MissionMirror(
        rev = 1,
        count = 5,
        homeSlot = true,
        uploadsSeen = 1,
        mission = MissionStatusWire(state = "active", seq = 3, total = 5, id = 7),
        items = listOf(wp(0, 12.0, 77.0), wp(1, 12.001, 77.001), wp(2, 12.002, 77.002), wp(3, 12.003, 77.003), MirrorItem(seq = 4, command = 178)),
    )

    @Test
    fun `a gap is placed on its mirrored item with the planner's numbering`() {
        val gaps = listOf(MissionGap(2, "stall_timeout", 1L), MissionGap(4, "stall_operator", 2L), MissionGap(9, "stall_timeout", 3L))
        assertEquals(listOf(SkippedWaypoint(2, "2", GeoPoint(12.002, 77.002))), PlanIdentity.skippedOf(mirror, gaps))
        val px4 = mirror.copy(homeSlot = false)
        assertEquals("without a home slot seq 2 is the third item", "3", PlanIdentity.skippedOf(px4, gaps).single().label)
    }

    @Test
    fun `the pilot's skipped waypoints show on the map, crossed out, and clear with the gap`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            h.mirror.value = mirror
            runCurrent()
            assertTrue(vm.state.value.toOverlays().waypoints.none { it.kind == WaypointKind.Skipped })

            h.gaps.value = listOf(MissionGap(2, "stall_timeout", 1L))
            runCurrent()
            val marker = vm.state.value.toOverlays().waypoints.single { it.kind == WaypointKind.Skipped }
            assertEquals("\u00D72", marker.label)
            assertEquals(GeoPoint(12.002, 77.002), marker.position)
            assertFalse("a skipped marker never moves the plan", marker.draggable)

            h.gaps.value = emptyList()
            runCurrent()
            assertTrue(vm.state.value.toOverlays().waypoints.none { it.kind == WaypointKind.Skipped })
        }
    }

    @Test
    fun `the gaps are read from the pilot frame of the navigation status`() {
        val frame = File("../../core/agent/src/test/resources/fixtures/pilot-frame-stall.json").readText()
        val status = Json.parseToJsonElement("""{"pilot":{"ts_ms":5,"age_ms":40,"data":$frame},"pose":null}""") as JsonObject
        assertEquals(listOf(MissionGap(2, "stall_timeout", 1793959950000L)), AgentMissionGapsSource.gapsOf(status))
        assertEquals(emptyList<MissionGap>(), AgentMissionGapsSource.gapsOf(Json.parseToJsonElement("""{"pilot":null}""") as JsonObject))
        assertEquals(emptyList<MissionGap>(), AgentMissionGapsSource.gapsOf(null))
    }
}
