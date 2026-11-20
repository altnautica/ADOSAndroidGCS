package com.altnautica.gcs.radio.rx.uplink

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeGateTest {

    @Test
    fun aTakeBackBeforeTheTransmittersOpenCancelsTheTake() {
        val gate = TakeGate()
        assertEquals(TakeGate.TakeBack.Cancelled, gate.takeBack())
        assertFalse(gate.open())
        assertTrue(gate.takenBack())
        assertNull(gate.close())
        assertEquals(TakeGate.TakeBack.Settled, gate.takeBack())
    }

    @Test
    fun aTakeBackWhileTheTransmittersOpenWaitsForTheTake() {
        val gate = TakeGate()
        assertTrue(gate.open())
        val first = gate.takeBack() as TakeGate.TakeBack.Waiting
        val again = gate.takeBack() as TakeGate.TakeBack.Waiting
        assertSame(first.quiet, again.quiet)
        var settled = false
        val waiter = gate.close { settled = true }
        assertFalse("settled as transmitting with a take-back waiting", settled)
        assertSame(first.quiet, waiter)
        assertSame(waiter, gate.close())
    }

    @Test
    fun aTakeBackAfterTheTakeSettledIsTheHoldersOrdinaryOne() {
        val gate = TakeGate()
        assertTrue(gate.open())
        var settled = false
        assertNull(gate.close { settled = true })
        assertTrue(settled)
        assertEquals(TakeGate.TakeBack.Settled, gate.takeBack())
        assertFalse(gate.takenBack())
    }

    /**
     * The take as [UplinkHandover] runs it, against a take-back arriving at
     * every millisecond of the take's timeline on the test scheduler: after
     * an ok answer the transmitters are closed and never open again.
     */
    @Test
    fun noTakeBackIsAnsweredOkWhileThePhoneCanStillRadiate() {
        val postMs = 40L
        val holdMs = 60L
        for (arrival in 0L..postMs + holdMs + 20) {
            runTest {
                val gate = TakeGate()
                var transmitting = false
                var openedAfterAnswer = false
                var answered = false
                launch {
                    delay(postMs)
                    if (!gate.open()) return@launch
                    delay(holdMs)
                    transmitting = true
                    if (answered) openedAfterAnswer = true
                    val waiter = gate.close() ?: return@launch
                    transmitting = false
                    waiter.complete(true)
                }
                launch {
                    delay(arrival)
                    when (val during = gate.takeBack()) {
                        TakeGate.TakeBack.Cancelled -> Unit
                        is TakeGate.TakeBack.Waiting -> assertTrue(during.quiet.await())
                        TakeGate.TakeBack.Settled -> transmitting = false
                    }
                    answered = true
                    assertFalse("arrival $arrival ms: transmitting at the ok answer", transmitting)
                }
                testScheduler.advanceUntilIdle()
                assertTrue(answered)
                assertFalse("arrival $arrival ms: transmitting after the ok answer", transmitting)
                assertFalse("arrival $arrival ms: opened after the ok answer", openedAfterAnswer)
            }
        }
    }
}
