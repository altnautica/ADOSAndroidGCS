package com.altnautica.gcs.feature.plan

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.plan.PlannerSettingsRepository
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionPlan as WirePlan
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.UploadReceipt
import com.altnautica.gcs.core.link.mission.MissionService
import com.altnautica.gcs.core.link.mission.MissionTransfer
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Scripted mission protocol: stores what is uploaded and returns it on download. */
internal class FakeMissionService : MissionService {
    override val transfer = MutableStateFlow<MissionTransfer>(MissionTransfer.Idle)
    val uploads = mutableListOf<Pair<MissionType, List<MissionItem>>>()
    val downloads = mutableListOf<MissionType>()
    val cleared = mutableListOf<MissionType>()
    var failUpload: MissionType? = null
    var stored = mutableMapOf<MissionType, WirePlan>()
    var home: MissionItem? = null
    var startResult: CommandResult = CommandResult.Accepted
    var started = 0

    /** When set, read-backs of these lists come back changed, as a vehicle that mangled them would. */
    var tamper: Set<MissionType> = emptySet()

    /** When set, the mission upload suspends here so tests can observe progress mid-transfer. */
    var gate: CompletableDeferred<Unit>? = null

    /** The plan id the scripted vehicle returns for a mission upload (0: no plan ids). */
    var opaqueId: Long = 0

    override suspend fun upload(type: MissionType, items: List<MissionItem>): MissionResult<UploadReceipt> {
        items.forEachIndexed { i, _ -> transfer.value = MissionTransfer.InProgress(type, TransferDirection.Upload, i + 1, items.size) }
        if (type == MissionType.Mission) gate?.await()
        if (type == failUpload) {
            transfer.value = MissionTransfer.Failed(type, TransferDirection.Upload, MissionError.Timeout)
            return MissionResult.Failure(MissionError.Timeout)
        }
        uploads += type to items
        stored[type] = WirePlan(if (type == MissionType.Mission) home else null, items)
        transfer.value = MissionTransfer.Completed(type, TransferDirection.Upload)
        return MissionResult.Success(UploadReceipt(if (type == MissionType.Mission) opaqueId else 0))
    }

