package com.altnautica.gcs.feature.cockpit.confirm

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.ConfirmRequest
import com.altnautica.gcs.core.skills.ConfirmResult
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.testBuiltin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The confirm sheet's state changes only when something it shows changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmPublishTest {
    private val kill = testBuiltin("kill")

    @Test
    fun `an untouched stop sheet publishes no state while its guard runs, and a hold publishes once per shown tenth`() = runTest {
        val c = ConfirmController(backgroundScope, MonotonicClock { testScheduler.currentTime }, { emptyList() }, { false })
        val states = mutableListOf<ConfirmUiState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.state.collect { states += it } }
        val result = async { c.confirm(ConfirmRequest(DeviceId("d"), kill, kill.confirm!!, emptyMap(), gamepadButton = null, checklistReady = true)) }
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        // The closed sheet and the opened one; nothing while the 3 s guard counts down.
        assertTrue("${states.size} states with the sheet untouched for 2 s", states.size <= 3)

        val beforeHold = states.size
        c.onTouchHold(true)
        advanceTimeBy(1_000)
        runCurrent()
        val held = states.size - beforeHold
        // 1 s of hold shows ten tenths of a second (plus the hold start).
        assertTrue("$held states during a 1 s hold", held in 9..12)
        assertTrue(c.isOpen)

        // The hold still completes exactly at 3 s.
        advanceTimeBy(1_990)
        runCurrent()
        assertTrue(c.isOpen)
        advanceTimeBy(30)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), result.await())
    }
}
