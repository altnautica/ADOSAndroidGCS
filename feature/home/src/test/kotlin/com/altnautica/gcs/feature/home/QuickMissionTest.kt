package com.altnautica.gcs.feature.home

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.MissionStatus
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.feature.home.quick.quickMission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickMissionTest {
    private val drone = DeviceId("drone-1")
    private val other = DeviceId("drone-2")

    private fun mission(state: MissionState, seq: Int = 3) = MissionStatus(state, seq, 9, null, 1L, false, null, false, null)

    private fun cockpit(node: DeviceId, m: MissionStatus?) = CockpitSummary(node, 0, 0, 0, null, false, m)

    @Test
    fun `with no cockpit open the card reads the vehicle's own mission`() {
        val flying = mission(MissionState.Active)
        assertEquals(flying, quickMission(drone, cockpit = null, mirror = drone to flying))
    }

    @Test
    fun `another vehicle's cockpit never speaks for the selected vehicle`() {
        val theirs = mission(MissionState.Paused)
        val ours = mission(MissionState.Active, seq = 5)
        assertEquals(ours, quickMission(drone, cockpit(other, theirs), mirror = drone to ours))
        assertNull("nothing known about the selected vehicle is unknown, not 'no mission'", quickMission(drone, cockpit(other, theirs), mirror = null))
    }

    @Test
    fun `the selected vehicle's open cockpit is preferred, and a mirror for another vehicle is ignored`() {
        val live = mission(MissionState.Active, seq = 6)
        assertEquals(live, quickMission(drone, cockpit(drone, live), mirror = drone to mission(MissionState.Active, seq = 5)))
        assertNull(quickMission(drone, cockpit = null, mirror = other to live))
    }
}
