package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.MissionStall
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.TtsManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stall, the blocked return and a skipped waypoint are spoken once each when they appear. */
@OptIn(ExperimentalCoroutinesApi::class)
class StallVoicePromptsTest {
    private val strings = testSpec.setupStrings

    @Test
    fun `a stall, a blocked return and a skipped waypoint each speak their spec line once`() = runTest(UnconfinedTestDispatcher()) {
        val spoken = mutableListOf<String>()
        val tts = mockk<TtsManager>(relaxed = true) { every { speak(capture(spoken), any()) } just runs }
        val announcer = AlertAnnouncer(testSpec, tts, mockk(relaxed = true))
        val base = pilotState(PilotOwner.Pilot)
        val pilot = MutableStateFlow<PilotState?>(base)
        backgroundScope.launchVoicePrompts(pilot, MutableStateFlow(CockpitSettings()), { strings }, announcer)

        val stall = MissionStall(MissionStallKind.Mission, 3, "3", 0L, 40f, 15f)
        pilot.value = base.copy(missionStall = stall)
        pilot.value = base.copy(missionStall = stall.copy(skipInS = 39f))
        val skipped = PilotBanner("pilot.banner.mission_wp_skipped", mapOf("wp" to "3"), 10L)
        pilot.value = base.copy(banner = skipped)
        pilot.value = base.copy(banner = skipped, receivedAtMs = 100L)
        pilot.value = base.copy(missionStall = stall.copy(kind = MissionStallKind.Return, seq = null, wpLabel = null))
        pilot.value = base.copy(missionStall = stall.copy(kind = MissionStallKind.Return, seq = null, wpLabel = null, skipInS = 30f))

        assertEquals(
            listOf(strings.format("voice.mission_stalled"), strings.format("voice.waypoint_skipped"), strings.format("voice.return_blocked")),
            spoken,
        )
        assertEquals(listOf("Mission stalled. Holding.", "Waypoint skipped", "Return blocked. Holding."), spoken)
    }

    @Test
    fun `nothing is spoken with voice prompts off`() = runTest(UnconfinedTestDispatcher()) {
        val spoken = mutableListOf<String>()
        val tts = mockk<TtsManager>(relaxed = true) { every { speak(capture(spoken), any()) } just runs }
        val pilot = MutableStateFlow<PilotState?>(pilotState(PilotOwner.Pilot))
        backgroundScope.launchVoicePrompts(pilot, MutableStateFlow(CockpitSettings(voicePrompts = false)), { strings }, AlertAnnouncer(testSpec, tts, mockk(relaxed = true)))
        pilot.value = pilotState(PilotOwner.Pilot).copy(missionStall = MissionStall(MissionStallKind.Mission, 3, "3", 0L, 40f, 15f))
        assertEquals(emptyList<String>(), spoken)
    }
}
