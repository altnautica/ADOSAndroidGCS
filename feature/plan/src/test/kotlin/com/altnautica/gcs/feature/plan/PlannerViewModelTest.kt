package com.altnautica.gcs.feature.plan

import android.net.Uri
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionPlan as WirePlan
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionService
import com.altnautica.gcs.core.link.mission.MissionTransfer
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.PmtilesPack
import com.altnautica.gcs.core.map.TileBounds
import com.altnautica.gcs.core.map.offline.OfflineMapRepository
import com.altnautica.gcs.core.map.offline.OfflinePack
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.feature.plan.io.PlanDocuments
import com.altnautica.gcs.feature.plan.io.PlanFiles
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import androidx.lifecycle.viewModelScope

@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelTest {

    // ── Fakes ──

    private class FakeMissionService : MissionService {
        override val transfer = MutableStateFlow<MissionTransfer>(MissionTransfer.Idle)
        val uploads = mutableListOf<Pair<MissionType, List<MissionItem>>>()
        val cleared = mutableListOf<MissionType>()
        var failUpload: MissionType? = null
        var stored = mutableMapOf<MissionType, WirePlan>()
        var home: MissionItem? = null
        var startResult: CommandResult = CommandResult.Accepted
        var started = 0

        /** When set, the mission upload suspends here so tests can observe progress mid-transfer. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun upload(type: MissionType, items: List<MissionItem>): MissionResult<Unit> {
            items.forEachIndexed { i, _ -> transfer.value = MissionTransfer.InProgress(type, TransferDirection.Upload, i + 1, items.size) }
            if (type == MissionType.Mission) gate?.await()
            if (type == failUpload) {
                transfer.value = MissionTransfer.Failed(type, TransferDirection.Upload, MissionError.Timeout)
                return MissionResult.Failure(MissionError.Timeout)
            }
            uploads += type to items
            stored[type] = WirePlan(if (type == MissionType.Mission) home else null, items)
            transfer.value = MissionTransfer.Completed(type, TransferDirection.Upload)
            return MissionResult.Success(Unit)
        }

        override suspend fun download(type: MissionType): MissionResult<WirePlan> {
            val plan = stored[type] ?: WirePlan(null, emptyList())
            plan.items.forEachIndexed { i, _ -> transfer.value = MissionTransfer.InProgress(type, TransferDirection.Download, i + 1, plan.items.size) }
            return MissionResult.Success(plan)
        }

        override suspend fun clear(type: MissionType): MissionResult<Unit> {
            cleared += type
            stored.remove(type)
            return MissionResult.Success(Unit)
        }

        override suspend fun setCurrent(index: Int) = CommandResult.Accepted

        override suspend fun start(): CommandResult {
            started++
            return startResult
        }
    }

    private class FakeControl : VehicleControl {
        override val availableModes = MutableStateFlow<List<FlightMode>>(emptyList())
        val commands = mutableListOf<VehicleCommand>()
        var result: CommandResult = CommandResult.Accepted

        override suspend fun run(command: VehicleCommand): CommandResult {
            commands += command
            return result
        }
    }

    private class FakeDao : SavedPlanDao {
        val rows = MutableStateFlow<List<SavedPlanEntity>>(emptyList())
        override suspend fun upsert(plan: SavedPlanEntity) {
            rows.value = listOf(plan) + rows.value.filterNot { it.id == plan.id }
        }
        override fun observeAll(): Flow<List<SavedPlanEntity>> = rows
        override suspend fun getById(id: String) = rows.value.firstOrNull { it.id == id }
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
    }

    private class FakeOfflineMaps : OfflineMapRepository {
        override val packs = MutableStateFlow<List<OfflinePack>>(emptyList())
        override val pmtiles = MutableStateFlow<List<PmtilesPack>>(emptyList())
        override suspend fun refresh() = Result.success(Unit)
        override suspend fun download(name: String, provider: com.altnautica.gcs.core.map.MapProvider, bounds: TileBounds, minZoom: Int, maxZoom: Int) = Result.success(Unit)
        override fun setPaused(id: Long, paused: Boolean) = Unit
        override suspend fun delete(id: Long) = Result.success(Unit)
        override suspend fun importPmtiles(uri: Uri) = Result.failure<PmtilesPack>(UnsupportedOperationException())
        override suspend fun deletePmtiles(id: String) = Result.success(Unit)
    }

    private val node = DeviceId("drone-1")
    private var now = 10_000L
    private val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    private val position = MutableStateFlow<PositionFix?>(null)
    private val home = MutableStateFlow<HomePosition?>(null)
    private val gps = MutableStateFlow<GpsState?>(null)
    private val vfr = MutableStateFlow<Vfr?>(null)
    private val firmware = MutableStateFlow<FirmwareFamily?>(FirmwareFamily.ArduCopter)
    private val mission = FakeMissionService()
    private val control = FakeControl()
    private val servicesFlow = MutableStateFlow<VehicleServices?>(null)
    private val dao = FakeDao()

    private val telemetry = mockk<VehicleTelemetry>().also {
        every { it.heartbeat } returns heartbeat
        every { it.position } returns position
        every { it.home } returns home
        every { it.gps } returns gps
        every { it.vfr } returns vfr
    }

    private val vehicle = object : Vehicle {
        override val id = node
        override val reach: StateFlow<Reach> = MutableStateFlow(Reach.Lan)
        override val telemetry = this@PlannerViewModelTest.telemetry
        override val control: VehicleControl = this@PlannerViewModelTest.control
    }

    private val services = object : VehicleServices {
        override val vehicle = this@PlannerViewModelTest.vehicle
        override val firmware: StateFlow<FirmwareFamily?> = this@PlannerViewModelTest.firmware
        override val mission: MissionService = this@PlannerViewModelTest.mission
        override val params = mockk<com.altnautica.gcs.core.link.param.ParamService>()
        override val calibration = mockk<com.altnautica.gcs.core.link.calibration.CalibrationService>()
        override val logs = mockk<com.altnautica.gcs.core.link.log.LogService>()
        override val manualControl = mockk<com.altnautica.gcs.core.link.manual.ManualControlStreamer>()
    }

    private val provider = object : VehicleServicesProvider {
        override fun services(id: DeviceId): Flow<VehicleServices?> = servicesFlow.map { if (id == node) it else null }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlannerViewModel(
        servicesProvider = provider,
        nodeSources = emptySet(),
        savedPlans = dao,
        documents = mockk<PlanDocuments>(),
        clock = { now },
        offlineMaps = FakeOfflineMaps(),
    )

    private suspend fun TestScope.withViewModel(block: suspend TestScope.(PlannerViewModel) -> Unit) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    private fun connect(vm: PlannerViewModel, armed: Boolean = false) {
        heartbeat.value = HeartbeatState(armed, FlightMode(5, "LOITER"), vehicleType = 2, autopilot = 3, tMs = now)
        position.value = PositionFix(12.0, 77.0, 920f, 0f, 0f, 0f, 0f, now)
        home.value = HomePosition(12.0, 77.0, 910f, now)
        gps.value = GpsState(GpsFix.Fix3D, 12, 0.8f, now)
        servicesFlow.value = services
        vm.selectVehicle(node)
    }

    private val a = GeoPoint(12.0, 77.0)
    private val b = GeoPoint(12.001, 77.0)
    private val c = GeoPoint(12.002, 77.001)

    // ── Editing ──

    @Test
    fun `taps add a takeoff then waypoints at the default altitude`() = runTest {
        withViewModel { vm ->
            vm.setDefaultAltitude(45f)
            vm.onMapTap(a)
            vm.onMapTap(b)

            val items = vm.state.value.plan.items
            assertEquals(listOf(MavCmd.NAV_TAKEOFF, MavCmd.NAV_WAYPOINT), items.map { it.command })
            assertEquals(listOf(45f, 45f), items.map { it.z })
            assertEquals(listOf(a, b), items.map { it.position })
            assertEquals(Selection.Item(items[1].id), vm.state.value.selection)
            assertTrue(vm.state.value.dirty)
        }
    }

    @Test
    fun `move, insert and delete edit the item list`() = runTest {
        withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(c)
            val (first, second) = vm.state.value.plan.items

            vm.moveItem(second.id, b)
            assertEquals(b, vm.state.value.plan.items[1].position)

            vm.insertNear(second.id, after = false)
            val inserted = vm.state.value.plan.items[1]
            assertEquals(3, vm.state.value.plan.items.size)
            assertEquals(GeoPoint((a.latDeg + b.latDeg) / 2, (a.lonDeg + b.lonDeg) / 2), inserted.position)

            vm.insertNear(second.id, after = true)
            assertEquals(4, vm.state.value.plan.items.size)
            assertEquals(second.id, vm.state.value.plan.items[2].id)

            vm.insertCommandAfter(first.id, com.altnautica.gcs.feature.plan.model.PlanCommand.ChangeSpeed)
            assertEquals(MavCmd.DO_CHANGE_SPEED, vm.state.value.plan.items[1].command)
            assertNull(vm.state.value.plan.items[1].position)

            vm.select(Selection.Item(inserted.id))
            vm.deleteItem(inserted.id)
            assertFalse(vm.state.value.plan.items.any { it.id == inserted.id })
            assertNull(vm.state.value.selection)
        }
    }

    @Test
    fun `changing altitude reference converts through home altitude`() = runTest {
        withViewModel { vm ->
            connect(vm)
            vm.onMapTap(a)
            val id = vm.state.value.plan.items.single().id
            vm.setAltitude(id, 50f)

            vm.setAltitudeRef(id, AltitudeRef.Msl)
            assertEquals(MavFrame.GLOBAL, vm.state.value.plan.items.single().frame)
            assertEquals(960f, vm.state.value.plan.items.single().z)

            vm.setAltitudeRef(id, AltitudeRef.Relative)
            assertEquals(MavFrame.GLOBAL_RELATIVE_ALT, vm.state.value.plan.items.single().frame)
            assertEquals(50f, vm.state.value.plan.items.single().z)
        }
    }

    @Test
    fun `without a home altitude the reference changes but the number stays`() = runTest {
        withViewModel { vm ->
            vm.onMapTap(a)
            val id = vm.state.value.plan.items.single().id

            vm.setAltitudeRef(id, AltitudeRef.Msl)

            assertEquals(AltitudeRef.Msl, vm.state.value.plan.items.single().altitudeRef)
            assertEquals(30f, vm.state.value.plan.items.single().z)
        }
    }

    @Test
    fun `survey outline previews and appends lanes`() = runTest {
        withViewModel { vm ->
            vm.setTool(EditTool.Survey)
            vm.onMapTap(GeoPoint(0.0, 0.0))
            vm.onMapTap(GeoPoint(0.0, 0.002))
            assertNull(vm.state.value.surveyPreview)
            vm.onMapTap(GeoPoint(0.001, 0.002))
            vm.onMapTap(GeoPoint(0.001, 0.0))
            assertTrue(vm.state.value.surveyPreview!!.lineCount > 0)

            vm.applySurvey()

            assertTrue(vm.state.value.plan.items.any { it.command == MavCmd.DO_SET_CAM_TRIGG_DIST })
            assertTrue(vm.state.value.survey.isEmpty())
            assertEquals(EditTool.Waypoint, vm.state.value.tool)
        }
    }

    @Test
    fun `fence outline closes into a zone and rally taps add points`() = runTest {
        withViewModel { vm ->
            vm.setTool(EditTool.FencePolygon)
            vm.setFenceDraftInclusion(false)
            listOf(a, b, c).forEach(vm::onMapTap)
            vm.closeFenceDraft()
            vm.setTool(EditTool.Rally)
            vm.onMapTap(b)

            val plan = vm.state.value.plan
            val zone = plan.fence.single() as com.altnautica.gcs.feature.plan.model.FenceZone.Polygon
            assertFalse(zone.inclusion)
            assertEquals(listOf(a, b, c), zone.points)
            assertEquals(b, plan.rally.single().position)
            assertTrue(vm.state.value.fenceDraft.isEmpty())
        }
    }

    // ── Files and library ──

    @Test
    fun `importing a plan file replaces the plan and marks it unsaved`() = runTest {
        withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            val text = vm.exportText(com.altnautica.gcs.feature.plan.io.PlanFileFormat.Waypoints)
            vm.newPlan()

            vm.importText("field.waypoints", text)

            assertEquals(listOf(a, b), vm.state.value.plan.items.map { it.position })
            assertEquals("field", vm.state.value.name)
            assertTrue(vm.state.value.dirty)
            assertEquals(PlannerMessage.Imported("field", 0), vm.state.value.message)
        }
    }

    @Test
    fun `broken files report why`() = runTest {
        withViewModel { vm ->
            vm.importText("x.plan", "{\"fileType\":\"Nope\"}")

            assertEquals(
                PlannerMessage.ImportFailed(com.altnautica.gcs.feature.plan.io.PlanFormatException.Reason.NotPlan, "NotPlan"),
                vm.state.value.message,
            )
        }
    }

    @Test
    fun `save stores the plan json and load restores it`() = runTest {
        withViewModel { vm ->
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.setName("North")
            vm.save("Untitled")

            val saved = dao.rows.value.single()
            assertEquals("North", saved.name)
            assertEquals(2, saved.itemCount)
            assertFalse(vm.state.value.dirty)
            assertEquals(2, PlanFiles.parsePlan(saved.planJson) { 1L }.plan.items.size)

            vm.newPlan()
            vm.load(saved.id)

            assertEquals(listOf(a, b), vm.state.value.plan.items.map { it.position })
            assertEquals(saved.id, vm.state.value.savedId)
            assertFalse(vm.state.value.dirty)
        }
    }

    // ── Vehicle transfer ──

    @Test
    fun `link states follow the selected vehicle`() = runTest {
        withViewModel { vm ->
            assertEquals(VehicleLink.None, vm.state.value.link)
            vm.selectVehicle(node)
            assertEquals(VehicleLink.Unsupported, vm.state.value.link)

            connect(vm)
            val link = vm.state.value.link as VehicleLink.Connected
            assertTrue(link.linkFresh)
            assertTrue(link.homeSlot)

            now += 3_500
            testScheduler.advanceTimeBy(1_100)
            runCurrent()
            assertFalse((vm.state.value.link as VehicleLink.Connected).linkFresh)
        }
    }

    @Test
    fun `upload runs mission, fence, rally and reports progress then done`() = runTest {
        withViewModel { vm ->
            connect(vm)
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.setTool(EditTool.Rally)
            vm.onMapTap(c)
            val gate = CompletableDeferred<Unit>()
            mission.gate = gate

            vm.upload()

            assertEquals(TransferState.Running(TransferDirection.Upload, MissionType.Mission, 2, 2), vm.state.value.transfer)
            assertTrue(vm.state.value.transferring)
            gate.complete(Unit)
            runCurrent()

            assertEquals(TransferState.Done(TransferDirection.Upload, 2), vm.state.value.transfer)
            assertEquals(listOf(MissionType.Mission, MissionType.Rally), mission.uploads.map { it.first })
            assertEquals(listOf(MissionType.Fence), mission.cleared)
            assertTrue(vm.state.value.inSyncWithVehicle)

            vm.moveItem(vm.state.value.plan.items[1].id, c)
            assertFalse(vm.state.value.inSyncWithVehicle)
        }
    }

    @Test
    fun `failed fence upload stops the sequence and names the list`() = runTest {
        withViewModel { vm ->
            connect(vm)
            vm.onMapTap(a)
            vm.setTool(EditTool.FenceCircle)
            vm.onMapTap(b)
            mission.failUpload = MissionType.Fence

            vm.upload()

            assertEquals(TransferState.Failed(TransferDirection.Upload, MissionType.Fence, MissionError.Timeout), vm.state.value.transfer)
            assertEquals(listOf(MissionType.Mission), mission.uploads.map { it.first })
            assertFalse(vm.state.value.inSyncWithVehicle)
            vm.dismissTransfer()
            assertEquals(TransferState.Idle, vm.state.value.transfer)
        }
    }

    @Test
    fun `download asks before replacing unsaved edits then loads the vehicle mission`() = runTest {
        withViewModel { vm ->
            connect(vm)
            mission.home = MissionItem.global(MavCmd.NAV_WAYPOINT, 12.0, 77.0, 910f, frame = 5)
            mission.stored[MissionType.Mission] = WirePlan(
                mission.home,
                listOf(
                    MissionItem.global(MavCmd.NAV_TAKEOFF, 12.0, 77.0, 20f),
                    MissionItem.global(MavCmd.NAV_WAYPOINT, 12.001, 77.0, 25f),
                    MissionItem.global(MavCmd.NAV_WAYPOINT, 12.002, 77.001, 25f),
                ),
            )
            vm.onMapTap(c)

            vm.download()
            assertTrue(vm.state.value.confirmReplaceWithDownload)
            assertEquals(1, vm.state.value.plan.items.size)

            vm.confirmDownload()

            assertEquals(TransferState.Done(TransferDirection.Download, 3), vm.state.value.transfer)
            assertEquals(listOf(MavCmd.NAV_TAKEOFF, MavCmd.NAV_WAYPOINT, MavCmd.NAV_WAYPOINT), vm.state.value.plan.items.map { it.command })
            assertEquals(GeoPoint(12.0, 77.0), vm.state.value.plan.home?.position)
            assertTrue(vm.state.value.inSyncWithVehicle)
        }
    }

    @Test
    fun `empty vehicle mission keeps the open plan`() = runTest {
        withViewModel { vm ->
            connect(vm)
            vm.download()

            assertEquals(TransferState.Done(TransferDirection.Download, 0), vm.state.value.transfer)
            assertEquals(PlannerMessage.VehicleHasNoMission, vm.state.value.message)
        }
    }

    // ── Start mission ──

    @Test
    fun `start mission needs the plan on the vehicle, arms, starts and routes to the cockpit`() = runTest {
        withViewModel { vm ->
            connect(vm, armed = false)
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.openStartMission()
            assertFalse(vm.state.value.startSheet!!.canSlide)
            vm.dismissStartMission()

            vm.upload()
            vm.openStartMission()
            val sheet = vm.state.value.startSheet!!
            assertTrue(sheet.checks.all { it.ok })
            assertTrue(sheet.canSlide)

            vm.startMission()

            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), control.commands)
            assertEquals(1, mission.started)
            assertNull(vm.state.value.startSheet)
            assertEquals(node, vm.state.value.missionStartedFor)
            vm.onMissionStartHandled()
            assertNull(vm.state.value.missionStartedFor)
        }
    }

    @Test
    fun `soft checklist failures need an override and refusals stay on the sheet`() = runTest {
        withViewModel { vm ->
            connect(vm, armed = true)
            vm.onMapTap(a)
            vm.upload()
            gps.value = GpsState(GpsFix.Fix2D, 5, 3f, now)
            mission.startResult = CommandResult.Rejected(4, "denied")

            vm.openStartMission()
            assertTrue(vm.state.value.startSheet!!.needsOverride)
            assertFalse(vm.state.value.startSheet!!.canSlide)
            vm.setChecklistOverride(true)
            assertTrue(vm.state.value.startSheet!!.canSlide)

            vm.startMission()

            assertTrue(control.commands.isEmpty())
            assertEquals(StartError.StartRefused(CommandResult.Rejected(4, "denied")), vm.state.value.startSheet!!.error)
            assertNull(vm.state.value.missionStartedFor)
        }
    }

    @Test
    fun `arming refusal does not start the mission`() = runTest {
        withViewModel { vm ->
            connect(vm, armed = false)
            vm.onMapTap(a)
            vm.upload()
            control.result = CommandResult.Rejected(1, "prearm")
            vm.openStartMission()

            vm.startMission()

            assertEquals(0, mission.started)
            assertEquals(StartError.ArmRefused(CommandResult.Rejected(1, "prearm")), vm.state.value.startSheet!!.error)
        }
    }
}
