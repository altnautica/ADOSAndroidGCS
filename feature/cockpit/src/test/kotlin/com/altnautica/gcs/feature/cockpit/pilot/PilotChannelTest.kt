package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PilotChannelTest {
    private fun kotlinx.coroutines.test.TestScope.channel(): PilotChannel {
        val ch = PilotChannel(backgroundScope, MonotonicClock { testScheduler.currentTime }) { emptyFlow() }
        ch.onText("""{"kind":"keepalive","ts_ms":1}""")
        return ch
    }

    @Test
    fun `an action waits for the pilot's answer to its own id`() = runTest {
        val ch = channel()
        val pending = async { ch.action(PilotAction(PilotAction.TAKEOFF)) }
        runCurrent()
        // Another surface's answer and an answer to another id are not this action's.
        ch.onText("""{"kind":"action_result","name":"takeoff","id":"99","ok":true}""")
        runCurrent()
        ch.onText("""{"kind":"action_result","name":"takeoff","id":"1","ok":false,"err":"readiness red"}""")
        assertEquals(PilotActionResult.Refused("readiness red"), pending.await())
    }

    @Test
    fun `an accepted action reads accepted`() = runTest {
        val ch = channel()
        val pending = async { ch.action(PilotAction(PilotAction.LAND)) }
        runCurrent()
        ch.onText("""{"kind":"action_result","name":"land","id":"1","ok":true}""")
        assertEquals(PilotActionResult.Accepted, pending.await())
    }

    @Test
    fun `no answer within the window reads no answer`() = runTest {
        val ch = channel()
        val pending = async { ch.action(PilotAction(PilotAction.RTH)) }
        runCurrent()
        advanceTimeBy(PilotChannel.ACTION_ANSWER_MS + 1)
        assertEquals(PilotActionResult.NoAnswer, pending.await())
    }

    @Test
    fun `a down channel sends nothing`() = runTest {
        val ch = PilotChannel(backgroundScope, MonotonicClock { testScheduler.currentTime }) { emptyFlow() }
        assertEquals(PilotActionResult.Unavailable, ch.action(PilotAction(PilotAction.PAUSE)))
    }

    @Test
    fun `keepalives keep the channel up but never make the pilot state fresh`() = runTest {
        val ch = channel()
        assertTrue(ch.connected.value)
        assertFalse("no state yet", ch.stateFresh)
        ch.onText("""{"kind":"pilot","ts_ms":1,"data":{"owner":"idle","armed":true}}""")
        assertTrue(ch.stateFresh)
        repeat(5) {
            advanceTimeBy(1_000)
            ch.onText("""{"kind":"keepalive","ts_ms":1}""")
        }
        assertTrue("keepalives keep the socket up", ch.connected.value)
        assertFalse("a pilot state older than its stale window is not fresh", ch.stateFresh)
    }

    @Test
    fun `a silence of the spec stale age drops the link, one just under it does not`() = runTest {
        val ch = channel()
        advanceTimeBy(PilotState.STALE_MS - 1)
        ch.watchdog()
        assertTrue("1999 ms of silence is still a live link", ch.connected.value)
        advanceTimeBy(1)
        ch.watchdog()
        assertFalse("2000 ms of silence is a lost link", ch.connected.value)
    }

    // Fails on the base commit: the channel took no parse context and parsed each frame in
    // its collector, on the main thread.
    @Test
    fun `server frames are parsed in the parse context and applied in arrival order`() = runTest {
        val parse = StandardTestDispatcher(testScheduler, name = "parse")
        var streamedIn: ContinuationInterceptor? = null
        val ch = PilotChannel(backgroundScope, MonotonicClock { testScheduler.currentTime }, parse) {
            flow {
                streamedIn = currentCoroutineContext()[ContinuationInterceptor]
                emit("""{"kind":"pilot","ts_ms":1,"data":{"owner":"idle","armed":true}}""")
                emit("""{"kind":"pilot","ts_ms":2,"data":{"owner":"idle","armed":false}}""")
                awaitCancellation()
            }
        }
        ch.start()
        runCurrent()
        assertSame("the stream and its parse run in the parse context", parse, streamedIn)
        assertTrue(ch.connected.value)
        assertEquals(false, ch.state.value?.armed)
        ch.stop()
    }
}
