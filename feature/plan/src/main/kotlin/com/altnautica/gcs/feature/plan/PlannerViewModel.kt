package com.altnautica.gcs.feature.plan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.offline.OfflineMapRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.feature.plan.io.PlanDocuments
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFiles
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.model.PlanEditor
import com.altnautica.gcs.feature.plan.model.RallyPoint
import com.altnautica.gcs.feature.plan.survey.SurveyBuilder
import com.altnautica.gcs.feature.plan.survey.SurveySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    servicesProvider: VehicleServicesProvider,
    nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    private val savedPlans: SavedPlanDao,
    private val documents: PlanDocuments,
    clock: ElapsedClock,
    private val offlineMaps: OfflineMapRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    private val ids = AtomicLong(1)
    private val nextId: () -> Long = { ids.getAndIncrement() }

    /** Plan as last saved or loaded; drives [PlannerUiState.dirty]. */
    private var savedPlan = MissionPlan()
    private val vehicle = PlannerVehicleController(viewModelScope, _state, servicesProvider, clock, nextId) { plan, name, savedId, dirty ->
        replacePlan(plan, name = name, savedId = savedId, dirty = dirty)
    }

    init {
        viewModelScope.launch { savedPlans.observeAll().collect { list -> _state.update { it.copy(library = list) } } }
        viewModelScope.launch { offlineMaps.pmtiles.collect { packs -> _state.update { it.copy(packs = packs) } } }
        viewModelScope.launch { offlineMaps.refresh() }
        vehicle.observeNodes(nodeSources)
    }

    // ── Vehicle ──────────────────────────────────────────────

    /** Plans for [nodeId]; null detaches from any vehicle. */
    fun selectVehicle(nodeId: DeviceId?) = vehicle.selectVehicle(nodeId)

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

    fun setAltitudeRef(id: Long, ref: AltitudeRef) = edit { PlanEditor.setAltitudeRef(it, id, ref, vehicle.homeAltMsl()) }

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
        PlanFileFormat.QgcPlan -> PlanFiles.exportPlan(vehicle.planWithHome())
        PlanFileFormat.Waypoints -> PlanFiles.exportWaypoints(vehicle.planWithHome())
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
    fun upload() = vehicle.upload()

    /** Downloads from the vehicle; asks first when the open plan has unsaved edits. */
    fun download() = vehicle.download()

    fun cancelDownload() = vehicle.cancelDownload()

    fun confirmDownload() = vehicle.confirmDownload()

    fun dismissTransfer() = vehicle.dismissTransfer()

    // ── Start mission (slide) ────────────────────────────────

    fun openStartMission() = vehicle.openStartMission()

    fun setChecklistOverride(enabled: Boolean) = vehicle.setChecklistOverride(enabled)

    fun dismissStartMission() = vehicle.dismissStartMission()

    /** Called when the slide completes: arms if needed, then runs the uploaded mission. */
    fun startMission() = vehicle.startMission()

    fun onMissionStartHandled() = vehicle.onMissionStartHandled()

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

    override fun onCleared() {
        vehicle.cancel()
    }

    companion object {
        const val LINK_FRESH_MS = 3_000L
        const val DEFAULT_FENCE_RADIUS_M = 100.0
        const val MIN_FENCE_RADIUS_M = 10.0
    }
}
