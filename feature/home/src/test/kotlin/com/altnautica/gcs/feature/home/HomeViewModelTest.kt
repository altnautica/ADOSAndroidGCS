package com.altnautica.gcs.feature.home

import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.demo.DemoIds
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import kotlinx.coroutines.flow.toList

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val prefs = MemoryPreferences()
    private val store = SelectedVehicleStore(prefs)
    private val demo = DemoSettings(prefs)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun node(
        id: String,
        profile: NodeProfile,
        reaches: List<Reach>,
        liveness: Liveness = Liveness.Live,
        carried: DeviceId? = null,
    ) = NodeSummary(DeviceId(id), id, profile, reaches, liveness, null, FcVariant.Unknown, carried, null)

    private class Source(val state: MutableStateFlow<List<NodeSummary>>) : NodeSource {
        override val nodes: Flow<List<NodeSummary>> = state
    }

    private fun vitals(map: Map<String, NodeVitals> = emptyMap()) = mockk<NodeVitalsRepository> {
        every { vitals(any()) } answers { flowOf(map[firstArg<NodeSummary>().id.value] ?: NodeVitals.UNKNOWN) }
        every { thumbnails(any(), any()) } returns flowOf(null)
    }

    private fun lanNodes(unreadable: MutableStateFlow<Boolean> = MutableStateFlow(false)) =
        mockk<LanNodeSource> { every { savedNodesUnreadable } returns unreadable }

    private val openVehicles = MutableStateFlow<Map<DeviceId, com.altnautica.gcs.core.model.Vehicle>>(emptyMap())

    private fun CoroutineScope.vm(
        nodes: List<NodeSummary>,
        vitals: NodeVitalsRepository = vitals(),
        lan: LanNodeSource = lanNodes(),
    ): HomeViewModel {
        val repo = NodeRepository(setOf(Source(MutableStateFlow(nodes))), this)
        val vehicles = mockk<VehicleRepository> { every { this@mockk.openVehicles } returns this@HomeViewModelTest.openVehicles }
        return HomeViewModel(repo, vitals, lan, store, demo, vehicles).also { vm -> launch { vm.uiState.collect {} } }
    }

    @Test
    fun aClosedDirectLinkIsNotConnecting() = runTest(dispatcher) {
        val vm = backgroundScope.vm(listOf(node("usb", NodeProfile.Drone, listOf(Reach.DirectFc))))
        val card = vm.loaded().vehicles.single()
        assertTrue(card.directLinkClosed)
        val fly = surfaces(card).single { it.surface == HomeSurface.Fly }
        assertEquals(HeroReadiness.OpensOnFly, heroReadiness(fly, card.vitals, card.directLinkClosed))
    }

    @Test
    fun anOpenDirectLinkReportsItsVitals() = runTest(dispatcher) {
        openVehicles.value = mapOf(DeviceId("usb") to mockk(relaxed = true))
        val known = NodeVitals(80, "LOITER", false, com.altnautica.gcs.core.model.GpsFix.Fix3D, 12)
        val vm = backgroundScope.vm(listOf(node("usb", NodeProfile.Drone, listOf(Reach.DirectFc))), vitals = vitals(mapOf("usb" to known)))
        val card = vm.uiState.first { s -> s.vehicles.any { it.vitals == known } }.vehicles.single()
        assertFalse(card.directLinkClosed)
        val fly = surfaces(card).single { it.surface == HomeSurface.Fly }
        assertEquals(HeroReadiness.Ready, heroReadiness(fly, card.vitals, card.directLinkClosed))
    }

    @Test
    fun aLanNodeNeverWaitsOnADirectLink() {
        val card = HomeViewModel.card(node("d", NodeProfile.Drone, listOf(Reach.Lan, Reach.DirectFc)), NodeVitals.UNKNOWN, linkOpen = false)
        assertFalse(card.directLinkClosed)
    }

    @Test
    fun directLinkVitalsSurviveTheLinkClosing() = runTest(dispatcher) {
        val known = NodeVitals(64, "RTL", false)
        val seen = flowOf(NodeVitals.UNKNOWN, known, NodeVitals.UNKNOWN).keepLastKnown()
        assertEquals(listOf(NodeVitals.UNKNOWN, known), seen.toList(mutableListOf()))
    }

    @Test
    fun unpairIsOfferedOnlyWithAnAgentLink() {
        val lan = HomeViewModel.card(node("d", NodeProfile.Drone, listOf(Reach.Lan)), NodeVitals.UNKNOWN, linkOpen = false)
        val usb = HomeViewModel.card(node("u", NodeProfile.Drone, listOf(Reach.DirectFc)), NodeVitals.UNKNOWN, linkOpen = false)
        assertEquals(listOf(VehicleMenuAction.Details, VehicleMenuAction.PlanFor, VehicleMenuAction.Unpair), vehicleMenuActions(lan))
        assertEquals(listOf(VehicleMenuAction.Details, VehicleMenuAction.PlanFor), vehicleMenuActions(usb))
    }

    private suspend fun HomeViewModel.loaded(): HomeUiState = uiState.first { !it.loading }

    @Test
    fun emptySourcesGiveNoVehicles() = runTest(dispatcher) {
        val state = backgroundScope.vm(emptyList()).loaded()
        assertTrue(state.vehicles.isEmpty())
        assertNull(state.selected)
    }

    @Test
    fun cardsCarryVitalsAndFlyability() = runTest(dispatcher) {
        val vm = backgroundScope.vm(
            listOf(
                node("drone", NodeProfile.Drone, listOf(Reach.Lan)),
                node("gs-empty", NodeProfile.GroundStation, listOf(Reach.Lan)),
                node("gs-carrying", NodeProfile.GroundStation, listOf(Reach.Lan), carried = DeviceId("drone")),
                node("ws", NodeProfile.Workstation, listOf(Reach.Lan)),
                node("locked", NodeProfile.Drone, listOf(Reach.Blocked(BlockReason.NotPaired))),
                node("usb", NodeProfile.Unknown, listOf(Reach.DirectFc)),
            ),
            vitals = vitals(mapOf("drone" to NodeVitals(76, "LOITER", true))),
        )

        val cards = vm.uiState.first { s -> s.vehicles.any { it.vitals.batteryPct != null } }.vehicles.associateBy { it.node.id.value }
        assertEquals(NodeVitals(76, "LOITER", true), cards.getValue("drone").vitals)
        assertTrue(cards.getValue("drone").canFly)
        assertTrue(cards.getValue("drone").showThumbnail)
        assertTrue(cards.getValue("drone").hasAgentLink)
        assertFalse(cards.getValue("gs-empty").canFly)
        assertTrue(cards.getValue("gs-carrying").canFly)
        assertFalse(cards.getValue("ws").canFly)
        assertFalse(cards.getValue("locked").canFly)
        assertTrue(cards.getValue("usb").canFly)
        assertFalse(cards.getValue("usb").showThumbnail)
        assertFalse(cards.getValue("usb").hasAgentLink)
    }

    @Test
    fun unreadableSavedNodesAreReportedAndRetried() = runTest(dispatcher) {
        val unreadable = MutableStateFlow(true)
        val lan = lanNodes(unreadable)
        coEvery { lan.retrySavedNodes() } coAnswers { unreadable.value = false; true }
        val vm = backgroundScope.vm(emptyList(), lan = lan)

        assertTrue(vm.loaded().savedNodesUnreadable)
        vm.retrySavedNodes()
        coVerify(exactly = 1) { lan.retrySavedNodes() }
        assertFalse(vm.loaded().savedNodesUnreadable)
    }

    @Test
    fun staleDroneHasNoThumbnail() {
        val card = HomeViewModel.card(node("d", NodeProfile.Drone, listOf(Reach.Lan), Liveness.Stale), NodeVitals.UNKNOWN, linkOpen = false)
        assertFalse(card.showThumbnail)
        assertTrue(card.canFly)
    }

    @Test
    fun liveNodesOrderByProfileThenName() = runTest(dispatcher) {
        val vm = backgroundScope.vm(
            listOf(
                node("a-gs", NodeProfile.GroundStation, listOf(Reach.Lan)),
                node("b-unknown", NodeProfile.Unknown, listOf(Reach.Lan)),
                node("c-ws", NodeProfile.Workstation, listOf(Reach.Lan)),
                node("d-visor", NodeProfile.Visor, listOf(Reach.Lan)),
                node("e-drone", NodeProfile.Drone, listOf(Reach.Lan)),
            ),
        )
        val cards = vm.loaded().vehicles
        assertEquals(listOf("e-drone", "d-visor", "c-ws", "a-gs", "b-unknown"), cards.map { it.node.id.value })
        assertFalse(cards.first { it.node.profile == NodeProfile.Visor }.canFly)
        assertFalse(cards.first { it.node.profile == NodeProfile.Unknown }.canFly)
    }

    @Test
    fun selectionFallsBackToFirstVehicle() = runTest(dispatcher) {
        store.select(DeviceId("gone"))
        val vm = backgroundScope.vm(listOf(node("b", NodeProfile.Drone, listOf(Reach.Lan)), node("a", NodeProfile.GroundStation, listOf(Reach.Lan))))
        assertEquals("b", vm.loaded().selected?.node?.id?.value)
    }

    @Test
    fun storedSelectionIsKept() = runTest(dispatcher) {
        store.select(DeviceId("a"))
        val vm = backgroundScope.vm(listOf(node("b", NodeProfile.Drone, listOf(Reach.Lan)), node("a", NodeProfile.GroundStation, listOf(Reach.Lan))))
        assertEquals("a", vm.loaded().selected?.node?.id?.value)
    }

    @Test
    fun selectNextWrapsBothWays() = runTest(dispatcher) {
        val vm = backgroundScope.vm(
            listOf(
                node("a", NodeProfile.Drone, listOf(Reach.Lan)),
                node("b", NodeProfile.Drone, listOf(Reach.Lan)),
                node("c", NodeProfile.Drone, listOf(Reach.Lan)),
            ),
        )
        assertEquals("a", vm.loaded().selected?.node?.id?.value)
        vm.selectNext(-1)
        assertEquals("c", vm.uiState.first { it.selected?.node?.id?.value == "c" }.selected?.node?.id?.value)
        vm.selectNext(1)
        assertEquals("a", vm.uiState.first { it.selected?.node?.id?.value == "a" }.selected?.node?.id?.value)
        assertEquals(DeviceId("a"), store.selected.first())
    }

    @Test
    fun enableDemoTurnsDemoOnAndSelectsTheDemoDrone() = runTest(dispatcher) {
        val vm = backgroundScope.vm(emptyList())
        assertFalse(vm.loaded().demoEnabled)
        vm.enableDemo()
        assertTrue(vm.uiState.first { it.demoEnabled }.demoEnabled)
        assertEquals(DemoIds.DRONE, store.selected.first())
    }
}
