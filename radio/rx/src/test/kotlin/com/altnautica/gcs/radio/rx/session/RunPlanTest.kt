package com.altnautica.gcs.radio.rx.session

import com.altnautica.gcs.core.link.fleet.BoundFleet
import com.altnautica.gcs.core.link.fleet.FleetSlot
import com.altnautica.gcs.radio.rx.link.LinkParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunPlanTest {
    private fun fleet(slots: List<FleetSlot>) =
        BoundFleet(ByteArray(64), ByteArray(32), fleetId = 1, channel = 149, keyProven = false, keyFingerprint = "f", slots = slots)

    private fun ch(slot: Int, port: Int) = LinkParams.channelId(LinkParams.linkId(1, slot), port)

    @Test
    fun `a just-bound fleet listens on every drone slot and the ground link`() {
        // Right after a bind the fleet lists no drone: the drone is found only by
        // its beacon, on whatever slot it transmits under. A radio opened on the
        // bundle's slot list would hear nothing and the drone would never enrol.
        val plan = RunPlan.of(fleet(emptyList()))
        assertTrue(plan.bundle.links().isEmpty())
        val rx = plan.rxChannels()
        assertEquals(LinkParams.FLEET_MAX_SLOTS * 3 + 1, rx.size)
        for (port in 0..2) assertTrue(ch(5, port) in rx)
        assertTrue("other grounds' beacons", ch(LinkParams.SLOT_GROUND, LinkParams.PORT_CONTROL) in rx)
    }

    @Test
    fun `the phone transmits the ground's control plane and aux uplink`() {
        val plan = RunPlan.of(fleet(listOf(FleetSlot(1, "drone-0001", 0))))
        assertEquals(
            listOf(ch(LinkParams.SLOT_GROUND, LinkParams.PORT_CONTROL), ch(LinkParams.SLOT_GROUND, LinkParams.PORT_AUX_UP)),
            plan.txChannels(),
        )
        assertTrue(plan.ownsFleet)
    }
}
