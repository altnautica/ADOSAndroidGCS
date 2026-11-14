package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.feature.cockpit.autonomy.Box
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.Detection
import com.altnautica.gcs.feature.cockpit.autonomy.DetectionBatch
import com.altnautica.gcs.feature.cockpit.chip.ChipButton
import com.altnautica.gcs.feature.cockpit.chip.ChipContext
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipResolver
import com.altnautica.gcs.feature.cockpit.launch.LaunchAfter
import com.altnautica.gcs.feature.cockpit.launch.LaunchDraft
import com.altnautica.gcs.feature.cockpit.launch.LaunchWhenDone
import com.altnautica.gcs.feature.cockpit.launch.NedPoint
import com.altnautica.gcs.feature.cockpit.launch.launchArgs
import com.altnautica.gcs.feature.cockpit.launch.launchGotoNed
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.LightbarState
import com.altnautica.gcs.feature.cockpit.pilot.lightbarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

/** Rules the cockpit draws from the spec: light bar, chip, launch arguments and designation. */
class CockpitRulesTest {

    private fun light(owner: PilotOwner, mission: MissionState? = null, mode: String = "GUIDED", failsafe: Boolean = false, lowBattery: Boolean = false): LightbarState {
        val p = pilotState(owner, fcModeName = mode, mission = mission)
        return lightbarState(ControllerContext.of(p, armed = p.armed == true, modeName = null, pickerOpen = false, lowBattery = lowBattery, failsafe = failsafe))
    }

    @Test
    fun `light bar colour follows the owner, with the spec's priority on top`() {
        assertEquals(LightbarState.Disarmed, light(PilotOwner.Idle))
        assertEquals(LightbarState.Pilot, light(PilotOwner.Pilot))
        assertEquals(LightbarState.Pilot, light(PilotOwner.Goto))
        assertEquals(LightbarState.Skill, light(PilotOwner.Skill))
        assertEquals(LightbarState.Mission, light(PilotOwner.MissionFc, MissionState.Active, mode = "AUTO"))
        assertEquals(LightbarState.ArmedIdle, light(PilotOwner.FcMode, mode = "LOITER"))
        // RTH outranks the mission, low battery outranks RTH, a failsafe outranks everything.
        assertEquals(LightbarState.Rth, light(PilotOwner.FcMode, MissionState.Active, mode = "RTL"))
        assertEquals(LightbarState.LowBattery, light(PilotOwner.FcMode, mode = "RTL", lowBattery = true))
        assertEquals(LightbarState.Failsafe, light(PilotOwner.Pilot, lowBattery = true, failsafe = true))
    }

