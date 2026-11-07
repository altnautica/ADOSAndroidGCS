package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.feature.settings.MemoryPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The controller Status tab; the controls editor is covered by [ControlsConfigTest]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
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

    @Test
    fun `removing a vehicle withdraws its all-modes permission`() = runTest(dispatcher) {
        val settings = ControllerSettingsRepository(MemoryPreferences())
        settings.setAllModes(DeviceId("drone-1"), enabled = true)
        val vm = ControllerViewModel(manager, settings, nodes)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(listOf(AllModesVehicle(DeviceId("drone-1"), null)), vm.state.value.allModesVehicles)
        vm.removeAllModes(DeviceId("drone-1"))
        runCurrent()
        assertEquals(emptySet<String>(), settings.settings.first().allModesVehicles)
        assertNull(vm.state.value.controller)
    }
}
