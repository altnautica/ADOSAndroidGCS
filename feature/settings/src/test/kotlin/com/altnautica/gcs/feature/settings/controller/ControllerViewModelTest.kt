package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.input.controller.Rumble
import com.altnautica.gcs.feature.settings.MemoryPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val buttons = MutableStateFlow(0)
    private val router = mockk<AdosInputRouter>(relaxed = true) {
        every { this@mockk.buttons } returns this@ControllerViewModelTest.buttons
        every { axes } returns MutableStateFlow(GamepadAxes.Neutral)
    }
    private val manager = mockk<ControllerManager>(relaxed = true) {
        every { active } returns MutableStateFlow<ControllerInfo?>(null)
    }
    private val nodes = mockk<NodeRepository> {
        every { this@mockk.nodes } returns MutableStateFlow<List<NodeSummary>?>(emptyList())
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(settings: ControllerSettingsRepository = ControllerSettingsRepository(MemoryPreferences())) =
        ControllerViewModel(manager, settings, router, nodes)

    private fun TestScope.leaves(vm: ControllerViewModel): () -> Int {
        var count = 0
        backgroundScope.launch { vm.leave.collect { count++ } }
        runCurrent()
        return { count }
    }

    private val backBit = 1 shl GamepadButton.B

    @Test
    fun `holding back for a second leaves the input test`() = runTest(dispatcher) {
        val vm = vm()
        val count = leaves(vm)
        vm.setTestActive(true)
        runCurrent()
        buttons.value = backBit
        advanceTimeBy(ControllerViewModel.LEAVE_HOLD_MS - 1)
        runCurrent()
        assertEquals(0, count())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, count())
        verify { router.suppressStickNavigation = true }
    }

    @Test
    fun `a short back press does not leave`() = runTest(dispatcher) {
        val vm = vm()
        val count = leaves(vm)
        vm.setTestActive(true)
        runCurrent()
        buttons.value = backBit
        advanceTimeBy(500)
        runCurrent()
        buttons.value = 0
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, count())
    }

    @Test
    fun `back hold is ignored outside the input test`() = runTest(dispatcher) {
        val vm = vm()
        val count = leaves(vm)
        vm.setTestActive(true)
        vm.setTestActive(false)
        runCurrent()
        buttons.value = backBit
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, count())
        verify { router.suppressStickNavigation = false }
    }

    @Test
    fun `test rumble ignores the rumble setting`() = runTest(dispatcher) {
        vm().testRumble()
        verify { manager.rumble(Rumble.Warning, force = true) }
    }

    @Test
    fun `removing a vehicle withdraws its all-modes permission`() = runTest(dispatcher) {
        val settings = ControllerSettingsRepository(MemoryPreferences())
        settings.setAllModes(DeviceId("drone-1"), enabled = true)
        val vm = vm(settings)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(listOf(AllModesVehicle(DeviceId("drone-1"), null)), vm.state.value.allModesVehicles)
        vm.removeAllModes(DeviceId("drone-1"))
        runCurrent()
        assertEquals(emptySet<String>(), settings.settings.first().allModesVehicles)
        assertNull(vm.state.value.controller)
    }
}
