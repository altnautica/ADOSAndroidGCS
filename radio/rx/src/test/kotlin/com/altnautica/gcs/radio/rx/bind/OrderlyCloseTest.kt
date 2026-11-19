package com.altnautica.gcs.radio.rx.bind

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderlyCloseTest {

    /** A connection whose close advances on a script of (time, progress) steps. */
    private class Script(private val scope: TestScope, private val steps: List<Pair<Long, CloseProgress>>) {
        var finished = false
        fun finish(): Boolean {
            finished = true
            return true
        }
        fun progress(): CloseProgress {
            check(finished) { "progress read before the FIN was sent" }
            return steps.last { it.first <= scope.currentTime }.second
        }
    }

    private fun TestScope.closer() = OrderlyClose(nowMs = { currentTime })

    @Test
    fun `teardown waits for the FIN's acknowledgement and the drone's FIN`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent, 1_200L to CloseProgress.FinAcked, 1_500L to CloseProgress.Complete))
        val clean = closer().run(s::finish, s::progress)
        assertTrue(clean)
        assertTrue("returned before the close completed", currentTime >= 1_500L)
    }

    @Test
    fun `an acknowledged FIN is clean even when the drone never sends its own`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent, 300L to CloseProgress.FinAcked))
        assertTrue(closer().run(s::finish, s::progress))
        assertEquals(300L + OrderlyClose.PEER_FIN_GRACE_MS, currentTime)
    }

    @Test
    fun `an unacknowledged FIN holds the planes up for the whole budget then reports unclean`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent))
        assertFalse(closer().run(s::finish, s::progress))
        assertEquals(OrderlyClose.BUDGET_MS, currentTime)
    }

    @Test
    fun `a reset ends the wait as unclean`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent, 200L to CloseProgress.Failed))
        assertFalse(closer().run(s::finish, s::progress))
        assertEquals(200L, currentTime)
    }

    @Test
    fun `no connection to finish is unclean at once`() = runTest {
        assertFalse(closer().run({ false }, { CloseProgress.NotClosing }))
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a cancelled bind still sends its FIN and waits for the acknowledgement`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent, 1_200L to CloseProgress.FinAcked, 1_500L to CloseProgress.Complete))
        var clean: Boolean? = null
        val bind = launch {
            try {
                awaitCancellation()
            } finally {
                clean = closer().run(s::finish, s::progress)
            }
        }
        runCurrent()
        bind.cancelAndJoin()
        assertTrue(s.finished)
        assertEquals(true, clean)
        assertTrue("returned before the close completed", currentTime >= 1_500L)
    }

    @Test
    fun `a cancelled close is still bounded by its own budget`() = runTest {
        val s = Script(this, listOf(0L to CloseProgress.FinSent))
        var clean: Boolean? = null
        val bind = launch {
            try {
                awaitCancellation()
            } finally {
                clean = closer().run(s::finish, s::progress)
            }
        }
        runCurrent()
        bind.cancelAndJoin()
        assertEquals(false, clean)
        assertEquals(OrderlyClose.BUDGET_MS, currentTime)
    }
}
