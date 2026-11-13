package com.altnautica.gcs.feature.cockpit.video

import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.agent.model.VideoProfileLease
import com.altnautica.gcs.core.agent.model.VideoProfileState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.HeroProfileHold
import com.altnautica.gcs.core.video.VideoProfileAgent
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.testDroneNode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The flight view holds the drone it plays on the full-rate profile, and lets go when it stops. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoControllerHeroHoldTest {

    private val holder = "android:0123456789abcdef"

    private class RecordingAgent : VideoProfileAgent {
        val calls = mutableListOf<String>()
        override suspend fun statusFull(): AgentResult<StatusFull> {
            calls += "status"
            return AgentResult.Ok(StatusFull(profile = "drone", telemetry = TelemetrySnapshot(videoProfile = "thumbnail")))
        }
        override suspend fun setVideoProfile(profile: String, holder: String?, leaseS: Int?): AgentResult<VideoProfileState> {
            calls += "POST $profile"
            return AgentResult.Ok(VideoProfileState(profile = profile, leases = listOfNotNull(holder?.let { VideoProfileLease(it, 30.0) })))
        }
        override suspend fun releaseVideoProfile(holder: String): AgentResult<VideoProfileState> {
            calls += "DELETE"
            return AgentResult.Ok(VideoProfileState(profile = "thumbnail", leases = emptyList()))
        }
    }

    /** A controller flying `drone-1` whose video candidates are [sources], every session in state [state]. */
    private fun TestScope.controller(
        sources: List<VideoSource>,
        state: MutableStateFlow<VideoState>,
        agent: RecordingAgent,
        asked: MutableList<DeviceId> = mutableListOf(),
    ): CockpitVideoController {
        val sessions = mockk<VideoSessionManager> {
            every { canPlay(any()) } returns true
            every { state(any()) } returns state
            every { running(any()) } returns null
        }
        return CockpitVideoController(
            backgroundScope,
            MutableStateFlow<NodeSummary?>(testDroneNode()),
            mockk<VideoSourceSelector> { every { candidates(any(), any(), any()) } returns sources },
            sessions,
            HeroProfileHold(backgroundScope, holder) { node -> asked += node; agent },
            mockk<AgentEndpoints> { coEvery { endpointFor(any()) } returns null },
            mockk(relaxed = true),
            MutableSharedFlow<CockpitEvent>(),
        )
    }

    private val droneWhep = VideoSource.AgentWhep(DeviceId("drone-1"), "http://drone.local:8080/", null)

    @Test
    fun `showing the flight view takes the hold and leaving it releases it`() = runTest {
        val agent = RecordingAgent()
        val asked = mutableListOf<DeviceId>()
        val state = MutableStateFlow<VideoState>(VideoState.Idle)
        val ctl = controller(listOf(droneWhep), state, agent, asked)
        runCurrent()
        assertEquals("nothing is held before the flight view shows", emptyList<String>(), agent.calls)

        ctl.setFlightViewShown(true)
        runCurrent()
        assertEquals("the drone is promoted as the view shows", listOf("status", "POST hero"), agent.calls)
        assertEquals(listOf(DeviceId("drone-1")), asked)

        advanceTimeBy(HeroProfileHold.RENEW_MS + 1)
        assertEquals("the lease renews while the view shows", listOf("status", "POST hero", "POST hero"), agent.calls)

        ctl.setFlightViewShown(false)
        advanceTimeBy(HeroProfileHold.RELEASE_GRACE_MS + 1)
        assertEquals("the lease is released after the view leaves", "DELETE", agent.calls.last())
        val settled = agent.calls.size
        advanceTimeBy(60_000)
        assertEquals("nothing more once released", settled, agent.calls.size)
    }

    @Test
    fun `stream failures, restarts and failover while the view shows keep the one lease`() = runTest {
        val agent = RecordingAgent()
        val state = MutableStateFlow<VideoState>(VideoState.Connecting)
        // A cloud fallback too, so a failed WHEP session fails over off the drone's own stream.
        val ctl = controller(listOf(droneWhep, VideoSource.CloudRelay(DeviceId("drone-1"))), state, agent)
        ctl.setFlightViewShown(true)
        runCurrent()
        // The hero switch respawns the encoder: the stream drops, the session restarts, and so on.
        val cycle = listOf(VideoState.Live(80), VideoState.Failed("ice"), VideoState.Idle, VideoState.Connecting)
        repeat(6) {
            for (s in cycle) {
                state.value = s
                advanceTimeBy(2_500)
            }
        }
        assertEquals("never released while the view shows", 0, agent.calls.count { it == "DELETE" })
        assertEquals("one promotion", 1, agent.calls.count { it == "status" })
        assertEquals("one POST, then a renewal at 15, 30 and 45 s only", 4, agent.calls.count { it == "POST hero" })
    }

    @Test
    fun `a drone reached only through the ground station carrying it takes no hold`() = runTest {
        val agent = RecordingAgent()
        val state = MutableStateFlow<VideoState>(VideoState.Live(80))
        controller(listOf(VideoSource.AgentWhep(DeviceId("ground-1"), "http://ground.local:8080/", null)), state, agent).setFlightViewShown(true)
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), agent.calls)
    }

    @Test
    fun `a cloud stream takes no hold`() = runTest {
        val agent = RecordingAgent()
        val state = MutableStateFlow<VideoState>(VideoState.Live(80))
        controller(listOf(VideoSource.CloudRelay(DeviceId("drone-1"))), state, agent).setFlightViewShown(true)
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), agent.calls)
    }
}
