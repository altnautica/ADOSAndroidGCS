package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.map.DragPhase
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.map.MapProvider
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.model.PlanIssueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class PlannerEditingTest {

    private val h = PlannerHarness()
    private val a = GeoPoint(12.0, 77.0)
    private val b = GeoPoint(12.001, 77.0)
    private val c = GeoPoint(12.002, 77.001)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Drag and undo ──

    @Test
    fun `a drag previews every move and commits one undo step on end`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            val id = vm.state.value.plan.items[1].id
            val ref = MapFeatureRef(MapFeatureType.Waypoint, id.toString())
            val before = vm.state.value.plan
            val moves = (1..10).map { i -> GeoPoint(12.001 + i * 1e-4, 77.0) }

            vm.onFeatureDrag(ref, b, DragPhase.Start)
            moves.forEach { vm.onFeatureDrag(ref, it, DragPhase.Move) }
            assertEquals(before, vm.state.value.plan)
            assertEquals(moves.last(), vm.state.value.displayPlan.items[1].position)

            vm.onFeatureDrag(ref, c, DragPhase.End)
            assertNull(vm.state.value.dragPreview)
            assertEquals(c, vm.state.value.plan.items[1].position)

            vm.undo()
            assertEquals(before, vm.state.value.plan)
            assertTrue(vm.state.value.canRedo)
            vm.redo()
            assertEquals(c, vm.state.value.plan.items[1].position)
        }
    }

    @Test
    fun `undo walks back each edit and a new edit clears redo`() = runTest {
        h.withViewModel { vm ->
            assertFalse(vm.state.value.canUndo)
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.undo()
            assertEquals(1, vm.state.value.plan.items.size)
            assertNull(vm.state.value.selection)
            vm.undo()
            assertTrue(vm.state.value.plan.items.isEmpty())
            assertFalse(vm.state.value.canUndo)
            assertFalse(vm.state.value.dirty)

            vm.redo()
            assertTrue(vm.state.value.canRedo)
            vm.onMapTap(c)
            assertFalse(vm.state.value.canRedo)
        }
    }

    @Test
    fun `loading or starting a new plan clears the history`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.newPlan()
            assertFalse(vm.state.value.canUndo)
        }
    }

    @Test
    fun `controller grab drags the selected rally point`() = runTest {
        h.withViewModel { vm ->
            vm.addRallyAt(a)
            vm.grabSelected(a, DragPhase.Start)
            vm.grabSelected(b, DragPhase.Move)
            vm.grabSelected(c, DragPhase.End)
            assertEquals(c, vm.state.value.plan.rally.single().position)
        }
    }

    // ── Item edits ──

    @Test
    fun `command swaps stay within the positional class`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            val id = vm.state.value.plan.items[1].id

            vm.setCommand(id, PlanCommand.ChangeSpeed)
            assertEquals(MavCmd.NAV_WAYPOINT, vm.state.value.plan.items[1].command)

            vm.setCommand(id, PlanCommand.Land)
            assertEquals(MavCmd.NAV_LAND, vm.state.value.plan.items[1].command)
            assertEquals(b, vm.state.value.plan.items[1].position)
        }
    }

    @Test
    fun `param 5 keeps double precision`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            val id = vm.state.value.plan.items.single().id
            vm.setParam(id, 5, 12.345678912)
            assertEquals(12.345678912, vm.state.value.plan.items.single().x, 0.0)
        }
    }

    // ── Discard and delete confirms ──

    @Test
    fun `discarding actions ask first while the plan is dirty`() = runTest {
        h.withViewModel { vm ->
            vm.requestDiscard(DiscardAction.Import)
            assertTrue(vm.state.value.pickImport)
            vm.importPickerShown()

            vm.onMapTap(a)
            vm.requestDiscard(DiscardAction.New)
            assertEquals(DiscardAction.New, vm.state.value.pendingDiscard)
            assertEquals(1, vm.state.value.plan.items.size)
            vm.cancelDiscard()
            assertNull(vm.state.value.pendingDiscard)
            assertEquals(1, vm.state.value.plan.items.size)

            vm.requestDiscard(DiscardAction.Leave)
            vm.confirmDiscard()
            assertTrue(vm.state.value.leaveApproved)
            vm.leaveHandled()

            vm.requestDiscard(DiscardAction.New)
            vm.confirmDiscard()
            assertTrue(vm.state.value.plan.items.isEmpty())
            assertFalse(vm.state.value.dirty)
        }
    }

    @Test
    fun `library delete waits for the confirm`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.save("Field")
            val id = h.dao.rows.value.single().id

            vm.requestDeleteSaved(id)
            assertEquals(id, vm.state.value.pendingDelete?.id)
            assertEquals(1, h.dao.rows.value.size)
            vm.cancelDeleteSaved()
            assertEquals(1, h.dao.rows.value.size)

            vm.requestDeleteSaved(id)
            vm.confirmDeleteSaved()
            assertTrue(h.dao.rows.value.isEmpty())
            assertNull(vm.state.value.pendingDelete)
        }
    }

    // ── Validation and verify ──

    @Test
    fun `a blocking issue keeps the plan off the vehicle`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(a)
            vm.setTool(EditTool.FenceCircle)
            vm.onMapTap(c)
            assertTrue(vm.state.value.issues.any { it.kind == PlanIssueKind.OutsideInclusionFence })

            vm.upload()

            assertTrue(h.mission.uploads.isEmpty())
            assertEquals(TransferState.Idle, vm.state.value.transfer)
        }
    }

    @Test
    fun `a read-back mismatch fails the upload and retry succeeds`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            vm.onMapTap(a)
            vm.onMapTap(b)
            h.mission.tamper = setOf(MissionType.Mission)

            vm.upload()

            assertEquals(
                TransferState.Failed(TransferDirection.Upload, MissionType.Mission, MissionError.VerifyMismatch(MissionType.Mission)),
                vm.state.value.transfer,
            )
            assertNull(vm.state.value.vehiclePlan)
            assertFalse(vm.state.value.inSyncWithVehicle)

            h.mission.tamper = emptySet()
            vm.retryUpload()

            assertEquals(TransferState.Done(TransferDirection.Upload, 2), vm.state.value.transfer)
            assertTrue(vm.state.value.inSyncWithVehicle)
            assertEquals(listOf(MissionType.Mission, MissionType.Mission, MissionType.Fence, MissionType.Rally), h.mission.downloads)
        }
    }

    @Test
    fun `stats sum the legs between positional items`() = runTest {
        h.withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            val stats = vm.state.value.stats
            assertEquals(2, stats.itemCount)
            assertEquals(111.2, stats.distanceM, 0.5)
            assertEquals(30f, stats.maxAltRelM)
        }
    }

    // ── Basemap ──

    @Test
    fun `the basemap choice survives a new planner`() = runTest {
        h.withViewModel { vm ->
            assertEquals(MapBasemap.Online(MapProvider.Osm), vm.state.value.basemap)
            vm.setBasemap(MapBasemap.Online(MapProvider.EsriWorldImagery))
        }
        h.withViewModel { vm ->
            assertEquals(MapBasemap.Online(MapProvider.EsriWorldImagery), vm.state.value.basemap)
        }
    }

    @Test
    fun `a stored pack that no longer exists falls back to the default`() {
        assertEquals(MapBasemap.Online(MapProvider.Osm), BasemapCodec.decode("pmtiles:gone.pmtiles", emptyList()))
        assertEquals(MapBasemap.Online(MapProvider.OpenTopoMap), BasemapCodec.decode(BasemapCodec.encode(MapBasemap.Online(MapProvider.OpenTopoMap)), emptyList()))
    }
}
