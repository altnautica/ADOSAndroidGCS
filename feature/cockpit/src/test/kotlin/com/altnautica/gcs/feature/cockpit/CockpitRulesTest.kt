package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.dp
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
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

/** Rules the cockpit draws from the spec: light bar, chip, launch arguments, grid fit and designation. */
class CockpitRulesTest {

    private fun light(owner: PilotOwner, mission: MissionState? = null, mode: String = "GUIDED", failsafe: Boolean = false, lowBattery: Boolean = false): LightbarState {
        val p = pilotState(owner, fcModeName = mode, mission = mission)
        return lightbarState(ControllerContext.of(p, armed = p.armed, modeName = null, pickerOpen = false, lowBattery = lowBattery, failsafe = failsafe))
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

    private val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens)

    @Test
    fun `chip text follows the flight-state rules, first match wins`() {
        val now = 10_000L
        val pilot = chips.resolve(pilotState(PilotOwner.Pilot, receivedAtMs = now), ChipContext(now))
        assertEquals("PILOT · NORMAL", pilot.primary.text)
        assertTrue(pilot.secondary.any { it.text == "ADOS PILOT" })

        val stale = chips.resolve(pilotState(PilotOwner.Pilot, receivedAtMs = now - 2_500), ChipContext(now))
        assertEquals("LINK", stale.primary.text)
        assertTrue(stale.dim)

        val standby = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "Loiter", receivedAtMs = now), ChipContext(now))
        assertEquals("FC · LOITER · ADOS STANDING BY", standby.primary.text)
        assertEquals(ChipButton.TakeControl, standby.button)

        val manual = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "LOITER", controlMode = ControlMode.FcManual, receivedAtMs = now), ChipContext(now))
        assertEquals("FC · LOITER", manual.primary.text)

        val rth = chips.resolve(pilotState(PilotOwner.FcMode, fcModeName = "RTL", fcMode = 6, receivedAtMs = now), ChipContext(now, homeDistM = 42f))
        assertEquals("RTH · 42 m", rth.primary.text)

        val paused = chips.resolve(pilotState(PilotOwner.MissionFc, fcModeName = "AUTO", mission = MissionState.Paused, receivedAtMs = now), ChipContext(now))
        assertEquals("MISSION PAUSED · WP 2/6", paused.primary.text)
        assertEquals(ChipButton.Resume, paused.button)
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
    fun `on a short landscape phone the left column clears the map card and the right column clears the picker chip`() {
        // A 2400 x 1080 px phone at 420 dpi is 914 dp wide (expanded) and about 363 dp tall below the top bar.
        val sizes = gridSizes(WidthClass.Expanded)
        val h = 363.dp
        val fit = gridFit(h, sizes, record = false)
        val card = fit.card!!
        // The left column, centred in the space above the card, fits in it.
        assertTrue(leftColumnHeight(sizes) <= h - GridEdge - fit.leftBottom)
        assertTrue(card.second < 180.dp)
        assertEquals(280f / 180f, card.first / card.second, 0.01f)
        assertTrue(rightColumnHeight(sizes, record = false, cameraSettings = fit.cameraSettingsInColumn) <= h - 8.dp - fit.rightBottom)

        // With record offered, camera settings move to a long-press and the small buttons shrink to 48 dp.
        val tight = gridFit(h, sizes, record = true)
        assertTrue(!tight.cameraSettingsInColumn)
        assertEquals(48.dp, tight.rightButton)
        assertTrue(rightColumnHeight(sizes, record = true, cameraSettings = false, button = tight.rightButton) <= h - 8.dp - tight.rightBottom)

        // A tall tablet keeps the spec card and every button.
        val tall = gridFit(700.dp, sizes, record = true)
        assertEquals(280.dp to 180.dp, tall.card)
        assertTrue(tall.cameraSettingsInColumn)
    }

    @Test
    fun `on a medium window too short to stack, the map card keeps its spec size beside the left column`() {
        // 2400 x 1080 px at density 560 is 685 dp wide (medium); about 265 dp of body below the top bar.
        val sizes = gridSizes(WidthClass.Medium)
        val fit = gridFit(265.dp, sizes, record = false)
        assertEquals(200.dp to 140.dp, fit.card)
        assertTrue(fit.cardBesideColumn)
        assertEquals(GridEdge, fit.leftBottom)
        // Tall enough to stack: the card sits below the column as before.
        val tall = gridFit(420.dp, sizes, record = false)
        assertEquals(200.dp to 140.dp, tall.card)
        assertTrue(!tall.cardBesideColumn)
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
}
