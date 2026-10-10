package com.altnautica.gcs.feature.fleet.data

import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRepositoryTest {

    private val ground = DeviceId("gs-1")
    private val drone = DeviceId("drone-1")

    private fun node(
        id: DeviceId,
        reaches: List<Reach>,
        name: String = "",
        profile: NodeProfile = NodeProfile.Unknown,
        liveness: Liveness = Liveness.Offline,
        version: String? = null,
        fc: FcVariant = FcVariant.Unknown,
        lastSeen: Long? = null,
    ) = NodeSummary(id, name, profile, reaches, liveness, version, fc, null, lastSeen)

    private class FakeSource(initial: List<NodeSummary>) : NodeSource {
        val state = MutableStateFlow(initial)
        override val nodes: Flow<List<NodeSummary>> = state
    }

    @Test
    fun reachesUnionInCommandableFirstOrder() {
        val merged = NodeMerger.merge(
            listOf(
                listOf(node(drone, listOf(Reach.CloudQueue, Reach.CloudLive))),
                listOf(node(drone, listOf(Reach.PhoneRadio))),
                listOf(node(drone, listOf(Reach.ViaGround(ground), Reach.Lan))),
                listOf(node(drone, listOf(Reach.DirectFc))),
            ),
        )
        assertEquals(
            listOf(Reach.DirectFc, Reach.Lan, Reach.ViaGround(ground), Reach.PhoneRadio, Reach.CloudLive, Reach.CloudQueue),
            merged.single().reaches,
        )
    }

    @Test
    fun blockedReachDroppedWhenUsableReachExistsKeptOtherwise() {
        val withUsable = NodeMerger.merge(
            listOf(
                listOf(node(drone, listOf(Reach.Blocked(BlockReason.NotPaired)))),
                listOf(node(drone, listOf(Reach.CloudLive))),
            ),
        )
        assertEquals(listOf(Reach.CloudLive), withUsable.single().reaches)

        val onlyBlocked = NodeMerger.merge(
            listOf(listOf(node(drone, listOf(Reach.Blocked(BlockReason.SignedOut), Reach.Blocked(BlockReason.SignedOut))))),
        )
        assertEquals(listOf<Reach>(Reach.Blocked(BlockReason.SignedOut)), onlyBlocked.single().reaches)
    }

    @Test
    fun descriptiveFieldsComeFromBestReachAndLivenessIsBest() {
        val merged = NodeMerger.merge(
            listOf(
                listOf(node(drone, listOf(Reach.CloudLive), name = "Cloud name", profile = NodeProfile.Drone, liveness = Liveness.Stale, version = "1.0", lastSeen = 50)),
                listOf(node(drone, listOf(Reach.Lan), name = "Lan name", liveness = Liveness.Live, fc = FcVariant.MavlinkPx4, lastSeen = 20)),
            ),
        ).single()
        assertEquals("Lan name", merged.name)
        assertEquals(NodeProfile.Drone, merged.profile)
        assertEquals(Liveness.Live, merged.liveness)
        assertEquals("1.0", merged.agentVersion)
        assertEquals(FcVariant.MavlinkPx4, merged.fcVariant)
        assertEquals(50L, merged.lastSeenMs)
    }

    @Test
    fun fleetOrderIsLiveFirstThenName() {
        val merged = NodeMerger.merge(
            listOf(
                listOf(
                    node(DeviceId("a"), listOf(Reach.Lan), name = "Alpha", liveness = Liveness.Offline),
                    node(DeviceId("b"), listOf(Reach.Lan), name = "bravo", liveness = Liveness.Live),
                    node(DeviceId("c"), listOf(Reach.Lan), name = "Charlie", liveness = Liveness.Live),
                ),
            ),
        )
        assertEquals(listOf("bravo", "Charlie", "Alpha"), merged.map { it.name })
    }

    @Test
    fun repositoryIsNullUntilObservedThenMergesEverySource() = runTest {
        val lan = FakeSource(listOf(node(drone, listOf(Reach.Lan), name = "Drone")))
        val direct = FakeSource(listOf(node(drone, listOf(Reach.DirectFc))))
        val repo = NodeRepository(setOf(lan, direct), backgroundScope)
        assertNull(repo.nodes.value)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.nodes.collect {} }
        val nodes = repo.nodes.filterNotNull().first { it.isNotEmpty() && it.single().reaches.size == 2 }
        assertEquals(listOf(Reach.DirectFc, Reach.Lan), nodes.single().reaches)

        direct.state.value = emptyList()
        val after = repo.nodes.filterNotNull().first { it.single().reaches.size == 1 }
        assertEquals(listOf<Reach>(Reach.Lan), after.single().reaches)
    }

    @Test
    fun failingSourceDoesNotHideTheOthers() = runTest {
        val broken = object : NodeSource {
            override val nodes: Flow<List<NodeSummary>> = flow { throw IllegalStateException("boom") }
        }
        val lan = FakeSource(listOf(node(drone, listOf(Reach.Lan), name = "Drone")))
        val repo = NodeRepository(setOf(broken, lan), backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.nodes.collect {} }
        assertEquals("Drone", repo.nodes.filterNotNull().first { it.isNotEmpty() }.single().name)
    }
}
