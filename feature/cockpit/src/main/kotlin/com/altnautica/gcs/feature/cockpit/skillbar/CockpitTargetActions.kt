package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.extensions.skills.ExtensionTargetActionSkill
import com.altnautica.gcs.core.skills.ActivationOutcome
import com.altnautica.gcs.core.skills.SkillActivator
import com.altnautica.gcs.core.skills.SkillSession
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import com.altnautica.gcs.feature.cockpit.SlotUi
import com.altnautica.gcs.feature.cockpit.UI_STOP_MS
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Extension target actions run on a box drawn over the video. */
internal class CockpitTargetActions(
    private val scope: CoroutineScope,
    extensionSkills: ExtensionSkillContributor,
    private val session: SkillSession,
    private val activator: SkillActivator,
    private val confirm: ConfirmController,
    private val selectedStreamId: () -> String?,
    private val events: MutableSharedFlow<CockpitEvent>,
) {
    /** Extension target actions for the flown drone, with their states. */
    val actions: StateFlow<List<SlotUi>> = combine(extensionSkills.targetActions, session.context) { actions, ctx ->
        actions.mapIndexed { i, a -> SlotUi(i, a, activator.mergedState(ctx.vehicle?.id, a, ctx), null, null) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyList())

    /**
     * Runs a target action on a box drawn over the video. [bbox] is normalized to the
     * video frame (x, y, width, height in 0..1); the camera is the selected stream.
     */
    fun activate(skill: ExtensionTargetActionSkill, bbox: Map<String, Float>) {
        scope.launch {
            val args = mapOf(
                "camera_id" to (selectedStreamId() ?: PRIMARY_CAMERA),
                "bbox" to bbox,
            )
            val outcome = activator.activate(skill, session.context.value, args, confirm, session.allSkills.value)
            when (outcome) {
                is ActivationOutcome.Disabled -> outcome.reasonKey?.let { events.tryEmit(CockpitEvent.TargetRefused(it)) }
                is ActivationOutcome.Refused -> events.tryEmit(CockpitEvent.TargetRefused(outcome.reasonKey))
                else -> Unit
            }
        }
    }

    private companion object {
        /** Camera id of the agent's primary stream when no stream tab is selected. */
        const val PRIMARY_CAMERA = "main"
    }
}
