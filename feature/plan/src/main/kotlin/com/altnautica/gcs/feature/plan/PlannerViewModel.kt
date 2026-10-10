package com.altnautica.gcs.feature.plan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionTransfer
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.mission.TransferDirection
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.offline.OfflineMapRepository
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.plan.io.PlanDocuments
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFiles
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.MissionWire
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.model.PlanEditor
import com.altnautica.gcs.feature.plan.model.PlanHome
import com.altnautica.gcs.feature.plan.model.RallyPoint
import com.altnautica.gcs.feature.plan.survey.SurveyBuilder
import com.altnautica.gcs.feature.plan.survey.SurveySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Mission planner: edits a [MissionPlan] (path, survey, fence, rally), keeps the plan
 * library, reads and writes plan files, and transfers plans to and from the vehicle.
 */
@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val servicesProvider: VehicleServicesProvider,
    private val nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    private val savedPlans: SavedPlanDao,
    private val documents: PlanDocuments,
    private val clock: ElapsedClock,
    private val offlineMaps: OfflineMapRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    private val ids = AtomicLong(1)
    private val nextId: () -> Long = { ids.getAndIncrement() }

    /** Plan as last saved or loaded; drives [PlannerUiState.dirty]. */
    private var savedPlan = MissionPlan()
    private var services: VehicleServices? = null
    private var vehicleJob: Job? = null
    private var transferJob: Job? = null

    init {
        viewModelScope.launch { savedPlans.observeAll().collect { list -> _state.update { it.copy(library = list) } } }
        viewModelScope.launch { offlineMaps.pmtiles.collect { packs -> _state.update { it.copy(packs = packs) } } }
        viewModelScope.launch { offlineMaps.refresh() }
        viewModelScope.launch {
            allNodes().collect { nodes ->
                val vehicles = nodes.filter { it.profile == NodeProfile.Drone || Reach.DirectFc in it.reaches }
                _state.update { s -> s.copy(nodes = vehicles, nodeName = vehicles.firstOrNull { it.id == s.nodeId }?.name ?: s.nodeName) }
            }
        }
    }

    private fun allNodes(): Flow<List<NodeSummary>> {
        if (nodeSources.isEmpty()) return flowOf(emptyList())
        return combine(nodeSources.map { it.nodes }) { lists -> lists.flatMap { it.toList() }.distinctBy { it.id } }
    }

    // ── Vehicle ──────────────────────────────────────────────

    /** Plans for [nodeId]; null detaches from any vehicle. */
    fun selectVehicle(nodeId: DeviceId?) {
        if (nodeId == _state.value.nodeId && vehicleJob != null) return
        vehicleJob?.cancel()
        services = null
        _state.update { s ->
            s.copy(
                nodeId = nodeId,
                nodeName = s.nodes.firstOrNull { it.id == nodeId }?.name,
                link = if (nodeId == null) VehicleLink.None else VehicleLink.Connecting,
                vehiclePlan = null,
                transfer = TransferState.Idle,
            )
        }
        if (nodeId == null) {
            vehicleJob = null
            return
        }
        vehicleJob = viewModelScope.launch {
            servicesProvider.services(nodeId).collectLatest { svc ->
                services = svc
                if (svc == null) {
                    _state.update { it.copy(link = VehicleLink.Unsupported) }
                    return@collectLatest
                }
                observeVehicle(svc)
            }
        }
    }

    private suspend fun observeVehicle(svc: VehicleServices) {
        val t = svc.vehicle.telemetry
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
        combine(t.heartbeat, t.position, t.home, t.gps, svc.firmware) { hb, pos, home, gps, fw -> Snapshot(hb?.armed, hb?.tMs, pos, home, gps?.fix, fw) }
            .combine(t.vfr) { snap, vfr -> snap to vfr?.headingDeg }
            .combine(ticker) { pair, _ -> pair }
            .collect { (snap, heading) ->
                val link = VehicleLink.Connected(
                    armed = snap.armed,
                    linkFresh = snap.heartbeatMs != null && clock.nowMs() - snap.heartbeatMs <= LINK_FRESH_MS,
                    position = snap.position?.let { GeoPoint(it.latDeg, it.lonDeg) },
                    headingDeg = heading,
                    home = snap.home?.let { GeoPoint(it.latDeg, it.lonDeg) },
                    homeAltMslM = snap.home?.altMslM,
                    gpsFix = snap.fix,
                    homeSlot = snap.firmware != null && snap.firmware != FirmwareFamily.Px4 && snap.firmware != FirmwareFamily.Unknown,
                )
                _state.update { s -> s.copy(link = link, startSheet = s.startSheet?.let { sheet -> refreshChecks(sheet, s.copy(link = link)) }) }
            }
    }

    private data class Snapshot(
        val armed: Boolean?,
        val heartbeatMs: Long?,
        val position: com.altnautica.gcs.core.model.PositionFix?,
        val home: com.altnautica.gcs.core.model.HomePosition?,
        val fix: GpsFix?,
        val firmware: FirmwareFamily?,
    )

    // ── Editing ──────────────────────────────────────────────

    fun setTool(tool: EditTool) = _state.update { it.copy(tool = tool) }

    /** A tap on empty map: adds to whatever the active tool builds. */
    fun onMapTap(point: GeoPoint) {
        val s = _state.value
        when (s.tool) {
            EditTool.Waypoint -> {
                val command = if (s.plan.items.none { it.position != null }) PlanCommand.Takeoff else PlanCommand.Waypoint
                val item = PlanEditor.newItem(nextId(), command, point, s.defaultAltM, s.defaultRef)
                edit { PlanEditor.append(it, item) }
                _state.update { it.copy(selection = Selection.Item(item.id)) }
            }
            EditTool.Survey -> updateSurvey(s.survey + point)
            EditTool.FencePolygon -> _state.update { it.copy(fenceDraft = it.fenceDraft + point) }
            EditTool.FenceCircle -> {
                val zone = FenceZone.Circle(nextId(), s.fenceDraftInclusion, point, DEFAULT_FENCE_RADIUS_M)
                edit { PlanEditor.addFence(it, zone) }
                _state.update { it.copy(selection = Selection.Fence(zone.id)) }
            }
            EditTool.Rally -> {
                val rally = RallyPoint(nextId(), point, s.defaultAltM)
                edit { PlanEditor.addRally(it, rally) }
                _state.update { it.copy(selection = Selection.Rally(rally.id)) }
            }
        }
    }

    fun select(selection: Selection?) = _state.update { it.copy(selection = selection) }

    fun moveItem(id: Long, point: GeoPoint) = edit { PlanEditor.move(it, id, point) }

    /** Inserts a waypoint before ([after] = false) or after the item. */
    fun insertNear(id: Long, after: Boolean) {
        val s = _state.value
        val index = s.plan.items.indexOfFirst { it.id == id }
        if (index < 0) return
        val at = if (after) index + 1 else index
        val point = PlanEditor.insertionPoint(s.plan, at - 1) ?: s.plan.items[index].position ?: return
        val ref = s.plan.items[index].altitudeRef ?: s.defaultRef
        val item = PlanEditor.newItem(nextId(), PlanCommand.Waypoint, point, s.plan.items[index].z, ref)
        edit { PlanEditor.insertAt(it, at, item) }
        _state.update { it.copy(selection = Selection.Item(item.id)) }
    }

    /** Inserts a non-positional or positional command after the item. */
    fun insertCommandAfter(id: Long, command: PlanCommand) {
        val s = _state.value
        val index = s.plan.items.indexOfFirst { it.id == id }
        if (index < 0) return
        val point = PlanEditor.insertionPoint(s.plan, index)
        val item = PlanEditor.newItem(nextId(), command, point, s.plan.items[index].z, s.plan.items[index].altitudeRef ?: s.defaultRef)
        edit { PlanEditor.insertAt(it, index + 1, item) }
        _state.update { it.copy(selection = Selection.Item(item.id)) }
    }

    fun deleteItem(id: Long) {
        edit { PlanEditor.delete(it, id) }
        _state.update { s -> if (s.selection == Selection.Item(id)) s.copy(selection = null) else s }
    }

    fun setCommand(id: Long, command: PlanCommand) = edit { PlanEditor.setCommand(it, id, command, _state.value.defaultRef) }

    fun setAltitude(id: Long, altM: Float) = edit { plan -> PlanEditor.update(plan, id) { it.copy(z = altM) } }

    fun setAltitudeRef(id: Long, ref: AltitudeRef) = edit { PlanEditor.setAltitudeRef(it, id, ref, homeAltMsl()) }

    /** Leg speed into the item; null removes the speed change. */
    fun setSpeed(id: Long, speedMs: Float?) = edit { plan -> PlanEditor.update(plan, id) { it.copy(speedMs = speedMs?.takeIf { s -> s > 0f }) } }

    fun setParam(id: Long, index: Int, value: Float) = edit { plan ->
        PlanEditor.update(plan, id) {
            when (index) {
                1 -> it.copy(param1 = value)
                2 -> it.copy(param2 = value)
                3 -> it.copy(param3 = value)
                4 -> it.copy(param4 = value)
                5 -> it.copy(x = value.toDouble())
                else -> it
            }
        }
    }

    fun setDefaultAltitude(altM: Float) = _state.update { it.copy(defaultAltM = altM.coerceAtLeast(0f)) }

    fun setDefaultRef(ref: AltitudeRef) = _state.update { it.copy(defaultRef = ref) }

    fun setBasemap(basemap: MapBasemap) = _state.update { it.copy(basemap = basemap) }

    // Survey

    fun setSurveySettings(settings: SurveySettings) {
        _state.update { it.copy(surveySettings = settings) }
        updateSurvey(_state.value.survey)
    }

    fun moveSurveyVertex(index: Int, point: GeoPoint) {
        val list = _state.value.survey
        if (index !in list.indices) return
        updateSurvey(list.toMutableList().also { it[index] = point })
    }

    fun clearSurvey() = _state.update { it.copy(survey = emptyList(), surveyPreview = null) }

    /** Appends the previewed survey to the mission. */
    fun applySurvey() {
        val s = _state.value
        val survey = SurveyBuilder.build(s.survey, s.surveySettings, s.defaultRef, nextId)
        if (survey == null) {
            _state.update { it.copy(message = PlannerMessage.SurveyEmpty) }
            return
        }
        edit { it.copy(items = it.items + survey.items) }
        _state.update { it.copy(survey = emptyList(), surveyPreview = null, tool = EditTool.Waypoint) }
    }

    private fun updateSurvey(points: List<GeoPoint>) {
        val s = _state.value
        val preview = SurveyBuilder.build(points, s.surveySettings, s.defaultRef) { 0L }
        _state.update { it.copy(survey = points, surveyPreview = preview) }
    }

    // Fence and rally

    fun setFenceDraftInclusion(inclusion: Boolean) = _state.update { it.copy(fenceDraftInclusion = inclusion) }

    fun moveFenceDraftVertex(index: Int, point: GeoPoint) = _state.update { s ->
        if (index !in s.fenceDraft.indices) s else s.copy(fenceDraft = s.fenceDraft.toMutableList().also { it[index] = point })
    }

    fun closeFenceDraft() {
        val s = _state.value
        if (s.fenceDraft.size < 3) return
        val zone = FenceZone.Polygon(nextId(), s.fenceDraftInclusion, s.fenceDraft)
        edit { PlanEditor.addFence(it, zone) }
        _state.update { it.copy(fenceDraft = emptyList(), selection = Selection.Fence(zone.id)) }
    }

    fun discardFenceDraft() = _state.update { it.copy(fenceDraft = emptyList()) }

    fun moveFenceVertex(zoneId: Long, vertex: Int, point: GeoPoint) = edit { PlanEditor.moveFenceVertex(it, zoneId, vertex, point) }

    fun setFenceRadius(zoneId: Long, radiusM: Double) = edit { PlanEditor.setFenceRadius(it, zoneId, radiusM.coerceAtLeast(MIN_FENCE_RADIUS_M)) }

    fun toggleFenceInclusion(zoneId: Long) = edit { PlanEditor.toggleFenceInclusion(it, zoneId) }

    fun deleteFence(zoneId: Long) {
        edit { PlanEditor.deleteFence(it, zoneId) }
        _state.update { s -> if (s.selection == Selection.Fence(zoneId)) s.copy(selection = null) else s }
    }

    fun moveRally(id: Long, point: GeoPoint) = edit { PlanEditor.moveRally(it, id, point) }

    fun setRallyAltitude(id: Long, altM: Float) = edit { PlanEditor.setRallyAltitude(it, id, altM) }

    fun deleteRally(id: Long) {
        edit { PlanEditor.deleteRally(it, id) }
        _state.update { s -> if (s.selection == Selection.Rally(id)) s.copy(selection = null) else s }
    }

    // ── Library ──────────────────────────────────────────────

    fun setName(name: String) = _state.update { it.copy(name = name) }

    fun newPlan() {
        savedPlan = MissionPlan()
        _state.update {
            it.copy(plan = MissionPlan(), name = "", savedId = null, dirty = false, selection = null, survey = emptyList(), surveyPreview = null, fenceDraft = emptyList())
        }
    }

    fun save(defaultName: String) {
        val s = _state.value
        val name = s.name.ifBlank { defaultName }
        val id = s.savedId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val createdAt = s.library.firstOrNull { it.id == id }?.createdAtMs ?: now
        val plan = s.plan
        viewModelScope.launch {
            savedPlans.upsert(
                SavedPlanEntity(
                    id = id,
                    name = name,
                    createdAtMs = createdAt,
                    updatedAtMs = now,
                    itemCount = plan.items.size,
                    homeLatDeg = plan.home?.position?.latDeg,
                    homeLonDeg = plan.home?.position?.lonDeg,
                    planJson = PlanFiles.exportPlan(plan),
                ),
            )
            savedPlan = plan
            _state.update { it.copy(name = name, savedId = id, dirty = it.plan != plan, message = PlannerMessage.Saved(name)) }
        }
    }

    fun load(id: String) {
        viewModelScope.launch {
            val entity = savedPlans.getById(id) ?: return@launch
            val imported = runCatching { PlanFiles.parsePlan(entity.planJson, nextId) }.getOrElse { e ->
                _state.update { it.copy(message = PlannerMessage.ImportFailed((e as? PlanFormatException)?.reason, e.message)) }
                return@launch
            }
            replacePlan(imported.plan, name = entity.name, savedId = entity.id)
            _state.update { it.copy(message = PlannerMessage.Loaded(entity.name)) }
        }
    }

    fun deleteSaved(id: String) {
        viewModelScope.launch {
            savedPlans.delete(id)
            _state.update { s -> if (s.savedId == id) s.copy(savedId = null, dirty = true, message = PlannerMessage.Deleted) else s.copy(message = PlannerMessage.Deleted) }
        }
    }

    // ── Files ────────────────────────────────────────────────

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            val doc = runCatching { documents.read(uri) }.getOrElse { e ->
                _state.update { it.copy(message = PlannerMessage.ImportFailed(null, e.message)) }
                return@launch
            }
            importText(doc.name, doc.text)
        }
    }

    /** Parses [text] as the format named by [fileName] (content sniffed when the name has no known extension). */
    fun importText(fileName: String, text: String) {
        val format = PlanFileFormat.fromFileName(fileName)
            ?: if (text.trimStart().startsWith("{")) PlanFileFormat.QgcPlan else PlanFileFormat.Waypoints
        val result = runCatching {
            when (format) {
                PlanFileFormat.QgcPlan -> PlanFiles.parsePlan(text, nextId)
                PlanFileFormat.Waypoints -> PlanFiles.parseWaypoints(text, nextId)
            }
        }
        result.onSuccess { imported ->
            val name = fileName.substringBeforeLast('.').ifBlank { fileName }
            replacePlan(imported.plan, name = name, savedId = null, dirty = true)
            _state.update { it.copy(message = PlannerMessage.Imported(name, imported.warnings.size)) }
        }.onFailure { e ->
            _state.update { it.copy(message = PlannerMessage.ImportFailed((e as? PlanFormatException)?.reason, e.message)) }
        }
    }

    fun exportText(format: PlanFileFormat): String = when (format) {
        PlanFileFormat.QgcPlan -> PlanFiles.exportPlan(planWithHome())
        PlanFileFormat.Waypoints -> PlanFiles.exportWaypoints(planWithHome())
    }

    fun exportDocument(uri: Uri, format: PlanFileFormat) {
        val text = exportText(format)
        viewModelScope.launch {
            runCatching { documents.write(uri, text) }
                .onSuccess { _state.update { it.copy(message = PlannerMessage.Exported(format)) } }
                .onFailure { e -> _state.update { it.copy(message = PlannerMessage.ExportFailed(e.message ?: e.javaClass.simpleName)) } }
        }
    }

    // ── Vehicle transfer ─────────────────────────────────────

    /** Uploads mission, then fence, then rally; an empty fence or rally list clears it on the vehicle. */
    fun upload() {
        val svc = services ?: return
        if (_state.value.transferring) return
        val plan = _state.value.plan
        val wire = MissionWire.toWire(plan, homeSlot())
        runTransfer(svc, TransferDirection.Upload) {
            val steps = listOf(MissionType.Mission to wire.mission, MissionType.Fence to wire.fence, MissionType.Rally to wire.rally)
            for ((type, items) in steps) {
                val result = if (items.isEmpty() && type != MissionType.Mission) svc.mission.clear(type) else svc.mission.upload(type, items)
                if (result is MissionResult.Failure) {
                    _state.update { it.copy(transfer = TransferState.Failed(TransferDirection.Upload, type, result.error)) }
                    return@runTransfer
                }
            }
            _state.update { it.copy(transfer = TransferState.Done(TransferDirection.Upload, wire.mission.size), vehiclePlan = plan) }
        }
    }

    /** Downloads from the vehicle; asks first when the open plan has unsaved edits. */
    fun download() {
        if (services == null || _state.value.transferring) return
        if (_state.value.dirty && !_state.value.plan.isEmpty) {
            _state.update { it.copy(confirmReplaceWithDownload = true) }
            return
        }
        confirmDownload()
    }

    fun cancelDownload() = _state.update { it.copy(confirmReplaceWithDownload = false) }

    fun confirmDownload() {
        val svc = services ?: return
        _state.update { it.copy(confirmReplaceWithDownload = false) }
        val homeSlot = homeSlot()
        runTransfer(svc, TransferDirection.Download) {
            val mission = when (val r = svc.mission.download(MissionType.Mission)) {
                is MissionResult.Failure -> {
                    _state.update { it.copy(transfer = TransferState.Failed(TransferDirection.Download, MissionType.Mission, r.error)) }
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
                _state.update { it.copy(transfer = TransferState.Done(TransferDirection.Download, 0), message = message) }
                return@runTransfer
            }
            replacePlan(rebuilt.plan, name = _state.value.nodeName.orEmpty(), savedId = null, dirty = true)
            _state.update {
                it.copy(
                    transfer = TransferState.Done(TransferDirection.Download, mission.items.size),
                    vehiclePlan = rebuilt.plan,
                    message = message ?: it.message,
                )
            }
        }
    }

    private fun runTransfer(svc: VehicleServices, direction: TransferDirection, block: suspend () -> Unit) {
        transferJob?.cancel()
        _state.update { it.copy(transfer = TransferState.Running(direction, MissionType.Mission, 0, 0)) }
        transferJob = viewModelScope.launch {
            val progress = launch {
                svc.mission.transfer.collect { t ->
                    if (t is MissionTransfer.InProgress) {
                        _state.update { s ->
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

    fun dismissTransfer() = _state.update { if (it.transferring) it else it.copy(transfer = TransferState.Idle) }

    // ── Start mission (CS-6 slide) ───────────────────────────

    fun openStartMission() {
        val s = _state.value
        if (s.link !is VehicleLink.Connected) return
        _state.update { it.copy(startSheet = refreshChecks(StartMissionSheet(checks = emptyList(), armed = false), it)) }
    }

    fun setChecklistOverride(enabled: Boolean) = _state.update { s -> s.copy(startSheet = s.startSheet?.copy(overrideChecklist = enabled)) }

    fun dismissStartMission() = _state.update { s -> if (s.startSheet?.running == true) s else s.copy(startSheet = null) }

    /** Called when the slide completes: arms if needed, then runs the uploaded mission. */
    fun startMission() {
        val svc = services ?: return
        val sheet = _state.value.startSheet ?: return
        if (!sheet.canSlide) return
        val nodeId = _state.value.nodeId ?: return
        _state.update { it.copy(startSheet = sheet.copy(running = true, error = null)) }
        viewModelScope.launch {
            if (!sheet.armed) {
                val armed = svc.vehicle.control.run(VehicleCommand.Arm())
                if (armed != CommandResult.Accepted) {
                    _state.update { s -> s.copy(startSheet = s.startSheet?.copy(running = false, error = StartError.ArmRefused(armed))) }
                    return@launch
                }
            }
            val started = svc.mission.start()
            if (started != CommandResult.Accepted) {
                _state.update { s -> s.copy(startSheet = s.startSheet?.copy(running = false, error = StartError.StartRefused(started))) }
                return@launch
            }
            _state.update { it.copy(startSheet = null, missionStartedFor = nodeId) }
        }
    }

    fun onMissionStartHandled() = _state.update { it.copy(missionStartedFor = null) }

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

    // ── Shared ───────────────────────────────────────────────

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun edit(transform: (MissionPlan) -> MissionPlan) {
        _state.update { s ->
            val plan = transform(s.plan)
            s.copy(plan = plan, dirty = plan != savedPlan)
        }
    }

    private fun replacePlan(plan: MissionPlan, name: String, savedId: String?, dirty: Boolean = false) {
        if (!dirty) savedPlan = plan
        _state.update {
            it.copy(
                plan = plan,
                name = name,
                savedId = savedId,
                dirty = dirty,
                selection = null,
                survey = emptyList(),
                surveyPreview = null,
                fenceDraft = emptyList(),
            )
        }
    }

    /** Plan with the vehicle's home filled in when the plan has none. */
    private fun planWithHome(): MissionPlan {
        val s = _state.value
        if (s.plan.home != null) return s.plan
        val link = s.link as? VehicleLink.Connected ?: return s.plan
        val home = link.home ?: return s.plan
        return s.plan.copy(home = PlanHome(home, link.homeAltMslM))
    }

    private fun homeAltMsl(): Float? =
        _state.value.plan.home?.altMslM ?: (_state.value.link as? VehicleLink.Connected)?.homeAltMslM

    private fun homeSlot(): Boolean = (_state.value.link as? VehicleLink.Connected)?.homeSlot ?: true

    override fun onCleared() {
        vehicleJob?.cancel()
        transferJob?.cancel()
    }

    companion object {
        const val LINK_FRESH_MS = 3_000L
        const val DEFAULT_FENCE_RADIUS_M = 100.0
        const val MIN_FENCE_RADIUS_M = 10.0
    }
}
