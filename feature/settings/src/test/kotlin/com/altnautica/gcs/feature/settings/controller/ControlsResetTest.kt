package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.binding.KeyConflict
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.controls.EditResult
import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.controls.SaveResult
import com.altnautica.gcs.core.skills.controls.setRowAction
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import com.altnautica.gcs.feature.settings.MemoryPreferences
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Reset to default is a draft change: Save stores the shipped bindings, Cancel keeps the operator's. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsResetTest {
    private val dispatcher = StandardTestDispatcher()
    private val spec = CockpitSpecRepository(DirectorySpecSource(File("../cockpit/src/main/assets/cockpit-spec")))
    private val shipped get() = Ds4Bindings.resolve(spec.ds4, null, emptyList()).rows

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(controls: ControlsRepository): ControlsViewModel {
        val router = mockk<AdosInputRouter>(relaxed = true) {
            every { gamepadConnected } returns MutableStateFlow(false)
        }
        return ControlsViewModel(
            manager = mockk(relaxed = true) { every { active } returns MutableStateFlow<ControllerInfo?>(null) },
            settings = ControllerSettingsRepository(MemoryPreferences()),
            controls = controls,
            spec = spec,
            router = router,
            connections = PadConnectionProbe { emptySet() },
            padMemory = PadSeenMemory(router),
            registry = mockk { every { skills } returns flowOf(emptyList<Skill>()) },
            nodeRepository = mockk<NodeRepository> { every { nodes } returns MutableStateFlow<List<NodeSummary>?>(emptyList()) },
            bindings = mockk<BindingStore> { every { keyConflicts } returns flowOf(emptyList<KeyConflict>()) },
        )
    }

    private suspend fun customised(controls: ControlsRepository) {
        val rows = controls.bindings.first()
        val square = rows.first { it.control == "square" && it.gesture == Gesture.TAP && it.with.isEmpty() }
        val edited = (setRowAction(rows, square, "record_toggle", Ds4Bindings.stopHoldMs(spec.layout)) as EditResult.Applied).rows
        assertEquals(SaveResult.Saved, controls.saveBindings(edited))
    }

    @Test
    fun `reset fills the draft with the shipped bindings and leaves the saved ones alone`() = runTest(dispatcher) {
        val controls = ControlsRepository(MemoryPreferences(), spec, flowOf(emptyList()))
        customised(controls)
        val saved = controls.bindings.first()
        val vm = vm(controls)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)

        vm.resetToDefault()
        advanceUntilIdle()
        assertTrue(vm.state.value.dirty)
        assertEquals(shipped, vm.state.value.rows)
        assertEquals(saved, controls.bindings.first())

        vm.discard()
        advanceUntilIdle()
        assertEquals(saved, vm.state.value.rows)
        assertEquals(saved, controls.bindings.first())
    }

    @Test
    fun `saving the reset draft stores the shipped bindings`() = runTest(dispatcher) {
        val controls = ControlsRepository(MemoryPreferences(), spec, flowOf(emptyList()))
        customised(controls)
        val vm = vm(controls)
        backgroundScope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.resetToDefault()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)
        assertEquals(shipped, controls.bindings.first())
    }

    @Test
    fun `return home and emergency stop carry their spec names in the binding list`() {
        val vm = vm(ControlsRepository(MemoryPreferences(), spec, flowOf(emptyList())))
        assertEquals("Return home", vm.actionLabel("rth"))
        assertEquals("Emergency stop", vm.actionLabel("kill"))
    }

    @Test
    fun `clear calibration forgets the attached pad's calibration`() = runTest(dispatcher) {
        val controls = ControlsRepository(MemoryPreferences(), spec, flowOf(emptyList()))
        val key = com.altnautica.gcs.core.skills.controls.padKey(0x054C, 0x09CC)
        controls.setCalibration(key, com.altnautica.gcs.core.skills.controls.PadCalibration())
        val pad = ControllerInfo(1, "Wireless Controller", 0x054C, 0x09CC, com.altnautica.gcs.core.skills.input.controller.ControllerFamily.PlayStation, null, false)
        val router = mockk<AdosInputRouter>(relaxed = true) { every { gamepadConnected } returns MutableStateFlow(false) }
        val vm = ControlsViewModel(
            manager = mockk(relaxed = true) { every { active } returns MutableStateFlow<ControllerInfo?>(pad) },
            settings = ControllerSettingsRepository(MemoryPreferences()),
            controls = controls,
            spec = spec,
            router = router,
            connections = PadConnectionProbe { emptySet() },
            padMemory = PadSeenMemory(router),
            registry = mockk { every { skills } returns flowOf(emptyList<Skill>()) },
            nodeRepository = mockk<NodeRepository> { every { nodes } returns MutableStateFlow<List<NodeSummary>?>(emptyList()) },
            bindings = mockk<BindingStore> { every { keyConflicts } returns flowOf(emptyList<KeyConflict>()) },
        )
        assertTrue(key in controls.calibrations.first())
        vm.clearCalibration()
        advanceUntilIdle()
        assertFalse(key in controls.calibrations.first())
    }
}