    private val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens, testSpec.setupStrings.strings)

    @Test
    fun `a hold after a skill's lease is taken away reads Skill stopped`() {
        assertEquals("Skill stopped", chips.holdReasonLabel("lease-revoked"))
        assertEquals("Another skill has control", chips.holdReasonLabel("lease-busy"))
    }

    @Test
    fun `chip text follows the flight-state rules, first match wins`() {
        val now = 10_000L
        val pilot = chips.resolve(pilotState(PilotOwner.Pilot, receivedAtMs = now), ChipContext(now))
        assertEquals("Pilot · Normal", pilot.primary.text)
        assertTrue(pilot.secondary.any { it.text == "ADOS Pilot" })

        val stale = chips.resolve(pilotState(PilotOwner.Pilot, receivedAtMs = now - 2_500), ChipContext(now))
        assertEquals("Link", stale.primary.text)
        assertTrue(stale.dim)

        val standby = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "Loiter", receivedAtMs = now), ChipContext(now))
        assertEquals("FC · LOITER · ADOS standing by", standby.primary.text)
        assertEquals(ChipButton.TakeControl, standby.button)

        val manual = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "LOITER", controlMode = ControlMode.FcManual, receivedAtMs = now), ChipContext(now))
        assertEquals("FC · LOITER", manual.primary.text)

        val rth = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "RTL", fcMode = 6, receivedAtMs = now), ChipContext(now, homeDistM = 42f))
        assertEquals("RTH · 42 m", rth.primary.text)

        val paused = chips.resolve(pilotState(PilotOwner.MissionFc, fcModeName = "AUTO", mission = MissionState.Paused, receivedAtMs = now), ChipContext(now))
        assertEquals("Mission paused · WP 2/6", paused.primary.text)
        assertEquals(ChipButton.Resume, paused.button)
    }

    @Test
    fun `an FC mode the pilot has not heard reads unknown, never a default mode`() {
        val now = 10_000L
        val unheard = pilotState(PilotOwner.FcMode, fcModeName = null, fcMode = null, receivedAtMs = now)
        val chip = chips.resolve(unheard, ChipContext(now))
        assertEquals("FC · — · ADOS standing by", chip.primary.text)
        val ctx = ControllerContext.of(unheard, armed = null, modeName = null, pickerOpen = false, lowBattery = false, failsafe = false)
        assertEquals(false, ctx.rthActive)
        assertEquals(false, ctx.hover)
    }

    private fun skill(id: String, plugin: String): Skill = mockk {
        every { this@mockk.id } returns "$plugin:$id"
        every { source } returns SkillSource.Extension(plugin)
    }

    @Test
    fun `launch arguments carry the after-takeoff choice and refuse an incomplete one`() {
        val follow = skill("follow-me", "com.example.follow")
        val hold = launchArgs(LaunchDraft(altM = 5f), emptyList(), null)!!
        assertEquals(mapOf("after" to "hold", "alt_m" to 5f, "when_done" to null), hold)

        val sk = launchArgs(LaunchDraft(after = LaunchAfter.Skill, skillId = follow.id, whenDone = LaunchWhenDone.Rth), listOf(follow), null)!!
        assertEquals(mapOf("plugin_id" to "com.example.follow", "skill_id" to "follow-me"), sk["skill"])
        assertEquals("rth", sk["when_done"])
        assertNull(launchArgs(LaunchDraft(after = LaunchAfter.Skill, skillId = "missing"), listOf(follow), null))

        assertNull(launchArgs(LaunchDraft(after = LaunchAfter.Goto), emptyList(), null))
        val goto = launchArgs(LaunchDraft(after = LaunchAfter.Goto, altM = 4f), emptyList(), NedPoint(10f, -5f, -4f))!!
        assertEquals(mapOf("n" to 10f, "e" to -5f, "d" to -4f), goto["goto"])
    }

    @Test
    fun `a map point converts to the pilot's local frame from the reference position`() {
        // 0.001 deg north is 111.3 m; 0.001 deg east at 60 deg latitude is 55.7 m.
        val p = launchGotoNed(60.0, 10.0, 2f, 3f, 60.001, 10.001, 6f)
        assertEquals(2f + 111.3f, p.n, 0.2f)
        assertEquals(3f + 55.66f, p.e, 0.2f)
        assertEquals(-6f, p.d, 0f)
    }

    @Test
    fun `designate picks the detection under the centre reticle, else the nearest`() {
        fun det(x: Float, y: Float, w: Float, h: Float) = Detection(Box(x, y, w, h), "person", 0.9f, null, null)
        val big = det(500f, 200f, 400f, 400f)
        val small = det(600f, 300f, 100f, 200f)
        val far = det(10f, 10f, 20f, 20f)
        val batch = DetectionBatch("main", 1, 1280f, 720f, 0L, listOf(far, big, small))
        assertEquals(small, CockpitAutonomy.centreDetection(batch))
        val near = det(700f, 420f, 30f, 30f)
        assertEquals(near, CockpitAutonomy.centreDetection(batch.copy(items = listOf(far, near))))
        assertNull(CockpitAutonomy.centreDetection(batch.copy(items = emptyList())))
    }

    @Test
    fun `the goto distance is measured from the vehicle, not from the setpoint`() {
        val here = com.altnautica.gcs.core.model.LocalPosition(0f, 0f, -10f, 0f, 0f, 0f, 0L)
        // The planner puts the setpoint on the goal on a clear path; the chip must still read 50 m.
        assertEquals(50f, goalDistanceM(listOf(30f, 40f, -10f), here)!!, 0.01f)
        assertNull(goalDistanceM(listOf(30f, 40f, -10f), null))
    }
}
