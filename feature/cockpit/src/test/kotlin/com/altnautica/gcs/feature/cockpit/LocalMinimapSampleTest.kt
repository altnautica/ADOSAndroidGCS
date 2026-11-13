package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.hud.LocalMapInputs
import com.altnautica.gcs.feature.cockpit.hud.LocalPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The local minimap draws the pilot's planned path and goal from a sample taken on the flight
 * screen's beat: pilot frames at the video rate change what it draws at most once every
 * [MINIMAP_BEATS] beats, and a frame that changes nothing it shows changes nothing at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalMinimapSampleTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A go-to pilot frame received now, planning a straight path north to [goalN] metres. */
    private fun TestScope.pilotFrame(vm: CockpitViewModel, goalN: Float) {
        val t = testScheduler.currentTime
        vm.autonomy.feed.onNavText(
            """{"kind":"pilot","ts_ms":$t,"data":{"owner":"goto","plan_ned":[[0,0],[$goalN,0]],"goal_ned":[$goalN,0,-5],"avoid_state":"clear"}}""",
            t,
        )
    }

    /** One replacement of the sample: on beat [beat], the map's new [inputs]. */
    private data class Replaced(val beat: Long, val inputs: LocalMapInputs)

    /**
     * Runs [ms] of flight in 10 ms steps: [frame] every 30 ms (about 33 Hz) and a beat every
     * [RedrawBeat.BEAT_MS], numbered on from [firstBeat]. Returns the beat reached and every
     * replacement of the sample, failing if the sample changed anywhere but a minimap beat.
     */
    private fun TestScope.fly(sample: LocalMinimapSample, ms: Long, firstBeat: Long, frame: (Int) -> Unit): Pair<Long, List<Replaced>> {
        var beat = firstBeat
        var frames = 0
        val replaced = mutableListOf<Replaced>()
        var last = sample.inputs
        for (step in 1..ms / STEP_MS) {
            advanceTimeBy(STEP_MS)
            runCurrent()
            val t = step * STEP_MS
            if (t % FRAME_MS == 0L) frame(frames++)
            val onBeat = t % RedrawBeat.BEAT_MS == 0L
            if (onBeat) sample.onBeat(++beat)
            if (sample.inputs !== last) {
                assertTrue("the sample changed off a minimap beat, at $t ms", onBeat && beat % MINIMAP_BEATS == 0L)
                replaced += Replaced(beat, sample.inputs)
                last = sample.inputs
            }
        }
        return beat to replaced
    }

    @Test
    fun `a plan moving at the video rate changes the map only on every second beat, as it stands then`() = runTest(dispatcher) {
        val vm = testCockpitVm(FakeVehicle())
        val sample = LocalMinimapSample(vm.autonomy) { null }
        var latestGoal = 0f
        val (_, replaced) = fly(sample, 1_000L, firstBeat = 0L) { i ->
            latestGoal = 2f + i * 0.1f
            pilotFrame(vm, latestGoal)
        }
        assertEquals("33 plan changes in a second make 5 samples", listOf(2L, 4L, 6L, 8L, 10L), replaced.map { it.beat })
        val plan = sample.inputs.plan
        assertNotNull(plan)
        assertEquals("the sample holds the frame current on its beat", latestGoal to 0f, plan!!.goal)
        assertEquals(listOf(0f to 0f, latestGoal to 0f), plan.points)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `an unchanged plan keeps its sample until the age beat, and a stalled pilot clears it on the next sample`() = runTest(dispatcher) {
        val vm = testCockpitVm(FakeVehicle())
        val sample = LocalMinimapSample(vm.autonomy) { null }
        // Frames with the same plan, each newer than the last: only their receive time differs.
        var lastFrameAt = 0L
        val (beat, replaced) = fly(sample, 1_000L, firstBeat = 0L) {
            pilotFrame(vm, 5f)
            lastFrameAt = testScheduler.currentTime
        }
        assertEquals("the first sample, then one per 500 ms age beat on an even beat", listOf(2L, 10L), replaced.map { it.beat })
        assertEquals(LocalPlan(listOf(0f to 0f, 5f to 0f), 5f to 0f, blocked = false), sample.inputs.plan)

        // The pilot stalls: its plan hides on the first sample after its last frame turns stale.
        val (_, afterStall) = fly(sample, PilotState.STALE_MS + 500L, firstBeat = beat) {}
        val cleared = afterStall.first { it.inputs.plan == null }
        assertTrue("the plan holds while the frame is fresh", afterStall.takeWhile { it !== cleared }.all { it.inputs.plan != null })
        val staleFor = cleared.inputs.nowMs - lastFrameAt
        assertTrue("cleared within one sample of going stale: $staleFor ms", staleFor in PilotState.STALE_MS..PilotState.STALE_MS + MINIMAP_BEATS * RedrawBeat.BEAT_MS)
        assertEquals(null, sample.inputs.plan)
        vm.viewModelScope.cancel()
    }

    private companion object {
        const val STEP_MS = 10L
        const val FRAME_MS = 30L
    }
}
