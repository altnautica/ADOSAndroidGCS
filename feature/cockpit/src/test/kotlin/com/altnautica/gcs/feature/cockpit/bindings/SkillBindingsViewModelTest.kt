package com.altnautica.gcs.feature.cockpit.bindings

import com.altnautica.gcs.feature.cockpit.MemoryPreferences
import app.cash.turbine.test
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.InputEvent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillBindingsViewModelTest {
    private val events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 8)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.vm(): Pair<SkillBindingsViewModel, BindingStore> {
        val store = BindingStore(MemoryPreferences())
        val router = mockk<AdosInputRouter>(relaxed = true) { every { this@mockk.events } returns this@SkillBindingsViewModelTest.events }
        return SkillBindingsViewModel(store, SkillRegistry(emptySet()), router) to store
    }

    private suspend fun SkillBindingsViewModel.ready() = state.filterIsInstance<BindingsUiState.Ready>().first()

    @Test
    fun `lists bindable skills only`() = runTest {
        val (vm, _) = vm()
        val ids = vm.ready().skills.map { it.id }
        assertTrue("arm" in ids)
        assertFalse("set-mode" in ids)
        assertFalse("fly-here" in ids)
    }

    @Test
    fun `a captured chord is bound to the slot and capture ends`() = runTest {
        val (vm, store) = vm()
        vm.ready()
        vm.startCapture(10, CaptureKind.Key)
        assertEquals(Capture(10, CaptureKind.Key), vm.ready().capture)
        events.emit(InputEvent.Chord("shift+g"))
        // DataStore writes on its own I/O thread: wait for the persisted value.
        assertEquals("shift+g", store.loadout.first { it.slots[10].keyChord != null }.slots[10].keyChord)
        assertNull(vm.state.filterIsInstance<BindingsUiState.Ready>().first { it.capture == null }.capture)
    }

    @Test
    fun `a reserved gamepad button is refused with a message`() = runTest {
        val (vm, store) = vm()
        vm.ready()
        vm.messages.test {
            vm.startCapture(10, CaptureKind.Button)
            events.emit(InputEvent.Button(GamepadButton.START, pressed = true, heldMs = 0))
            assertEquals(BindMessage.Reserved, awaitItem())
        }
        assertNull(store.loadout.first().slots[10].gamepadButton)
    }

    @Test
    fun `assigning and resetting slots`() = runTest {
        val (vm, store) = vm()
        vm.ready()
        vm.assign(10, "mode.guided")
        assertEquals("mode.guided", store.loadout.first { it.slots[10].skillId != null }.slots[10].skillId)
        vm.reset()
        assertNull(store.loadout.first { it.slots[10].skillId == null }.slots[10].skillId)
    }
}
