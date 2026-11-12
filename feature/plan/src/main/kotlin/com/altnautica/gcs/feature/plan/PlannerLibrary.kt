package com.altnautica.gcs.feature.plan

import android.net.Uri
import android.util.Log
import com.altnautica.gcs.core.database.SavedPlanDao
import com.altnautica.gcs.core.database.SavedPlanEntity
import com.altnautica.gcs.feature.plan.io.PlanDocuments
import com.altnautica.gcs.feature.plan.io.PlanFileFormat
import com.altnautica.gcs.feature.plan.io.PlanFiles
import com.altnautica.gcs.feature.plan.io.PlanFormatException
import com.altnautica.gcs.feature.plan.model.Autopilot
import com.altnautica.gcs.feature.plan.model.MissionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Plan library and files: save, load, delete, import and export, plus the confirms that
 * guard actions which would drop unsaved edits or delete a stored plan.
 */
internal class PlannerLibrary(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlannerUiState>,
    private val savedPlans: SavedPlanDao,
    private val documents: PlanDocuments,
    private val session: PlanSession,
    private val nextId: () -> Long,
    /** Clears the stored draft at once; true when the open plan was discarded. */
    private val clearDraft: (discarded: Boolean) -> Unit,
    /** The plan as exported: with the vehicle's home filled in when the plan has none. */
    private val planWithHome: () -> MissionPlan,
) {
    fun observe() {
        scope.launch { savedPlans.observeAll().collect { list -> state.update { it.copy(library = list) } } }
    }

    // ── Discard guard ────────────────────────────────────────

    /** Runs [action] now, or asks first while the plan has unsaved edits. */
    fun requestDiscard(action: DiscardAction) {
        // Leaving cancels the transfer mid-list (mission updated, fence or rally stale): refuse it.
        if (action == DiscardAction.Leave && state.value.transferring) {
            state.update { it.copy(message = PlannerMessage.TransferInProgress) }
            return
        }
        if (state.value.dirty) state.update { it.copy(pendingDiscard = action) } else perform(action)
    }

    fun confirmDiscard() {
        val action = state.value.pendingDiscard ?: return
        state.update { it.copy(pendingDiscard = null) }
        perform(action)
    }

    fun cancelDiscard() = state.update { it.copy(pendingDiscard = null) }

    fun importPickerShown() = state.update { it.copy(pickImport = false) }

    fun leaveHandled() = state.update { it.copy(leaveApproved = false) }

    private fun perform(action: DiscardAction) = when (action) {
        DiscardAction.New -> newPlan()
        is DiscardAction.Load -> load(action.id)
        DiscardAction.Import -> state.update { it.copy(pickImport = true) }
        DiscardAction.Leave -> {
            // The discarded plan must not come back as a draft once this screen is gone.
            clearDraft(true)
            state.update { it.copy(leaveApproved = true) }
        }
    }

    // ── Library ──────────────────────────────────────────────

    fun newPlan() = session.replacePlan(MissionPlan(), name = "", savedId = null)

    fun save(defaultName: String) {
        val s = state.value
        val name = s.name.ifBlank { defaultName }
        val id = s.savedId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val createdAt = s.library.firstOrNull { it.id == id }?.createdAtMs ?: now
        val plan = s.plan
        scope.launch {
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
            session.markSaved(plan)
            state.update { it.copy(name = name, savedId = id, dirty = it.plan != plan, message = PlannerMessage.Saved(name)) }
            if (!state.value.dirty) clearDraft(false)
        }
    }

    fun load(id: String) {
        scope.launch {
            val entity = savedPlans.getById(id) ?: return@launch
            val imported = runCatching { PlanFiles.parsePlan(entity.planJson, nextId) }.getOrElse { e ->
                state.update { it.copy(message = importFailed(e)) }
                return@launch
            }
            session.replacePlan(imported.plan, name = entity.name, savedId = entity.id)
            state.update { it.copy(message = PlannerMessage.Loaded(entity.name)) }
        }
    }

    /** Asks before deleting a stored plan. */
    fun requestDeleteSaved(id: String) = state.update { s -> s.copy(pendingDelete = s.library.firstOrNull { it.id == id }) }

    fun cancelDeleteSaved() = state.update { it.copy(pendingDelete = null) }

    fun confirmDeleteSaved() {
        val entity = state.value.pendingDelete ?: return
        state.update { it.copy(pendingDelete = null) }
        scope.launch {
            savedPlans.delete(entity.id)
            state.update { s ->
                if (s.savedId == entity.id) s.copy(savedId = null, dirty = true, message = PlannerMessage.Deleted) else s.copy(message = PlannerMessage.Deleted)
            }
        }
    }

    // ── Files ────────────────────────────────────────────────

    fun importDocument(uri: Uri) {
        scope.launch {
            val doc = runCatching { documents.read(uri) }.getOrElse { e ->
                state.update { it.copy(message = importFailed(e)) }
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
            session.replacePlan(imported.plan, name = name, savedId = null, dirty = true)
            state.update { it.copy(message = PlannerMessage.Imported(name, imported.warnings.size), importWarnings = imported.warnings) }
        }.onFailure { e ->
            state.update { it.copy(message = importFailed(e)) }
        }
    }

    fun exportText(format: PlanFileFormat): String = when (format) {
        PlanFileFormat.QgcPlan -> {
            val vehicle = state.value.link as? VehicleLink.Connected
            PlanFiles.exportPlan(planWithHome(), vehicle?.autopilot ?: Autopilot.Unknown, vehicle?.vehicleType)
        }
        PlanFileFormat.Waypoints -> PlanFiles.exportWaypoints(planWithHome())
    }

    fun exportDocument(uri: Uri, format: PlanFileFormat) {
        val text = exportText(format)
        scope.launch {
            runCatching { documents.write(uri, text) }
                .onSuccess { state.update { it.copy(message = PlannerMessage.Exported(format)) } }
                .onFailure { e ->
                    Log.w(TAG, "plan export failed", e)
                    state.update { it.copy(message = PlannerMessage.ExportFailed) }
                }
        }
    }

    /** Operator text comes from the reason only; the exception text goes to the log. */
    private fun importFailed(e: Throwable): PlannerMessage {
        Log.w(TAG, "plan import failed", e)
        return PlannerMessage.ImportFailed((e as? PlanFormatException)?.reason)
    }

    private companion object {
        const val TAG = "PlannerLibrary"
    }
}
