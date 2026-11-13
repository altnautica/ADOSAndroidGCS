package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PilotStickStreamerTest {

    private class Sink : StickSink {
        var sessions = 0
        var releases = 0
        val frames = ArrayList<PilotInput>()
        override fun beginSession() {
            sessions++
        }
        override fun input(frame: PilotInput) {
            frames += frame
        }
        override fun release() {
            releases++
        }
    }

    private val held = GamepadAxes(0f, 0f, 0f, -1f)
    private var pad = PadSnapshot(3, 0x054c, 0x05c4, held, held, 0, 0L, emptyList())
    private val sink = Sink()
    private val sinkFlow = MutableStateFlow<StickSink?>(sink)

    private fun TestScope.streamer(scope: CoroutineScope = backgroundScope) = PilotStickStreamer(
        scope,
        StandardTestDispatcher(testScheduler),
        { testScheduler.currentTime },
        { pad },
        MutableStateFlow(StickConditioning()),
        sinkFlow,
    )

    @Test
    fun `a stick held still keeps streaming at 50 Hz in one session`() = runTest {
        val s = streamer()
        s.start()
        runCurrent()
        advanceTimeBy(1_000)
        // Android sends no event for a stick held still: the last position must keep streaming.
        assertTrue("${sink.frames.size} frames in 1 s", sink.frames.size in 49..51)
        assertTrue(sink.frames.all { it.pitch == 1f || it.pitch == -1f })
        assertEquals(1, sink.sessions)
        assertEquals(0, sink.releases)
        assertTrue(s.streaming.value)
    }

    @Test
    fun `a disconnected pad releases once and a reconnect starts a new session`() = runTest {
        val s = streamer()
        s.start()
        advanceTimeBy(200)
        val connected = pad
        pad = PadSnapshot.Neutral
        advanceTimeBy(500)
        assertEquals(1, sink.releases)
        assertFalse(s.streaming.value)
        val sent = sink.frames.size
        advanceTimeBy(200)
        assertEquals(sent, sink.frames.size)

        pad = connected
        advanceTimeBy(100)
        assertEquals(2, sink.sessions)
        assertTrue(s.streaming.value)
    }

    @Test
    fun `leaving the pilot route and stopping the cockpit each release once`() = runTest {
        val s = streamer()
        s.start()
        advanceTimeBy(200)
        sinkFlow.value = null
        advanceTimeBy(300)
        assertEquals(1, sink.releases)

        sinkFlow.value = sink
        advanceTimeBy(100)
        s.stop()
        assertEquals(2, sink.releases)
        val sent = sink.frames.size
        advanceTimeBy(500)
        assertEquals(sent, sink.frames.size)
    }

    @Test
    fun `sticks keep their 20 ms cadence while the main thread is blocked`() = runTest {
        // The owner's scope runs on a "main" dispatcher whose scheduler never advances: a
        // main thread that is stuck for the whole second.
        val blockedMain = CoroutineScope(SupervisorJob() + StandardTestDispatcher(TestCoroutineScheduler()))
        val s = streamer(blockedMain)
        s.start()
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue("${sink.frames.size} frames in 1 s", sink.frames.size in 49..51)
        s.stop()
        blockedMain.cancel()
    }
}
