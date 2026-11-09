package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mission.MissionError
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionMirrorSource
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionTransfer
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.link.mission.UploadReceipt
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.model.MissionState
import kotlinx.coroutines.flow.first
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.MissionWire
import com.altnautica.gcs.feature.plan.model.MissionVerify
import com.altnautica.gcs.feature.plan.model.PlanHome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Vehicle side of the planner: tracks the selected vehicle's link, transfers plans to and
 * from it, and runs the start-mission slide. Jobs run in [scope]; state lands in [state].
 */
internal class PlannerVehicleController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlannerUiState>,
    private val servicesProvider: VehicleServicesProvider,
    private val clock: ElapsedClock,
    private val nextId: () -> Long,
    private val mirrorSource: MissionMirrorSource,
    private val replacePlan: (plan: MissionPlan, name: String, savedId: String?, dirty: Boolean) -> Unit,
) {
    private var services: VehicleServices? = null
    private var vehicleJob: Job? = null
    private var transferJob: Job? = null
    private var startJob: Job? = null
    private var mirrorJob: Job? = null

    /** The vehicle's mirrored plan as last read, and this device's receipt for the plan it put there. */
    private var mirror: MissionMirror? = null
    private var receipt: PlanReceipt? = null

    // ── Vehicle ──────────────────────────────────────────────

    /** Keeps [PlannerUiState.nodes] to the vehicles among [nodeSources]. */
    fun observeNodes(nodeSources: Set<NodeSource>) {
        scope.launch {
            allNodes(nodeSources).collect { nodes ->
                val vehicles = nodes.filter { it.profile == NodeProfile.Drone || Reach.DirectFc in it.reaches }
                state.update { s -> s.copy(nodes = vehicles, nodeName = vehicles.firstOrNull { it.id == s.nodeId }?.name ?: s.nodeName) }
            }
        }
    }

    private fun allNodes(nodeSources: Set<NodeSource>): Flow<List<NodeSummary>> {
        if (nodeSources.isEmpty()) return flowOf(emptyList())
        return combine(nodeSources.map { it.nodes }) { lists -> lists.flatMap { it.toList() }.distinctBy { it.id } }
    }

    /** Plans for [nodeId]; null detaches from any vehicle. */
    fun selectVehicle(nodeId: DeviceId?) {
        if (nodeId == state.value.nodeId && vehicleJob != null) return
        vehicleJob?.cancel()
        mirrorJob?.cancel()
        mirrorJob = null
        mirror = null
        receipt = null
        transferJob?.cancel()
        transferJob = null
        startJob?.cancel()
        startJob = null
        services = null
        state.update { s ->
            s.copy(
                nodeId = nodeId,
                nodeName = s.nodes.firstOrNull { it.id == nodeId }?.name,
                link = if (nodeId == null) VehicleLink.None else VehicleLink.Connecting,
                vehiclePlan = null,
                drone = null,
                transfer = TransferState.Idle,
                startSheet = null,
            )
        }
        if (nodeId == null) {
            vehicleJob = null
            return
        }
        vehicleJob = scope.launch {
            servicesProvider.services(nodeId).collectLatest { svc ->
                services = svc
                if (svc == null) {
                    state.update { it.copy(link = VehicleLink.Unsupported) }
                    return@collectLatest
                }
                observeVehicle(svc)
            }
        }
        mirrorJob = scope.launch {
            mirrorSource.observe(nodeId).collect { m ->
                mirror = m
                refreshDrone()
            }
        }
    }

    /**
     * Recomputes [PlannerUiState.drone]: the mirrored plan, and whether it differs from
     * this device's. A vehicle plan with the open plan's items counts as the same plan.
     */
    private fun refreshDrone() {
        val m = mirror
        if (m == null) {
            state.update { it.copy(drone = null) }
            return
        }
        val dronePlan = PlanIdentity.planOf(m, nextId)
        state.update { s ->
            val sameItems = dronePlan != null && dronePlan.items.map { it.command to it.position } == s.plan.items.map { it.command to it.position }
            val differs = dronePlan != null && !sameItems && !PlanIdentity.matches(m, receipt)
            s.copy(drone = DroneMissionUi(dronePlan, m.status?.state ?: MissionState.None, differs))
        }
    }

    /** Replaces the open plan with the vehicle's mirrored plan ("Load from drone"). */
    fun loadFromDrone() {
        val m = mirror ?: return
        val plan = state.value.drone?.plan ?: return
        if (state.value.transferring) return
        replacePlan(plan, state.value.nodeName.orEmpty(), null, true)
        receipt = PlanIdentity.receiptOf(m)
        state.update { it.copy(vehiclePlan = plan) }
        refreshDrone()
    }

    private suspend fun observeVehicle(svc: VehicleServices) {
        val t = svc.vehicle.telemetry
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
        combine(t.heartbeat, t.position, t.home, t.gps, svc.firmware) { hb, pos, home, gps, fw -> Snapshot(hb?.armed, hb?.tMs, hb?.vehicleType, pos, home, gps?.fix, fw) }
            .combine(t.vfr) { snap, vfr -> snap to vfr?.headingDeg }
            .combine(ticker) { pair, _ -> pair }
            .map { (snap, heading) ->
                VehicleLink.Connected(
                    armed = snap.armed,
                    linkFresh = snap.heartbeatMs != null && clock.nowMs() - snap.heartbeatMs <= PlannerViewModel.LINK_FRESH_MS,
                    position = snap.position?.let { GeoPoint(it.latDeg, it.lonDeg) },
                    headingDeg = heading,
                    home = snap.home?.let { GeoPoint(it.latDeg, it.lonDeg) },
                    homeAltMslM = snap.home?.altMslM,
                    gpsFix = snap.fix,
                    homeSlot = snap.firmware != null && snap.firmware != FirmwareFamily.Px4 && snap.firmware != FirmwareFamily.Unknown,
                    vehicleType = snap.vehicleType,
                )
            }
            .distinctUntilChanged()
            .collect { link ->
                state.update { s -> s.copy(link = link, startSheet = s.startSheet?.let { sheet -> refreshChecks(sheet, s.copy(link = link)) }) }
            }
    }

    private data class Snapshot(
        val armed: Boolean?,
        val heartbeatMs: Long?,
        val vehicleType: Int?,
        val position: PositionFix?,
        val home: HomePosition?,
        val fix: GpsFix?,
        val firmware: FirmwareFamily?,
    )

    // ── Vehicle transfer ─────────────────────────────────────

    /**
     * Uploads mission, then fence, then rally (an empty fence or rally list clears it on the
     * vehicle), then reads each list back and compares it with what was sent. The plan counts
     * as on the vehicle only when every list matches. Blocking plan issues stop the upload.
     */
    fun upload() {
        val svc = services ?: return
        val s = state.value
        if (s.transferring || s.issues.any { it.kind.blocking }) return
        val plan = s.plan
        val homeSlot = homeSlot()
        val wire = MissionWire.toWire(plan, homeSlot)
        val nodeId = s.nodeId
        runTransfer(svc, TransferDirection.Upload) {
            val steps = listOf(MissionType.Mission to wire.mission, MissionType.Fence to wire.fence, MissionType.Rally to wire.rally)
            var opaqueId = 0L
            for ((type, items) in steps) {
                val result = if (items.isEmpty() && type != MissionType.Mission) svc.mission.clear(type) else svc.mission.upload(type, items)
                if (result is MissionResult.Failure) {
                    state.update { it.copy(transfer = TransferState.Failed(TransferDirection.Upload, type, result.error)) }
                    return@runTransfer
                }
                if (type == MissionType.Mission) opaqueId = ((result as MissionResult.Success).value as UploadReceipt).opaqueId
            }
            for ((type, sent) in steps) {
                state.update { it.copy(transfer = TransferState.Verifying(type)) }
                val error = when (val readBack = svc.mission.download(type)) {
                    is MissionResult.Failure -> readBack.error
                    is MissionResult.Success -> if (MissionVerify.matches(sent, readBack.value.items)) null else MissionError.VerifyMismatch(type)
                }
                if (error != null) {
                    state.update { it.copy(transfer = TransferState.Failed(TransferDirection.Upload, type, error)) }
                    return@runTransfer
                }
            }
            // The agent's upload counter right after this upload: a later change means another client uploaded.
            val seen = nodeId?.let { id -> mirrorSource.observe(id).first()?.uploadsSeen }
            receipt = PlanReceipt(opaqueId, wire.mission.size + if (homeSlot) 1 else 0, seen)
            state.update { it.copy(transfer = TransferState.Done(TransferDirection.Upload, wire.mission.size), vehiclePlan = plan) }
            refreshDrone()
        }
    }

    /** Downloads from the vehicle; asks first when the open plan has unsaved edits. */
    fun download() {
        if (services == null || state.value.transferring) return
        if (state.value.dirty && !state.value.plan.isEmpty) {
            state.update { it.copy(confirmReplaceWithDownload = true) }
            return
        }
        confirmDownload()
    }

    fun cancelDownload() = state.update { it.copy(confirmReplaceWithDownload = false) }

    fun confirmDownload() {
        val svc = services ?: return
        state.update { it.copy(confirmReplaceWithDownload = false) }
        val homeSlot = homeSlot()
        runTransfer(svc, TransferDirection.Download) {
            val mission = when (val r = svc.mission.download(MissionType.Mission)) {
                is MissionResult.Failure -> {
                    state.update { it.copy(transfer = TransferState.Failed(TransferDirection.Download, MissionType.Mission, r.error)) }
                    return@runTransfer
                }
                is MissionResult.Success -> r.value
            }
            var partial: MissionType? = null
            fun extra(type: MissionType, result: MissionResult<com.altnautica.gcs.core.link.mission.MissionPlan>): List<MissionItem> =
                when (result) {
                    is MissionResult.Success -> result.value.items
                    is MissionResult.Failure -> {
                        partial = partial ?: type
                        emptyList()
                    }
                }
            val fence = extra(MissionType.Fence, svc.mission.download(MissionType.Fence))
            val rally = extra(MissionType.Rally, svc.mission.download(MissionType.Rally))
            val rebuilt = MissionWire.fromWire(mission.home, mission.items, fence, rally, homeSlot, nextId)
            val message = when {
                partial != null -> PlannerMessage.DownloadPartial(partial!!)
                rebuilt.plan.isEmpty -> PlannerMessage.VehicleHasNoMission
                else -> null
            }
            if (rebuilt.plan.isEmpty) {
                state.update { it.copy(transfer = TransferState.Done(TransferDirection.Download, 0), message = message) }
                return@runTransfer
            }
            replacePlan(rebuilt.plan, state.value.nodeName.orEmpty(), null, true)
            mirror?.let { receipt = PlanIdentity.receiptOf(it) }
            state.update {
                it.copy(
                    transfer = TransferState.Done(TransferDirection.Download, mission.items.size),
                    vehiclePlan = rebuilt.plan,
                    message = message ?: it.message,
                )
            }
            refreshDrone()
        }
    }

    private fun runTransfer(svc: VehicleServices, direction: TransferDirection, block: suspend () -> Unit) {
        transferJob?.cancel()
        state.update { it.copy(transfer = TransferState.Running(direction, MissionType.Mission, 0, 0)) }
        transferJob = scope.launch {
            val progress = launch {
                svc.mission.transfer.collect { t ->
                    if (t is MissionTransfer.InProgress) {
                        state.update { s ->
                            if (s.transfer is TransferState.Running) s.copy(transfer = TransferState.Running(direction, t.type, t.done, t.total)) else s
                        }
                    }
                }
            }
            try {
                block()
            } finally {
                progress.cancel()
            }
        }
    }

    fun dismissTransfer() = state.update { if (it.transferring) it else it.copy(transfer = TransferState.Idle) }

    // ── Start mission (slide) ────────────────────────────────

    fun openStartMission() {
        val s = state.value
        if (s.link !is VehicleLink.Connected) return
        state.update { it.copy(startSheet = refreshChecks(StartMissionSheet(checks = emptyList(), armed = false), it)) }
    }

    fun setChecklistOverride(enabled: Boolean) = state.update { s -> s.copy(startSheet = s.startSheet?.copy(overrideChecklist = enabled)) }

    fun dismissStartMission() = state.update { s -> if (s.startSheet?.running == true) s else s.copy(startSheet = null) }

    /** Called when the slide completes: arms if needed, then runs the uploaded mission. */
    fun startMission() {
        val svc = services ?: return
        val sheet = state.value.startSheet ?: return
        if (!sheet.canSlide) return
        val nodeId = state.value.nodeId ?: return
        state.update { it.copy(startSheet = sheet.copy(running = true, error = null)) }
        startJob = scope.launch {
            if (!sheet.armed) {
                val armed = svc.vehicle.control.run(VehicleCommand.Arm())
                if (armed != CommandResult.Accepted) {
                    state.update { s -> s.copy(startSheet = s.startSheet?.copy(running = false, error = StartError.ArmRefused(armed))) }
                    return@launch
                }
            }
            val started = svc.mission.start()
            if (started != CommandResult.Accepted) {
                state.update { s -> s.copy(startSheet = s.startSheet?.copy(running = false, error = StartError.StartRefused(started))) }
                return@launch
            }
            state.update { it.copy(startSheet = null, missionStartedFor = nodeId) }
        }
    }

    fun onMissionStartHandled() = state.update { it.copy(missionStartedFor = null) }

    private fun refreshChecks(sheet: StartMissionSheet, s: PlannerUiState): StartMissionSheet {
        val link = s.link as? VehicleLink.Connected
        val fix = link?.gpsFix
        val checks = listOf(
            StartCheck(StartCheckKind.LinkFresh, link?.linkFresh == true),
            StartCheck(StartCheckKind.PlanOnVehicle, s.inSyncWithVehicle && s.plan.items.isNotEmpty()),
            StartCheck(StartCheckKind.GpsFix, fix != null && fix >= GpsFix.Fix3D),
            StartCheck(StartCheckKind.HomeSet, link?.home != null),
        )
        return sheet.copy(checks = checks, armed = link?.armed == true)
    }

    private fun homeSlot(): Boolean = (state.value.link as? VehicleLink.Connected)?.homeSlot ?: true

    /** Plan with the vehicle's home filled in when the plan has none. */
    fun planWithHome(): MissionPlan {
        val s = state.value
        if (s.plan.home != null) return s.plan
        val link = s.link as? VehicleLink.Connected ?: return s.plan
        val home = link.home ?: return s.plan
        return s.plan.copy(home = PlanHome(home, link.homeAltMslM))
    }

    fun homeAltMsl(): Float? =
        state.value.plan.home?.altMslM ?: (state.value.link as? VehicleLink.Connected)?.homeAltMslM

    /** Cancels the vehicle observation, any transfer, and any start in flight. */
    fun cancel() {
        vehicleJob?.cancel()
        mirrorJob?.cancel()
        transferJob?.cancel()
        startJob?.cancel()
    }
}
