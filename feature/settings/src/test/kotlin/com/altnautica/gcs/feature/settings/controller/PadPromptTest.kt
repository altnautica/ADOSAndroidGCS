package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.skills.input.AdosInputRouter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the controls screen asks for a button press, and when it says there is no pad. */
class PadPromptTest {

    @Test
    fun `no pad ever attached says no pad`() {
        assertEquals(PadPrompt.NoPad, padPromptFor(controllerActive = false, padSeen = false))
    }

    @Test
    fun `a pad seen earlier asks for a button press`() {
        assertEquals(PadPrompt.PressAnyButton, padPromptFor(controllerActive = false, padSeen = true))
    }

    @Test
    fun `an active controller shows no prompt`() {
        assertEquals(PadPrompt.None, padPromptFor(controllerActive = true, padSeen = true))
        assertEquals(PadPrompt.None, padPromptFor(controllerActive = true, padSeen = false))
    }

    @Test
    fun `seen stays true after the pad is removed`() = runTest(UnconfinedTestDispatcher()) {
        val connected = MutableStateFlow(false)
        val router = mockk<AdosInputRouter> { every { gamepadConnected } returns connected }
        val memory = PadSeenMemory(router)
        val seen = mutableListOf<Boolean>()
        val job = launch { memory.seen.toList(seen) }

        connected.value = true
        connected.value = false

        assertEquals(listOf(false, true), seen)
        job.cancel()
    }

    @Test
    fun `a pad attached before the screen opens counts as seen`() = runTest(UnconfinedTestDispatcher()) {
        val connected = MutableStateFlow(true)
        val router = mockk<AdosInputRouter> { every { gamepadConnected } returns connected }
        val seen = mutableListOf<Boolean>()
        val job = launch { PadSeenMemory(router).seen.toList(seen) }

        assertEquals(listOf(true), seen)
        job.cancel()
    }

    @Test
    fun `a pad attached and removed while nothing watches is remembered`() = runTest(UnconfinedTestDispatcher()) {
        val connected = MutableStateFlow(false)
        val router = mockk<AdosInputRouter> { every { gamepadConnected } returns connected }
        val memory = PadSeenMemory(router)

        connected.value = true
        connected.value = false

        assertEquals(true, memory.seen.first())
    }
}
