package com.altnautica.gcs.feature.home

import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSurfacesTest {

    private fun card(
        profile: NodeProfile,
        reaches: List<Reach> = listOf(Reach.Lan),
        liveness: Liveness = Liveness.Live,
        carried: DeviceId? = null,
    ) = HomeViewModel.card(
        NodeSummary(DeviceId("n"), "n", profile, reaches, liveness, null, FcVariant.Unknown, carried, null),
        NodeVitals.UNKNOWN,
    )

    private fun List<SurfaceCard>.of(surface: HomeSurface) = single { it.surface == surface }

    private val all = HomeSurface.entries

    @Test
    fun liveDroneEnablesEverySurfaceInOrder() {
        val cards = surfaces(card(NodeProfile.Drone))
        assertEquals(all, cards.map { it.surface })
        assertTrue(cards.all { it.enabled && it.reason == null })
    }

    @Test
    fun visorAndWorkstationHideFlightControllerSurfaces() {
        for (profile in listOf(NodeProfile.Visor, NodeProfile.Workstation)) {
            val cards = surfaces(card(profile))
            assertEquals(
                listOf(HomeSurface.Plan, HomeSurface.Vehicle, HomeSurface.Logs, HomeSurface.Settings),
                cards.map { it.surface },
            )
            assertTrue(cards.all { it.enabled })
        }
    }

    @Test
    fun groundStationWithoutCarriedDroneCannotFlyOrConfigure() {
        val cards = surfaces(card(NodeProfile.GroundStation))
        assertFalse(cards.of(HomeSurface.Fly).enabled)
        assertEquals(R.string.home_reason_no_carried, cards.of(HomeSurface.Fly).reason)
        assertFalse(cards.of(HomeSurface.Setup).enabled)
        assertEquals(R.string.home_reason_no_fc, cards.of(HomeSurface.Setup).reason)
        assertEquals(R.string.home_reason_no_fc, cards.of(HomeSurface.Parameters).reason)
        assertTrue(cards.of(HomeSurface.Vehicle).enabled)
    }

    @Test
    fun groundStationCarryingADroneCanFly() {
        val cards = surfaces(card(NodeProfile.GroundStation, carried = DeviceId("d")))
        assertTrue(cards.of(HomeSurface.Fly).enabled)
        assertNull(cards.of(HomeSurface.Fly).reason)
    }

    @Test
    fun directFlightControllerCanBeConfigured() {
        val cards = surfaces(card(NodeProfile.Unknown, reaches = listOf(Reach.DirectFc)))
        assertTrue(cards.of(HomeSurface.Fly).enabled)
        assertTrue(cards.of(HomeSurface.Setup).enabled)
        assertTrue(cards.of(HomeSurface.Parameters).enabled)
    }

    @Test
    fun unknownNodeWithoutFlightControllerSaysSo() {
        val cards = surfaces(card(NodeProfile.Unknown))
        assertEquals(R.string.home_reason_no_fc, cards.of(HomeSurface.Fly).reason)
        assertEquals(R.string.home_reason_no_fc, cards.of(HomeSurface.Setup).reason)
    }

    @Test
    fun offlineDroneCannotFly() {
        val cards = surfaces(card(NodeProfile.Drone, reaches = listOf(Reach.Blocked(BlockReason.Offline)), liveness = Liveness.Offline))
        assertFalse(cards.of(HomeSurface.Fly).enabled)
        assertEquals(R.string.home_reason_offline, cards.of(HomeSurface.Fly).reason)
        assertTrue(cards.of(HomeSurface.Setup).enabled)
    }

    @Test
    fun noVehicleLeavesOnlyAppWideSurfaces() {
        val cards = surfaces(null)
        assertEquals(all, cards.map { it.surface })
        for (s in listOf(HomeSurface.Fly, HomeSurface.Setup, HomeSurface.Parameters, HomeSurface.Vehicle)) {
            assertFalse(cards.of(s).enabled)
            assertEquals(R.string.home_reason_no_vehicle, cards.of(s).reason)
        }
        for (s in listOf(HomeSurface.Plan, HomeSurface.Logs, HomeSurface.Settings)) assertTrue(cards.of(s).enabled)
    }

    @Test
    fun surfacesOpenOnTheSelectedVehicleWhereTheyNeedOne() {
        val id = DeviceId("v")
        assertEquals(id, HomeSurface.Fly.nodeArgument(id))
        assertEquals(id, HomeSurface.Plan.nodeArgument(id))
        assertNull(HomeSurface.Plan.nodeArgument(null))
        assertNull(HomeSurface.Logs.nodeArgument(id))
        assertNull(HomeSurface.Settings.nodeArgument(id))
    }
}