    override suspend fun download(type: MissionType): MissionResult<WirePlan> {
        downloads += type
        val plan = stored[type] ?: WirePlan(null, emptyList())
        plan.items.forEachIndexed { i, _ -> transfer.value = MissionTransfer.InProgress(type, TransferDirection.Download, i + 1, plan.items.size) }
        if (type in tamper) return MissionResult.Success(plan.copy(items = plan.items.map { it.copy(z = it.z + 5f) }))
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

internal class FakeControl : VehicleControl {
    override val availableModes = MutableStateFlow<List<FlightMode>>(emptyList())
    val commands = mutableListOf<VehicleCommand>()
    var result: CommandResult = CommandResult.Accepted

    override suspend fun run(command: VehicleCommand): CommandResult {
        commands += command
        return result
    }
}

internal class FakeDao : SavedPlanDao {
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

internal class FakeOfflineMaps : OfflineMapRepository {
    override val packs = MutableStateFlow<List<OfflinePack>>(emptyList())
    override val pmtiles = MutableStateFlow<List<PmtilesPack>>(emptyList())
    override suspend fun refresh() = Result.success(Unit)
    override suspend fun download(name: String, provider: com.altnautica.gcs.core.map.MapProvider, bounds: TileBounds, minZoom: Int, maxZoom: Int) = Result.success(Unit)
    override fun setPaused(id: Long, paused: Boolean) = Unit
    override suspend fun delete(id: Long) = Result.success(Unit)
    override suspend fun importPmtiles(uri: Uri) = Result.failure<PmtilesPack>(UnsupportedOperationException())
    override suspend fun deletePmtiles(id: String) = Result.success(Unit)
}

/** One vehicle with scripted telemetry and mission protocol, plus the planner's stores. */
internal class PlannerHarness {
    val node = DeviceId("drone-1")
    var now = 10_000L
    val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    val position = MutableStateFlow<PositionFix?>(null)
    val home = MutableStateFlow<HomePosition?>(null)
    val gps = MutableStateFlow<GpsState?>(null)
    val vfr = MutableStateFlow<Vfr?>(null)
    val firmware = MutableStateFlow<FirmwareFamily?>(FirmwareFamily.ArduCopter)
    val mission = FakeMissionService()
    val control = FakeControl()
    val servicesFlow = MutableStateFlow<VehicleServices?>(null)
    val dao = FakeDao()
    val prefs = MemoryPreferences()
    val drafts = MemoryDraftStore()
    val paramSnapshot = MutableStateFlow(com.altnautica.gcs.core.link.param.ParamSnapshot.EMPTY)

    /** Sets the vehicle's loaded parameters. */
    fun params(vararg values: Pair<String, Double>) {
        paramSnapshot.value = com.altnautica.gcs.core.link.param.ParamSnapshot(
            values.size,
            values.size,
            values.withIndex().associate { (i, v) -> v.first to com.altnautica.gcs.core.link.param.ParamValue(v.first, v.second, 9, i) },
        )
    }

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
        override val telemetry = this@PlannerHarness.telemetry
        override val control: VehicleControl = this@PlannerHarness.control
    }

    val services = object : VehicleServices {
        override val vehicle = this@PlannerHarness.vehicle
        override val firmware: StateFlow<FirmwareFamily?> = this@PlannerHarness.firmware
        override val mission: MissionService = this@PlannerHarness.mission
        override val params = mockk<com.altnautica.gcs.core.link.param.ParamService>().also {
            every { it.snapshot } returns this@PlannerHarness.paramSnapshot
        }
        override val calibration = mockk<com.altnautica.gcs.core.link.calibration.CalibrationService>()
        override val logs = mockk<com.altnautica.gcs.core.link.log.LogService>()
        override val manualControl = mockk<com.altnautica.gcs.core.link.manual.ManualControlStreamer>()
    }

    private val provider = object : VehicleServicesProvider {
        override fun services(id: DeviceId): Flow<VehicleServices?> = servicesFlow.map { if (id == node) it else null }
    }

    /** The vehicle's mirrored plan as its agent would serve it; null: no agent path. */
    val mirror = MutableStateFlow<com.altnautica.gcs.core.agent.mission.MissionMirror?>(null)

    fun viewModel() = PlannerViewModel(
        servicesProvider = provider,
        nodeSources = emptySet(),
        savedPlans = dao,
        documents = mockk<PlanDocuments>(),
        clock = { now },
        offlineMaps = FakeOfflineMaps(),
        plannerSettings = PlannerSettingsRepository(prefs),
        inputRouter = mockk(relaxed = true),
        mirrorSource = { mirror },
        draftStore = drafts,
    )

    suspend fun withViewModel(block: suspend (PlannerViewModel) -> Unit) {
        val vm = viewModel()
        try {
            block(vm)
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    fun connect(vm: PlannerViewModel, armed: Boolean = false) {
        heartbeat.value = HeartbeatState(armed, FlightMode(5, "LOITER"), vehicleType = 2, autopilot = 3, tMs = now)
        position.value = PositionFix(12.0, 77.0, 920f, 0f, 0f, 0f, 0f, now)
        home.value = HomePosition(12.0, 77.0, 910f, now)
        gps.value = GpsState(GpsFix.Fix3D, 12, 0.8f, now)
        servicesFlow.value = services
        vm.selectVehicle(node)
    }
}

/** In-memory draft store: what a process death would leave on disk. */
internal class MemoryDraftStore : com.altnautica.gcs.feature.plan.io.PlanDraftStore {
    var draft: com.altnautica.gcs.feature.plan.io.PlanDraft? = null

    /**
     * When set, a draft write suspends here, then lands even if its caller was cancelled
     * meanwhile, as a disk write already under way does. Clears never wait.
     */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun read() = draft

    override suspend fun write(draft: com.altnautica.gcs.feature.plan.io.PlanDraft?) {
        val gate = gate
        if (draft != null && gate != null) {
            withContext(NonCancellable) {
                gate.await()
                this@MemoryDraftStore.draft = draft
            }
        } else {
            this.draft = draft
        }
    }
}
