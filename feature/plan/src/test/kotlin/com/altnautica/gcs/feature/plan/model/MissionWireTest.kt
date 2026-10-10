package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class MissionWireTest {

    private var next = 100L
    private val nextId: () -> Long = { next++ }

    private val plan = MissionPlan(
        items = listOf(
            PlanItem(1, MavCmd.NAV_TAKEOFF, MavFrame.GLOBAL_RELATIVE_ALT, x = 12.5, y = 77.25, z = 15f),
            PlanItem(2, MavCmd.NAV_WAYPOINT, MavFrame.GLOBAL, x = 12.501, y = 77.251, z = 930f, speedMs = 7f),
            PlanItem(3, MavCmd.DO_JUMP, MavFrame.MISSION, param1 = 2f, param2 = 2f, jumpTarget = 2),
        ),
        fence = listOf(
            FenceZone.Polygon(4, true, listOf(GeoPoint(1.0, 1.0), GeoPoint(1.0, 2.0), GeoPoint(2.0, 2.0))),
            FenceZone.Circle(5, false, GeoPoint(1.5, 1.5), 30.0),
        ),
        rally = listOf(RallyPoint(6, GeoPoint(1.2, 1.3), 40f)),
    )

    @Test
    fun `mission items use int frames and scaled positions`() {
        val wire = MissionWire.toWire(plan, homeSlot = true)

        assertEquals(4, wire.mission.size)
        val takeoff = wire.mission[0]
        assertEquals(MavFrame.GLOBAL_RELATIVE_ALT_INT, takeoff.frame)
        assertEquals(125_000_000, takeoff.x)
        assertEquals(772_500_000, takeoff.y)
        assertEquals(MavCmd.DO_CHANGE_SPEED, wire.mission[1].command)
        assertEquals(7f, wire.mission[1].param2)
        assertEquals(MavFrame.GLOBAL_INT, wire.mission[2].frame)
        // ArduPilot: first mission item is wire seq 1, the speed row that opens item 2 is seq 2.
        assertEquals(2f, wire.mission[3].param1)
    }

    @Test
    fun `jump targets shift down on vehicles without a home slot`() {
        val wire = MissionWire.toWire(plan, homeSlot = false)

        assertEquals(1f, wire.mission[3].param1)
        val back = MissionWire.fromWire(null, wire.mission, emptyList(), emptyList(), homeSlot = false, nextId).plan
        assertEquals(back.items[1].id, back.items[2].jumpTarget)
    }

    @Test
    fun `fence polygon vertices carry the vertex count and circles the radius`() {
        val fence = MissionWire.toWire(plan, homeSlot = true).fence

        assertEquals(4, fence.size)
        assertEquals(List(3) { MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION }, fence.take(3).map { it.command })
        assertEquals(List(3) { 3f }, fence.take(3).map { it.param1 })
        assertEquals(MavCmd.NAV_FENCE_CIRCLE_EXCLUSION, fence[3].command)
        assertEquals(30f, fence[3].param1)
    }

    @Test
    fun `downloaded lists rebuild the same plan`() {
        val wire = MissionWire.toWire(plan, homeSlot = true)
        val home = MissionItem.global(MavCmd.NAV_WAYPOINT, 12.4999, 77.2499, 920f, frame = MavFrame.GLOBAL_INT)

        val back = MissionWire.fromWire(home, wire.mission, wire.fence, wire.rally, homeSlot = true, nextId).plan

        assertEquals(plan.items.map { it.copy(id = 0, jumpTarget = null) }, back.items.map { it.copy(id = 0, jumpTarget = null) })
        assertEquals(back.items[1].id, back.items[2].jumpTarget)
        assertEquals(
            plan.fence.map { if (it is FenceZone.Polygon) it.copy(id = 0) else (it as FenceZone.Circle).copy(id = 0) },
            back.fence.map { if (it is FenceZone.Polygon) it.copy(id = 0) else (it as FenceZone.Circle).copy(id = 0) },
        )
        assertEquals(listOf(40f), back.rally.map { it.altM })
        assertEquals(GeoPoint(1.2, 1.3), back.rally.single().position)
        assertEquals(PlanHome(GeoPoint(12.4999, 77.2499), 920f), back.home)
    }

    private fun row(seq: Int, command: Int, p1: Float = 0f, p2: Float = 0f, p3: Float = 0f, p4: Float = 0f) = FlatItem(
        seq = seq, command = command, frame = if (command == MavCmd.DO_CHANGE_SPEED) MavFrame.MISSION else MavFrame.GLOBAL_RELATIVE_ALT,
        param1 = p1, param2 = p2, param3 = p3, param4 = p4, x = 12.5, y = 77.25, z = 20f, autoContinue = true,
    )

    @Test
    fun `airspeed and throttle speed changes survive a collapse and flatten round trip`() {
        val rows = listOf(
            // Airspeed change (param1 = 0), typical of a plane mission.
            row(1, MavCmd.DO_CHANGE_SPEED, p1 = 0f, p2 = 18f, p3 = -1f),
            row(2, MavCmd.NAV_WAYPOINT),
            // Ground speed with a throttle setting.
            row(3, MavCmd.DO_CHANGE_SPEED, p1 = 1f, p2 = 6f, p3 = 55f),
            row(4, MavCmd.NAV_WAYPOINT),
        )

        val collapsed = MissionFlattener.collapse(rows, nextId).items

        assertEquals(4, collapsed.size)
        assertEquals(listOf(null, null, null, null), collapsed.map { it.speedMs })
        assertEquals(rows, MissionFlattener.flatten(collapsed))
    }

    @Test
    fun `the speed row flatten writes folds back into its item`() {
        val rows = listOf(row(1, MavCmd.DO_CHANGE_SPEED, p1 = 1f, p2 = 7f, p3 = -1f), row(2, MavCmd.NAV_WAYPOINT))

        val collapsed = MissionFlattener.collapse(rows, nextId).items

        assertEquals(1, collapsed.size)
        assertEquals(7f, collapsed.single().speedMs)
    }
}
