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

    private fun kinds(plan: MissionPlan, profile: ValidationProfile = ValidationProfile()) = MissionValidator.validate(plan, profile).map { it.kind }.toSet()

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
    fun `a plan that starts airborne without a takeoff blocks except on PX4`() {
        val plan = MissionPlan(items = listOf(item(PlanCommand.Waypoint, near), item(PlanCommand.Land, near)))
        assertTrue(PlanIssueKind.MissingTakeoff in kinds(plan))
        assertTrue(PlanIssueKind.MissingTakeoff.blocking)
        assertTrue(PlanIssueKind.MissingTakeoff in kinds(plan, ValidationProfile(2, Autopilot.ArduPilotOther)))
        val px4 = kinds(plan, ValidationProfile(2, Autopilot.Px4))
        assertTrue(PlanIssueKind.NoTakeoffFirst in px4)
        assertFalse(PlanIssueKind.MissingTakeoff in px4)
        // Rovers and boats have no takeoff.
        assertFalse(PlanIssueKind.MissingTakeoff in kinds(plan, ValidationProfile(10, Autopilot.ArduPilotOther)))
        assertFalse(PlanIssueKind.NoTakeoffFirst in kinds(plan, ValidationProfile(10, Autopilot.ArduPilotOther)))
    }

    @Test
    fun `a ground start without a takeoff blocks only on ArduCopter`() {
        val plan = MissionPlan(items = listOf(item(PlanCommand.Land, near, altM = 0f), item(PlanCommand.Waypoint, near), item(PlanCommand.Land, near)))
        assertTrue(PlanIssueKind.MissingTakeoff in kinds(plan, ValidationProfile(2, Autopilot.ArduCopter)))
        assertTrue(PlanIssueKind.NoTakeoffFirst in kinds(plan, ValidationProfile(2, Autopilot.Px4)))
        assertTrue(PlanIssueKind.NoTakeoffFirst in kinds(plan))
    }

    @Test
    fun `a flown item at or below home blocks`() {
        assertEquals(setOf(PlanIssueKind.AltitudeAtOrBelowHome), kinds(good(item(PlanCommand.Waypoint, near, altM = 0f))))
        assertEquals(setOf(PlanIssueKind.AltitudeAtOrBelowHome), kinds(good(item(PlanCommand.Waypoint, near, altM = -5f))))
        assertTrue(PlanIssueKind.AltitudeAtOrBelowHome.blocking)
    }

    @Test
    fun `a waypoint at zero zero or off the globe blocks`() {
        val zero = item(PlanCommand.Waypoint, near).copy(x = 0.0, y = 0.0)
        assertEquals(setOf(PlanIssueKind.InvalidCoordinates), kinds(good(zero)))
        val offGlobe = item(PlanCommand.Waypoint, near).copy(x = 95.0)
        assertTrue(PlanIssueKind.InvalidCoordinates in kinds(good(offGlobe)))
    }

    @Test
    fun `a leg through an exclusion zone blocks even with both ends outside it`() {
        val west = GeoPoint(12.0003, 76.999)
        val east = GeoPoint(12.0003, 77.001)
        val square = listOf(GeoPoint(12.0002, 76.9999), GeoPoint(12.0002, 77.0001), GeoPoint(12.0004, 77.0001), GeoPoint(12.0004, 76.9999))
        val plan = MissionPlan(
            items = listOf(item(PlanCommand.Takeoff, west), item(PlanCommand.Waypoint, east), item(PlanCommand.ReturnToLaunch, null)),
            fence = listOf(FenceZone.Polygon(98, inclusion = false, points = square)),
        )
        assertEquals(setOf(PlanIssueKind.LegCrossesFence), kinds(plan))
        val circle = plan.copy(fence = listOf(FenceZone.Circle(97, inclusion = false, center = GeoPoint(12.0003, 77.0), radiusM = 20.0)))
        assertEquals(setOf(PlanIssueKind.LegCrossesFence), kinds(circle))
    }

    @Test
    fun `a leg that leaves a concave inclusion zone blocks`() {
        // A U shape open to the north: both ends sit in the arms, the straight leg crosses the gap.
        val u = listOf(
            GeoPoint(12.000, 77.000), GeoPoint(12.000, 77.003), GeoPoint(12.003, 77.003), GeoPoint(12.003, 77.002),
            GeoPoint(12.001, 77.002), GeoPoint(12.001, 77.001), GeoPoint(12.003, 77.001), GeoPoint(12.003, 77.000),
        )
        val plan = MissionPlan(
            items = listOf(item(PlanCommand.Takeoff, GeoPoint(12.002, 77.0005)), item(PlanCommand.Waypoint, GeoPoint(12.002, 77.0025)), item(PlanCommand.ReturnToLaunch, null)),
            fence = listOf(FenceZone.Polygon(96, inclusion = true, points = u)),
        )
        assertEquals(setOf(PlanIssueKind.LegCrossesFence), kinds(plan))
    }

    @Test
    fun `a jump without a target or looping forever blocks`() {
        val target = item(PlanCommand.Waypoint, near)
        val jump = PlanItem(id = nextId++, command = MavCmd.DO_JUMP, frame = MavFrame.MISSION, param2 = 2f, jumpTarget = target.id)
        assertEquals(emptySet<PlanIssueKind>(), kinds(good(target, jump)))
        assertEquals(setOf(PlanIssueKind.InvalidJump), kinds(good(target, jump.copy(jumpTarget = 9999))))
        assertEquals(setOf(PlanIssueKind.InvalidJump), kinds(good(target, jump.copy(param2 = -1f))))
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
    fun `the vehicle's home circle, ceiling and floor block points outside them`() {
        val limits = FenceLimits(homeRadiusM = 500.0, ceilingM = 40f, floorM = 10f)
        val profile = ValidationProfile(2, Autopilot.ArduCopter, limits, vehicleHome = home)
        assertEquals(emptySet<PlanIssueKind>(), kinds(good(), profile))
        assertEquals(setOf(PlanIssueKind.OutsideHomeCircleFence), kinds(good(item(PlanCommand.Waypoint, far)), profile))
        assertEquals(setOf(PlanIssueKind.AboveFenceCeiling), kinds(good(item(PlanCommand.Waypoint, near, altM = 60f)), profile))
        assertEquals(setOf(PlanIssueKind.BelowFenceFloor), kinds(good(item(PlanCommand.Waypoint, near, altM = 5f)), profile))
        // The plan's own home wins over the vehicle's.
        val homedFar = good(item(PlanCommand.Waypoint, far)).copy(home = PlanHome(far, null))
        assertTrue(PlanIssueKind.OutsideHomeCircleFence in kinds(homedFar, profile))
        assertTrue(PlanIssueKind.OutsideHomeCircleFence.blocking && PlanIssueKind.AboveFenceCeiling.blocking && PlanIssueKind.BelowFenceFloor.blocking)
    }

    @Test
    fun `fence limits come only from enabled fences and set type bits`() {
        val ap = mapOf("FENCE_ENABLE" to 1.0, "FENCE_TYPE" to 3.0, "FENCE_RADIUS" to 300.0, "FENCE_ALT_MAX" to 100.0, "FENCE_ALT_MIN" to 5.0)
        assertEquals(FenceLimits(homeRadiusM = 300.0, ceilingM = 100f), FenceLimits.fromParams(ap, Autopilot.ArduCopter))
        assertEquals(FenceLimits(homeRadiusM = 300.0, ceilingM = 100f, floorM = 5f), FenceLimits.fromParams(ap + ("FENCE_TYPE" to 11.0), Autopilot.ArduPilotOther))
        assertEquals(null, FenceLimits.fromParams(ap + ("FENCE_ENABLE" to 0.0), Autopilot.ArduCopter))
        assertEquals(null, FenceLimits.fromParams(emptyMap(), Autopilot.ArduCopter))
        val px4 = mapOf("GF_ACTION" to 1.0, "GF_MAX_HOR_DIST" to 0.0, "GF_MAX_VER_DIST" to 120.0)
        assertEquals(FenceLimits(ceilingM = 120f), FenceLimits.fromParams(px4, Autopilot.Px4))
        assertEquals(null, FenceLimits.fromParams(px4 + ("GF_ACTION" to 0.0), Autopilot.Px4))
        assertEquals(null, FenceLimits.fromParams(ap, Autopilot.Unknown))
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
