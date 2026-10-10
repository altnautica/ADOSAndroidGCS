package com.altnautica.gcs.feature.fleet

import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.fleet.data.NodeVitals
import com.altnautica.gcs.feature.fleet.data.NodeVitalsRepository
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FleetViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

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

    @Test
    fun emptySourcesGiveEmptyState() = runTest(dispatcher) {
        val repo = NodeRepository(setOf(Source(MutableStateFlow(emptyList()))), backgroundScope)
        val vm = FleetViewModel(repo, vitals())
        backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(FleetUiState.Empty, vm.uiState.first { it !is FleetUiState.Loading })
    }

    @Test
    fun cardsCarryVitalsAndFlyability() = runTest(dispatcher) {
        val state = MutableStateFlow(
            listOf(
                node("drone", NodeProfile.Drone, listOf(Reach.Lan)),
                node("gs-empty", NodeProfile.GroundStation, listOf(Reach.Lan)),
                node("gs-carrying", NodeProfile.GroundStation, listOf(Reach.Lan), carried = DeviceId("drone")),
                node("ws", NodeProfile.Workstation, listOf(Reach.Lan)),
                node("locked", NodeProfile.Drone, listOf(Reach.Blocked(BlockReason.NotPaired))),
                node("usb", NodeProfile.Unknown, listOf(Reach.DirectFc)),
            ),
        )
        val repo = NodeRepository(setOf(Source(state)), backgroundScope)
        val vm = FleetViewModel(repo, vitals(mapOf("drone" to NodeVitals(76, "LOITER", true))))
        backgroundScope.launch { vm.uiState.collect {} }

        val cards = (vm.uiState.first { it is FleetUiState.Nodes && it.cards.any { c -> c.vitals.batteryPct != null } } as FleetUiState.Nodes)
            .cards.associateBy { it.node.id.value }
        assertEquals(NodeVitals(76, "LOITER", true), cards.getValue("drone").vitals)
        assertTrue(cards.getValue("drone").canFly)
        assertTrue(cards.getValue("drone").showThumbnail)
        assertFalse(cards.getValue("gs-empty").canFly)
        assertTrue(cards.getValue("gs-carrying").canFly)
        assertFalse(cards.getValue("ws").canFly)
        assertFalse(cards.getValue("locked").canFly)
        assertTrue(cards.getValue("usb").canFly)
        assertFalse(cards.getValue("usb").showThumbnail)
    }

    @Test
    fun staleDroneHasNoThumbnail() {
        val card = FleetViewModel.card(node("d", NodeProfile.Drone, listOf(Reach.Lan), Liveness.Stale), NodeVitals.UNKNOWN)
        assertFalse(card.showThumbnail)
        assertTrue(card.canFly)
    }
}
