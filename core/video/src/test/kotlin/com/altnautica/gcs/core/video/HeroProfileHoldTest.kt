package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.agent.model.VideoProfileLease
import com.altnautica.gcs.core.agent.model.VideoProfileState
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class HeroProfileHoldTest {

    private val drone = DeviceId("drone-1")
    private val url = "http://drone.local:8080/"
    private val holder = "android:0123456789abcdef"
    private val lease = "POST hero $holder 30"

    /** An agent that records each call as a line; queued errors answer the next calls of that kind. */
    private class FakeAgent(
        var nodeProfile: String = "drone",
        var videoProfile: String? = "thumbnail",
        val leases: Boolean = true,
    ) : VideoProfileAgent {
        val calls = mutableListOf<String>()
        val statusErrors = ArrayDeque<AgentError>()
        val setErrors = ArrayDeque<AgentError>()

        override suspend fun statusFull(): AgentResult<StatusFull> {
            calls += "status"
            statusErrors.removeFirstOrNull()?.let { return AgentResult.Err(it) }
            return AgentResult.Ok(StatusFull(profile = nodeProfile, telemetry = TelemetrySnapshot(videoProfile = videoProfile)))
        }

        /** How long each POST takes to answer, in virtual ms. */
        var setLatencyMs = 0L

        /** Virtual time at which each POST started. */
        val setStartedAt = mutableListOf<Long>()

        override suspend fun setVideoProfile(profile: String, holder: String?, leaseS: Int?): AgentResult<VideoProfileState> {
            calls += listOfNotNull("POST", profile, holder, leaseS?.toString()).joinToString(" ")
            setStartedAt += currentCoroutineContext()[TestCoroutineScheduler]!!.currentTime
            if (setLatencyMs > 0) delay(setLatencyMs)
            setErrors.removeFirstOrNull()?.let { return AgentResult.Err(it) }
            return AgentResult.Ok(state(profile, holder))
        }

        override suspend fun releaseVideoProfile(holder: String): AgentResult<VideoProfileState> {
            calls += "DELETE $holder"
            return AgentResult.Ok(state("thumbnail", null))
        }

        private fun state(profile: String, holder: String?) = VideoProfileState(
            profile = profile,
            leases = if (!leases) null else listOfNotNull(holder?.let { VideoProfileLease(it, 30.0) }),
        )
    }

    private fun TestScope.hold(agent: FakeAgent) = HeroProfileHold(backgroundScope, holder) { agent }

    private val tooMany = AgentError.Server(429, "too many requests")
    private val starting = AgentError.Server(503, "video service starting")
    private val timedOut = AgentError.Network(SocketTimeoutException("timeout"))

    @Test
    fun `acquiring promotes the drone with a lease under the app's holder id`() = runTest {
        val agent = FakeAgent()
        hold(agent).acquire(drone, url)
        runCurrent()
        assertEquals(listOf("status", lease), agent.calls)
    }

    @Test
    fun `the lease is renewed every 10 s while held`() = runTest {
        val agent = FakeAgent()
        hold(agent).acquire(drone, url)
        runCurrent()
        advanceTimeBy(9_999)
        assertEquals("no renewal before a third of the lease", 1, agent.calls.count { it == lease })
        advanceTimeBy(2)
        assertEquals("renewed at a third of the lease", 2, agent.calls.count { it == lease })
        advanceTimeBy(10_000)
        assertEquals(3, agent.calls.count { it == lease })
    }

    @Test
    fun `renewals keep a fixed cadence from each request's start, however slow the answer`() = runTest {
        val agent = FakeAgent()
        hold(agent).acquire(drone, url)
        runCurrent()
        agent.setLatencyMs = 6_000
        advanceTimeBy(40_001)
        assertEquals("renewals start at 10, 20, 30 and 40 s", listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L), agent.setStartedAt)
    }

    @Test
    fun `a failed or timed-out renewal is retried within 2 s, then with backoff`() = runTest {
        val agent = FakeAgent()
        hold(agent).acquire(drone, url)
        runCurrent()
        agent.setErrors += listOf(timedOut, starting)
        advanceTimeBy(18_001)
        assertEquals(
            "renewal at 10 s fails, retried at 12 s, fails, retried at 16 s and accepted",
            listOf(0L, 10_000L, 12_000L, 16_000L),
            agent.setStartedAt,
        )
        advanceTimeBy(2_000)
        assertEquals("the schedule carries on at 20 s", 20_000L, agent.setStartedAt.last())
    }

    @Test
    fun `the lease is released 5 s after the last holder goes, and renewals stop`() = runTest {
        val agent = FakeAgent()
        val release = hold(agent).acquire(drone, url)
        runCurrent()
        release()
        advanceTimeBy(HeroProfileHold.RELEASE_GRACE_MS - 1)
        assertEquals("nothing released inside the grace", listOf("status", lease), agent.calls)
        advanceTimeBy(2)
        assertEquals(listOf("status", lease, "DELETE $holder"), agent.calls)
        advanceTimeBy(60_000)
        assertEquals("no renewal after the release", listOf("status", lease, "DELETE $holder"), agent.calls)
    }

    @Test
    fun `viewers of one agent share one lease`() = runTest {
        val agent = FakeAgent()
        val hold = hold(agent)
        val first = hold.acquire(drone, url)
        val second = hold.acquire(drone, url)
        runCurrent()
        assertEquals("two holds, one promotion", listOf("status", lease), agent.calls)
        first()
        first()
        advanceTimeBy(10_000)
        assertEquals("one viewer still holds it", 0, agent.calls.count { it.startsWith("DELETE") })
        second()
        advanceTimeBy(HeroProfileHold.RELEASE_GRACE_MS + 1)
        assertEquals("one release", 1, agent.calls.count { it.startsWith("DELETE") })
        assertEquals(1, agent.calls.count { it == "status" })
    }

    @Test
    fun `re-acquiring inside the grace keeps the lease without a second promotion`() = runTest {
        val agent = FakeAgent()
        val hold = hold(agent)
        hold.acquire(drone, url)()
        runCurrent()
        advanceTimeBy(HeroProfileHold.RELEASE_GRACE_MS - 1_000)
        hold.acquire(drone, url)
        advanceTimeBy(HeroProfileHold.RELEASE_GRACE_MS * 2)
        assertEquals("one promotion, then only the renewal at 10 s", listOf("status", lease, lease), agent.calls)
    }

    @Test
    fun `a ground station is never promoted`() = runTest {
        val agent = FakeAgent(nodeProfile = "ground-station", videoProfile = "thumbnail")
        hold(agent).acquire(drone, url)()
        advanceTimeBy(60_000)
        assertEquals(listOf("status"), agent.calls)
    }

    @Test
    fun `an agent that does not report a video profile is left alone`() = runTest {
        val agent = FakeAgent(videoProfile = null)
        hold(agent).acquire(drone, url)()
        advanceTimeBy(60_000)
        assertEquals(listOf("status"), agent.calls)
    }

    @Test
    fun `a rate-limited promotion is retried after 1, 2 and 4 s`() = runTest {
        val agent = FakeAgent()
        agent.statusErrors += tooMany
        agent.setErrors += listOf(tooMany, tooMany)
        hold(agent).acquire(drone, url)
        runCurrent()
        assertEquals(listOf("status"), agent.calls)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("first retry after 1 s", listOf("status", "status", lease), agent.calls)
        advanceTimeBy(1_999)
        assertEquals("second retry not before 2 s", 3, agent.calls.size)
        advanceTimeBy(2)
        assertEquals(5, agent.calls.size)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("third retry after 4 s takes the lease", listOf("status", "status", lease, "status", lease, "status", lease), agent.calls)
        advanceTimeBy(HeroProfileHold.RENEW_MS)
        assertEquals("the won lease renews", lease, agent.calls.last())
    }

    @Test
    fun `failed promotions keep retrying every 15 s while held and stop once the hold is dropped`() = runTest {
        val agent = FakeAgent()
        repeat(100) { agent.statusErrors += tooMany }
        hold(agent).acquire(drone, url)
        advanceTimeBy(60_000)
        assertEquals("tries at 0, 1, 3, 7, 15, 30 and 45 s", 7, agent.calls.size)

        val released = FakeAgent()
        repeat(100) { released.statusErrors += tooMany }
        hold(released).acquire(drone, url)()
        advanceTimeBy(60_000)
        assertEquals("retried at 1 and 3 s inside the grace, then dropped at 5 s", 3, released.calls.size)
    }

    @Test
    fun `a failing promotion resumes when the view returns inside the grace`() = runTest {
        val agent = FakeAgent()
        repeat(2) { agent.statusErrors += starting }
        val hold = hold(agent)
        val first = hold.acquire(drone, url)
        runCurrent()
        advanceTimeBy(500)
        first()
        advanceTimeBy(1_000)
        assertEquals("the retry at 1 s ran while nobody held the drone", listOf("status", "status"), agent.calls)
        hold.acquire(drone, url)
        advanceTimeBy(2_000)
        assertEquals("the retry at 3 s takes the lease", listOf("status", "status", "status", lease), agent.calls)
        advanceTimeBy(HeroProfileHold.RENEW_MS)
        assertEquals("the lease is taken once, then renewed", 2, agent.calls.count { it == lease })
        assertEquals(0, agent.calls.count { it.startsWith("DELETE") })
    }

    @Test
    fun `a promotion refused while the video service starts, or timed out, is retried`() = runTest {
        val agent = FakeAgent()
        agent.statusErrors += starting
        agent.setErrors += timedOut
        hold(agent).acquire(drone, url)
        runCurrent()
        advanceTimeBy(3_001)
        assertEquals(
            "503 retried after 1 s, the timed-out POST after 2 s more",
            listOf("status", "status", lease, "status", lease),
            agent.calls,
        )
        advanceTimeBy(HeroProfileHold.RENEW_MS)
        assertEquals("the won lease renews 10 s after it was taken", 3, agent.calls.count { it == lease })
    }

    @Test
    fun `a refusal of the caller or the route is not retried`() = runTest {
        val agent = FakeAgent()
        agent.statusErrors += AgentError.Unauthorized
        hold(agent).acquire(drone, url)
        advanceTimeBy(60_000)
        assertEquals(listOf("status"), agent.calls)

        val noRoute = FakeAgent()
        noRoute.setErrors += AgentError.FeatureAbsent
        hold(noRoute).acquire(drone, url)
        advanceTimeBy(60_000)
        assertEquals(listOf("status", lease), noRoute.calls)
    }

    @Test
    fun `a pre-lease agent gets a plain set and is demoted only when the hold promoted it`() = runTest {
        val agent = FakeAgent(leases = false)
        hold(agent).acquire(drone, url)()
        advanceTimeBy(60_000)
        assertEquals("promoted from thumbnail, demoted with a plain set, never renewed", listOf("status", lease, "POST thumbnail"), agent.calls)

        val alreadyHero = FakeAgent(videoProfile = "hero", leases = false)
        hold(alreadyHero).acquire(drone, url)()
        advanceTimeBy(60_000)
        assertEquals("a drone already on hero is not demoted", listOf("status", lease), alreadyHero.calls)
    }
}
