package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.map.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionValidatorTest {

    private val home = GeoPoint(12.0, 77.0)
    private val near = GeoPoint(12.0003, 77.0)
    private val far = GeoPoint(12.01, 77.0)
    private var nextId = 1L

    private fun item(command: PlanCommand, at: GeoPoint?, altM: Float = 30f) =
        PlanEditor.newItem(nextId++, command, at, altM, AltitudeRef.Relative)

    private fun good(vararg extra: PlanItem) = MissionPlan(
        items = listOf(item(PlanCommand.Takeoff, home), item(PlanCommand.Waypoint, near)) + extra + item(PlanCommand.ReturnToLaunch, null),
    )

    private fun kinds(plan: MissionPlan, takeoffExpected: Boolean = true) = MissionValidator.validate(plan, takeoffExpected).map { it.kind }.toSet()

    @Test
    fun `a well formed plan has no issues`() {
        assertEquals(emptySet<PlanIssueKind>(), kinds(good()))
    }

    @Test
    fun `empty plan is blocking`() {
        assertEquals(setOf(PlanIssueKind.Empty), kinds(MissionPlan()))
        assertTrue(PlanIssueKind.Empty.blocking)
    }

    @Test
    fun `missing takeoff warns only where a takeoff is expected`() {
        val plan = MissionPlan(items = listOf(item(PlanCommand.Waypoint, near), item(PlanCommand.Land, near)))
        assertTrue(PlanIssueKind.NoTakeoffFirst in kinds(plan))
        assertFalse(PlanIssueKind.NoTakeoffFirst in kinds(plan, takeoffExpected = false))
        assertTrue(MissionValidator.takeoffExpected(2))
        assertTrue(MissionValidator.takeoffExpected(null))
        assertFalse(MissionValidator.takeoffExpected(10))
    }

    @Test
    fun `missing land or return warns`() {
        val plan = MissionPlan(items = listOf(item(PlanCommand.Takeoff, home), item(PlanCommand.Waypoint, near)))
        assertEquals(setOf(PlanIssueKind.NoLandOrRtlLast), kinds(plan))
        assertFalse(PlanIssueKind.NoLandOrRtlLast.blocking)
    }

    @Test
    fun `relative altitude above 120 m warns`() {
        assertEquals(setOf(PlanIssueKind.AltitudeAbove120mRel), kinds(good(item(PlanCommand.Waypoint, near, altM = 121f))))
        assertEquals(emptySet<PlanIssueKind>(), kinds(good(item(PlanCommand.Waypoint, near, altM = 120f))))
    }

    @Test
    fun `items outside every inclusion zone block`() {
        val fence = FenceZone.Circle(99, inclusion = true, center = home, radiusM = 200.0)
        assertEquals(emptySet<PlanIssueKind>(), kinds(good().copy(fence = listOf(fence))))
        val outside = good(item(PlanCommand.Waypoint, far)).copy(fence = listOf(fence))
        assertEquals(setOf(PlanIssueKind.OutsideInclusionFence), kinds(outside))
        assertTrue(PlanIssueKind.OutsideInclusionFence.blocking)
    }

    @Test
    fun `items inside an exclusion polygon block`() {
        val square = listOf(GeoPoint(12.0002, 76.9999), GeoPoint(12.0002, 77.0001), GeoPoint(12.0004, 77.0001), GeoPoint(12.0004, 76.9999))
        val zone = FenceZone.Polygon(98, inclusion = false, points = square)
        assertEquals(setOf(PlanIssueKind.InsideExclusionFence), kinds(good().copy(fence = listOf(zone))))
        val elsewhere = zone.copy(points = square.map { GeoPoint(it.latDeg + 0.01, it.lonDeg) })
        assertEquals(emptySet<PlanIssueKind>(), kinds(good().copy(fence = listOf(elsewhere))))
    }

    @Test
    fun `read-back compares within the wire tolerances`() {
        val sent = listOf(MissionItem.global(MavCmd.NAV_WAYPOINT, 12.0, 77.0, 30f))
        assertTrue(MissionVerify.matches(sent, listOf(sent[0].copy(x = sent[0].x + 1, z = 30.005f))))
        assertFalse(MissionVerify.matches(sent, listOf(sent[0].copy(x = sent[0].x + 2))))
        assertFalse(MissionVerify.matches(sent, listOf(sent[0].copy(command = MavCmd.NAV_LAND))))
        assertFalse(MissionVerify.matches(sent, emptyList()))
    }
}
