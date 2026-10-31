package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanEditor
import com.altnautica.gcs.feature.plan.model.PlanHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The open plan's edit bookkeeping: every committed edit goes through [edit], which keeps
 * the undo history and the dirty flag against the last saved or loaded plan.
 */
internal class PlanSession(private val state: MutableStateFlow<PlannerUiState>) {
    /** Plan as last saved or loaded; drives [PlannerUiState.dirty]. */
    private var savedPlan = MissionPlan()
    private var history = PlanHistory()

    /** Commits one edit; a no-op transform records nothing. */
    fun edit(transform: (MissionPlan) -> MissionPlan) {
        val current = state.value.plan
        val plan = transform(current)
        if (plan == current) return
        history = history.record(current)
        state.update { it.copy(plan = plan, dirty = plan != savedPlan, canUndo = history.canUndo, canRedo = history.canRedo) }
    }

    fun undo() {
        val (next, plan) = history.undo(state.value.plan) ?: return
        history = next
        show(plan)
    }

    fun redo() {
        val (next, plan) = history.redo(state.value.plan) ?: return
        history = next
        show(plan)
    }

    private fun show(plan: MissionPlan) = state.update { s ->
        s.copy(
            plan = plan,
            dirty = plan != savedPlan,
            canUndo = history.canUndo,
            canRedo = history.canRedo,
            dragPreview = null,
            selection = s.selection?.takeIf { it.existsIn(plan) },
        )
    }

    /** The plan now matches storage (after a save). */
    fun markSaved(plan: MissionPlan) {
        savedPlan = plan
    }

    /** Replaces the whole plan (new, load, import, download); clears undo history. */
    fun replacePlan(plan: MissionPlan, name: String, savedId: String?, dirty: Boolean = false) {
        if (!dirty) savedPlan = plan
        history = history.cleared()
        state.update {
            it.copy(
                plan = plan,
                name = name,
                savedId = savedId,
                dirty = dirty,
                canUndo = false,
                canRedo = false,
                dragPreview = null,
                selection = null,
                survey = emptyList(),
                surveyPreview = null,
                fenceDraft = emptyList(),
            )
        }
    }
}

internal fun Selection.existsIn(plan: MissionPlan): Boolean = when (this) {
    is Selection.Item -> plan.items.any { it.id == id }
    is Selection.Rally -> plan.rally.any { it.id == id }
    is Selection.Fence -> plan.fence.any { it.id == id }
}

/** [plan] with this drag's feature moved to [DragPreview.point]; survey and draft handles are not plan content. */
internal fun DragPreview.applyTo(plan: MissionPlan): MissionPlan = when (ref.type) {
    MapFeatureType.Waypoint -> ref.id.toLongOrNull()?.let { PlanEditor.move(plan, it, point) } ?: plan
    MapFeatureType.Rally -> ref.id.toLongOrNull()?.let { PlanEditor.moveRally(plan, it, point) } ?: plan
    MapFeatureType.Handle -> when (val target = HandleTarget.parse(ref.id)) {
        is HandleTarget.FenceVertex -> PlanEditor.moveFenceVertex(plan, target.zoneId, target.index, point)
        is HandleTarget.FenceCenter -> PlanEditor.moveFenceVertex(plan, target.zoneId, 0, point)
        else -> plan
    }
}

/** True when the drag edits plan content (and so previews then commits once). */
internal fun MapFeatureRef.editsPlan(): Boolean = when (type) {
    MapFeatureType.Waypoint, MapFeatureType.Rally -> true
    MapFeatureType.Handle -> HandleTarget.parse(id).let { it is HandleTarget.FenceVertex || it is HandleTarget.FenceCenter }
}

/** The map feature that stands for [selection] when grabbed with the controller, if it can move. */
internal fun MissionPlan.grabRef(selection: Selection?): MapFeatureRef? = when (selection) {
    is Selection.Item -> items.firstOrNull { it.id == selection.id }?.position?.let { MapFeatureRef(MapFeatureType.Waypoint, selection.id.toString()) }
    is Selection.Rally -> MapFeatureRef(MapFeatureType.Rally, selection.id.toString()).takeIf { rally.any { it.id == selection.id } }
    is Selection.Fence -> (fence.firstOrNull { it.id == selection.id } as? FenceZone.Circle)?.let {
        MapFeatureRef(MapFeatureType.Handle, HandleTarget.id(HandleTarget.FenceCenter(it.id)))
    }
    null -> null
}
