package com.altnautica.gcs.feature.plan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.plan.PlannerSettingsRepository
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mission.MissionMirrorSource
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.DragPhase
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapBasemap
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.map.offline.OfflineMapRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.feature.plan.io.PlanDocuments
import com.altnautica.gcs.feature.plan.io.PlanDraft
import com.altnautica.gcs.feature.plan.io.PlanDraftStore
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFiles
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanCommand
import com.altnautica.gcs.feature.plan.model.PlanEditor
import com.altnautica.gcs.feature.plan.model.RallyPoint
import com.altnautica.gcs.feature.plan.survey.SurveyBuilder
import com.altnautica.gcs.feature.plan.survey.SurveySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * Mission planner: edits a [MissionPlan] (path, survey, fence, rally) with undo and redo,
 * keeps the plan library, reads and writes plan files, and transfers plans to and from
 * the vehicle.
 */
@HiltViewModel
class PlannerViewModel @Inject constructor(
    servicesProvider: VehicleServicesProvider,
    nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    savedPlans: SavedPlanDao,
    documents: PlanDocuments,
    clock: ElapsedClock,
    private val offlineMaps: OfflineMapRepository,
    private val plannerSettings: PlannerSettingsRepository,
    private val inputRouter: AdosInputRouter,
    mirrorSource: MissionMirrorSource,
    private val draftStore: PlanDraftStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PlannerUiState())
    val state: StateFlow<PlannerUiState> = _state.asStateFlow()

    /** Controller stick and trigger positions; the map cursor reads them every frame. */
    val axes: StateFlow<GamepadAxes> = inputRouter.axes

    /** While the map holds controller focus the left stick pans it instead of moving focus. */
    fun setStickNavigationSuppressed(suppressed: Boolean) {
        inputRouter.suppressStickNavigation = suppressed
    }

    private val ids = AtomicLong(1)
    private val nextId: () -> Long = { ids.getAndIncrement() }

    private val session = PlanSession(_state)
    private val vehicle = PlannerVehicleController(viewModelScope, _state, servicesProvider, clock, nextId, mirrorSource) { plan, name, savedId, dirty ->
        session.replacePlan(plan, name = name, savedId = savedId, dirty = dirty)
    }
    private val library = PlannerLibrary(viewModelScope, _state, savedPlans, documents, session, nextId) { vehicle.planWithHome() }

    init {
        library.observe()
        viewModelScope.launch {
            combine(plannerSettings.settings, offlineMaps.pmtiles) { settings, packs -> packs to BasemapCodec.decode(settings.basemap, packs) }
                .collect { (packs, basemap) -> _state.update { it.copy(packs = packs, basemap = basemap) } }
        }
        viewModelScope.launch { offlineMaps.refresh() }
        vehicle.observeNodes(nodeSources)
        keepDraft()
    }

    /**
     * Restores an unsaved plan left by a process death (the file pickers are a common moment
     * for one), then keeps the draft in step with the open plan: written while it has unsaved
     * edits, cleared once it is saved or emptied.
     */
    private fun keepDraft() {
        viewModelScope.launch {
            draftStore.read()?.let { draft ->
                if (!_state.value.dirty && _state.value.plan.isEmpty) {
                    runCatching { PlanFiles.parsePlan(draft.planJson, nextId) }
                        .onSuccess { session.replacePlan(it.plan, name = draft.name, savedId = draft.savedId, dirty = true) }
                }
            }
            _state.map { DraftKey(it.plan, it.name, it.savedId, it.dirty) }
                .distinctUntilChanged()
                .collectLatest { k ->
                    delay(DRAFT_DEBOUNCE_MS)
                    draftStore.write(if (k.dirty && !k.plan.isEmpty) PlanDraft(k.name, k.savedId, PlanFiles.exportPlan(k.plan)) else null)
                }
        }
    }

    private data class DraftKey(val plan: MissionPlan, val name: String, val savedId: String?, val dirty: Boolean)

    // ── Vehicle ──────────────────────────────────────────────

    /** Plans for [nodeId]; null detaches from any vehicle. */
    fun selectVehicle(nodeId: DeviceId?) = vehicle.selectVehicle(nodeId)

    // ── Editing ──────────────────────────────────────────────

    fun setTool(tool: EditTool) = _state.update { it.copy(tool = tool) }

    /** A tap on empty map: adds to whatever the active tool builds. */
    fun onMapTap(point: GeoPoint) {
        val s = _state.value
        when (s.tool) {
            EditTool.Waypoint -> addWaypointAt(point)
            EditTool.Survey -> addSurveyPointAt(point)
            EditTool.FencePolygon -> _state.update { it.copy(fenceDraft = it.fenceDraft + point) }
            EditTool.FenceCircle -> {
                val zone = FenceZone.Circle(nextId(), s.fenceDraftInclusion, point, DEFAULT_FENCE_RADIUS_M)
                edit { PlanEditor.addFence(it, zone) }
                _state.update { it.copy(selection = Selection.Fence(zone.id)) }
            }
            EditTool.Rally -> addRallyAt(point)
        }
    }

    /** Appends a waypoint (a takeoff when the plan has no located item yet) and selects it. */
    fun addWaypointAt(point: GeoPoint) {
        val s = _state.value
        val command = if (s.plan.items.none { it.position != null }) PlanCommand.Takeoff else PlanCommand.Waypoint
        val item = PlanEditor.newItem(nextId(), command, point, s.defaultAltM, s.defaultRef)
        edit { PlanEditor.append(it, item) }
        _state.update { it.copy(selection = Selection.Item(item.id)) }
    }

    fun addRallyAt(point: GeoPoint) {
        val rally = RallyPoint(nextId(), point, _state.value.defaultAltM)
        edit { PlanEditor.addRally(it, rally) }
        _state.update { it.copy(selection = Selection.Rally(rally.id)) }
    }

    /** Adds the next survey outline vertex (the first one starts the outline). */
    fun addSurveyPointAt(point: GeoPoint) = updateSurvey(_state.value.survey + point)

    fun select(selection: Selection?) = _state.update { it.copy(selection = selection) }

    fun undo() = session.undo()

    fun redo() = session.redo()

    /**
     * A map feature drag. Plan features preview while moving and commit one edit (one undo
     * step) on [DragPhase.End]; survey and fence-draft handles are not plan content and
     * follow the finger directly.
     */
    fun onFeatureDrag(ref: MapFeatureRef, point: GeoPoint, phase: DragPhase) {
        if (!ref.editsPlan()) {
            if (ref.type == MapFeatureType.Handle) {
                when (val target = HandleTarget.parse(ref.id)) {
                    is HandleTarget.SurveyVertex -> moveSurveyVertex(target.index, point)
                    is HandleTarget.DraftVertex -> moveFenceDraftVertex(target.index, point)
                    else -> Unit
                }
            }
            return
        }
        when (phase) {
            DragPhase.Start, DragPhase.Move -> _state.update { it.copy(dragPreview = DragPreview(ref, point)) }
            DragPhase.End -> {
                _state.update { it.copy(dragPreview = null) }
                edit { DragPreview(ref, point).applyTo(it) }
            }
        }
    }

    /** Controller grab: drags the selected waypoint, rally point or circle fence to [point]. */
    fun grabSelected(point: GeoPoint, phase: DragPhase) {
        val s = _state.value
        val ref = s.dragPreview?.ref ?: s.plan.grabRef(s.selection) ?: return
        onFeatureDrag(ref, point, phase)
    }

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

    /** Swaps the item's command within its positional class; a cross-class swap is ignored. */
    fun setCommand(id: Long, command: PlanCommand) = edit { PlanEditor.setCommand(it, id, command, _state.value.defaultRef) }

    fun setAltitude(id: Long, altM: Float) = edit { plan -> PlanEditor.update(plan, id) { it.copy(z = altM) } }

    fun setAltitudeRef(id: Long, ref: AltitudeRef) = edit { PlanEditor.setAltitudeRef(it, id, ref, vehicle.homeAltMsl()) }

    /** Leg speed into the item; null removes the speed change. */
    fun setSpeed(id: Long, speedMs: Float?) = edit { plan -> PlanEditor.update(plan, id) { it.copy(speedMs = speedMs?.takeIf { s -> s > 0f }) } }

    /** Sets MAVLink param [index] (1..6); 5 and 6 are `x`/`y`, kept at double precision. */
    fun setParam(id: Long, index: Int, value: Double) = edit { plan ->
        PlanEditor.update(plan, id) {
            when (index) {
                1 -> it.copy(param1 = value.toFloat())
                2 -> it.copy(param2 = value.toFloat())
                3 -> it.copy(param3 = value.toFloat())
                4 -> it.copy(param4 = value.toFloat())
                5 -> it.copy(x = value)
                6 -> it.copy(y = value)
                else -> it
            }
        }
    }

    fun setDefaultAltitude(altM: Float) = _state.update { it.copy(defaultAltM = altM.coerceAtLeast(0f)) }

    fun setDefaultRef(ref: AltitudeRef) = _state.update { it.copy(defaultRef = ref) }

    /** Shows [basemap] and remembers it for the next planner session. */
    fun setBasemap(basemap: MapBasemap) {
        _state.update { it.copy(basemap = basemap) }
        viewModelScope.launch { plannerSettings.setBasemap(BasemapCodec.encode(basemap)) }
    }

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

    // ── Library and files ────────────────────────────────────

    fun setName(name: String) = _state.update { it.copy(name = name) }

    /** Runs [action] now, or asks first ([PlannerUiState.pendingDiscard]) while there are unsaved edits. */
    fun requestDiscard(action: DiscardAction) = library.requestDiscard(action)

    fun confirmDiscard() = library.confirmDiscard()

    fun cancelDiscard() = library.cancelDiscard()

    fun importPickerShown() = library.importPickerShown()

    fun leaveHandled() = library.leaveHandled()

    /** Starts an empty plan, dropping any unsaved edits; the screen asks first through [requestDiscard]. */
    fun newPlan() = library.newPlan()

    fun save(defaultName: String) = library.save(defaultName)

    /** Opens a library plan, dropping any unsaved edits; the screen asks first through [requestDiscard]. */
    fun load(id: String) = library.load(id)

    fun requestDeleteSaved(id: String) = library.requestDeleteSaved(id)

    fun confirmDeleteSaved() = library.confirmDeleteSaved()

    fun cancelDeleteSaved() = library.cancelDeleteSaved()

    fun importDocument(uri: Uri) = library.importDocument(uri)

    /** Parses [text] as the format named by [fileName] (content sniffed when the name has no known extension). */
    fun importText(fileName: String, text: String) = library.importText(fileName, text)

    fun exportText(format: PlanFileFormat): String = library.exportText(format)

    fun exportDocument(uri: Uri, format: PlanFileFormat) = library.exportDocument(uri, format)

    // ── Vehicle transfer ─────────────────────────────────────

    /** Uploads mission, then fence, then rally, then reads each back to verify it. */
    fun upload() = vehicle.upload()

    /** Re-runs the upload after a failure. */
    fun retryUpload() = vehicle.upload()

    /** Downloads from the vehicle; asks first when the open plan has unsaved edits. */
    fun download() = vehicle.download()

    /** Replaces the open plan with the plan stored on the vehicle (its agent's mirror). */
    fun loadFromDrone() = vehicle.loadFromDrone()

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

    fun dismissImportWarnings() = _state.update { it.copy(importWarnings = emptyList()) }

    private fun edit(transform: (MissionPlan) -> MissionPlan) = session.edit(transform)

    override fun onCleared() {
        vehicle.cancel()
    }

    companion object {
        const val LINK_FRESH_MS = 3_000L
        const val DEFAULT_FENCE_RADIUS_M = 100.0
        const val MIN_FENCE_RADIUS_M = 10.0

        /** Quiet time after the last edit before the draft is written. */
        const val DRAFT_DEBOUNCE_MS = 500L
    }
}
