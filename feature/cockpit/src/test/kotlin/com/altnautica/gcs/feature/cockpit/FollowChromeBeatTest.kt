package com.altnautica.gcs.feature.cockpit

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.autonomy.AutonomyParse
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.NavFrame
import com.altnautica.gcs.feature.cockpit.autonomy.Stamped
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The follow chrome reads the follow feed on the beat, not per frame. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowChromeBeatTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: FollowState = (
        AutonomyParse.navFrame(
            """{"kind":"follow","ts_ms":1,"data":{"phase":"following","track_id":12,"range_m":10.2,"range_sigma_m":0.3}}""",
            0L,
        ) as NavFrame.Follow
        ).follow

    // Fails on the base commit: FollowChrome collected the follow feed directly, so each
    // 20 Hz frame (a new stamp) recomposed it.
    @Test
    fun `20 Hz follow frames recompose the reader at most once a beat`() {
        val follow = MutableStateFlow<Stamped<FollowState>?>(null)
        var compositions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            rememberFollowOnBeat(follow) { compose.mainClock.currentTime }
            SideEffect { compositions++ }
        }
        compose.mainClock.advanceTimeByFrame()
        val start = compositions
        // One second of follow frames at 20 Hz, each with a new range and stamp.
        repeat(20) { i ->
            follow.value = Stamped(base.copy(rangeM = 10f + i * 0.1f), compose.mainClock.currentTime)
            compose.mainClock.advanceTimeBy(50)
        }
        val recompositions = compositions - start
        // A 1 s window holds up to 11 beats, plus the sample that picks up the first frame;
        // reading every frame would recompose 20 times.
        assertTrue("recompositions in 1 s: $recompositions", recompositions in 1..(1_000 / RedrawBeat.BEAT_MS + 2).toInt())
    }
}
