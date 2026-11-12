package com.altnautica.gcs.feature.home

import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.mission.MissionMirrorSource
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.feature.home.quick.QuickMenuViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The Quick menu's vehicle card over an open cockpit: a silent link is not shown as live. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickMenuVitalsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val usb = NodeSummary(DeviceId("usb"), "usb", NodeProfile.Drone, listOf(Reach.DirectFc), Liveness.Live, null, FcVariant.Unknown, null, null)
    private val heard = NodeVitals(70, "AUTO", true, GpsFix.Fix3D, 14)
    private val live = MutableStateFlow(heard)
    private val openVehicles = MutableStateFlow<Map<DeviceId, Vehicle>>(mapOf(DeviceId("usb") to mockk(relaxed = true)))
    private var now = 20_000L

    private class Source(override val nodes: Flow<List<NodeSummary>>) : NodeSource

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun CoroutineScope.vm(): QuickMenuViewModel {
        val vm = QuickMenuViewModel(
            nodeRepository = NodeRepository(setOf(Source(MutableStateFlow(listOf(usb)))), this),
            vitalsRepository = mockk<NodeVitalsRepository> { every { vitals(any()) } returns live },
            selectedStore = SelectedVehicleStore(MemoryPreferences()),
            controllerManager = mockk<ControllerManager> { every { active } returns MutableStateFlow(null) },
            cockpitSummaries = CockpitSummaryRegistry(),
            missionMirror = MissionMirrorSource { flowOf(null) },
            vehicleRepository = mockk<VehicleRepository> { every { this@mockk.openVehicles } returns this@QuickMenuVitalsTest.openVehicles },
        )
        vm.clock = { now }
        launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun anOpenLinkWhoseControllerFallsSilentShowsUnknownVitals() = runTest(dispatcher) {
        val vm = backgroundScope.vm()
        vm.uiState.first { it.vehicle?.vitals == heard }

        live.value = NodeVitals.UNKNOWN
        val card = vm.uiState.value.vehicle!!
        assertEquals(NodeVitals.UNKNOWN, card.vitals)
        assertNull(card.keptHeardAtMs)
    }

    @Test
    fun aClosedLinkShowsTheKeptReadingWithItsAgeAndNoArmState() = runTest(dispatcher) {
        val vm = backgroundScope.vm()
        vm.uiState.first { it.vehicle?.vitals == heard }

        now = 33_000L
        openVehicles.value = emptyMap()
        live.value = NodeVitals.UNKNOWN
        val card = vm.uiState.value.vehicle!!
        assertEquals(heard.copy(armed = null), card.vitals)
        assertEquals(33_000L, card.keptHeardAtMs)
    }
}
