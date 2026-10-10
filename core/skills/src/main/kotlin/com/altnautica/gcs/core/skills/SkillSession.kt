package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.Vehicle
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NoticeLevel { Info, Warning, Error }

/** Feedback for one activation; [reasonKey] is a [SkillReason] key, [message] free text from the vehicle. */
data class SkillNotice(
    val skillId: String,
    val level: NoticeLevel,
    val reasonKey: String?,
    val message: String?,
)

/** MAV_AUTOPILOT values whose firmware flies autonomous navigation (ArduPilot, PX4). */
private val AUTONOMOUS_AUTOPILOTS = setOf(3, 12)

private fun autonomousNav(heartbeat: HeartbeatState): Boolean? =
    if (heartbeat.autopilot in AUTONOMOUS_AUTOPILOTS) true else null

/**
 * Skill state for one vehicle. States are recomputed on every change of arm state,
 * mode, reach, available modes, link freshness (sampled every
 * [SkillTiming.LINK_FRESH_POLL_MS], recompute only on a flip), checklist readiness,
 * the skill set, a cooldown end, and after each activation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillSession internal constructor(
    vehicle: Flow<Vehicle?>,
    checklistReady: Flow<Boolean>,
    registry: SkillRegistry,
    private val activator: SkillActivator,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
) {
    private val vehicleState: StateFlow<Vehicle?> = vehicle.stateIn(scope, SharingStarted.Eagerly, null)
    private val checklist: StateFlow<Boolean> = checklistReady.stateIn(scope, SharingStarted.Eagerly, false)
    private val recomputeTick = MutableStateFlow(0L)
    private val _notices = MutableSharedFlow<SkillNotice>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val notices: SharedFlow<SkillNotice> = _notices.asSharedFlow()

    /** Every registered skill (built-ins and extensions). */
    val allSkills: StateFlow<List<Skill>> = registry.skills.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun freshness(v: Vehicle): Flow<Boolean> = flow {
        while (true) {
            emit(isLinkFresh(v.telemetry.heartbeat.value, clock.nowMs()))
            delay(SkillTiming.LINK_FRESH_POLL_MS)
        }
    }.distinctUntilChanged()

    /** Emits on every input change, including reach and mode-list changes that leave the context equal. */
    private val contextInputs: SharedFlow<SkillContext> = vehicleState.flatMapLatest { v ->
        if (v == null) {
            checklist.map { ready -> SkillContext(null, null, null, false, ready, null) }
        } else {
            combine(
                v.telemetry.heartbeat.map { hb -> hb?.let { Triple(it.armed, it.mode, autonomousNav(it)) } }.distinctUntilChanged(),
                freshness(v),
                checklist,
                v.reach,
                v.control.availableModes,
            ) { hb, fresh, ready, _, _ ->
                SkillContext(
                    vehicle = v,
                    armed = hb?.first,
                    mode = hb?.second,
                    linkFresh = fresh,
                    checklistReady = ready,
                    autonomousNav = hb?.third,
                )
            }
        }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val context: StateFlow<SkillContext> = contextInputs.stateIn(
        scope,
        SharingStarted.Eagerly,
        SkillContext(null, null, null, false, false, null),
    )

    /** Per-skill state keyed by skill id. */
    val states: StateFlow<Map<String, SkillState>> =
        combine(contextInputs, allSkills, recomputeTick) { ctx, skills, _ ->
            val id = ctx.vehicle?.id
            skills.associate { it.id to activator.mergedState(id, it, ctx) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Skills eligible for the Skill Bar for the current vehicle; re-evaluated when the mode list arrives. */
    val barSkills: StateFlow<List<Skill>> =
        combine(allSkills, contextInputs) { skills, ctx -> SkillRegistry.barSkills(skills, ctx) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun recompute() {
        recomputeTick.value = recomputeTick.value + 1
    }

    /** Runs the activation pipeline for [skillId] and reports the result on [notices]. */
    suspend fun activate(skillId: String, args: Map<String, Any?>, confirmer: SkillConfirmer): ActivationOutcome {
        val skills = allSkills.value
        val skill = skills.firstOrNull { it.id == skillId }
        val base = context.value
        // Sample link freshness now rather than trusting the last 500 ms poll.
        val ctx = base.copy(linkFresh = base.vehicle != null && isLinkFresh(base.vehicle?.telemetry?.heartbeat?.value, clock.nowMs()))
        val outcome = activator.activate(skill, ctx, args, confirmer, skills)
        report(skillId, outcome)
        recompute()
        val vehicleId = ctx.vehicle?.id
        if (vehicleId != null && skill != null) {
            activator.cooldownEndsAt(vehicleId, skill.id)?.let { until ->
                scope.launch {
                    delay((until - clock.nowMs()).coerceAtLeast(0))
                    recompute()
                }
            }
        }
        return outcome
    }

    private fun report(skillId: String, outcome: ActivationOutcome) {
        val notice = when (outcome) {
            is ActivationOutcome.Disabled -> SkillNotice(skillId, NoticeLevel.Warning, outcome.reasonKey, null)
            is ActivationOutcome.Refused -> SkillNotice(skillId, NoticeLevel.Warning, outcome.reasonKey, null)
            is ActivationOutcome.Failed -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_FAILED, outcome.message)
            is ActivationOutcome.Completed -> resultNotice(skillId, outcome.result)
            is ActivationOutcome.Deactivated -> resultNotice(skillId, outcome.result).takeIf { outcome.result != CommandResult.Unsupported }
            ActivationOutcome.Busy, ActivationOutcome.Cancelled, ActivationOutcome.Debounced, ActivationOutcome.Missing -> null
        } ?: return
        _notices.tryEmit(notice)
    }

    private fun resultNotice(skillId: String, result: CommandResult): SkillNotice? = when (result) {
        CommandResult.Accepted -> null
        is CommandResult.Queued -> SkillNotice(skillId, NoticeLevel.Info, SkillReason.COMMAND_QUEUED, null)
        is CommandResult.Rejected -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_REJECTED, result.reason.ifBlank { null })
        CommandResult.Timeout -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_TIMEOUT, null)
        CommandResult.Unsupported -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_UNSUPPORTED, null)
        is CommandResult.Denied -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_DENIED, result.reason.ifBlank { null })
    }
}

/** Creates a [SkillSession] bound to a caller-owned scope (a ViewModel scope, typically). */
class SkillSessionFactory @Inject constructor(
    private val registry: SkillRegistry,
    private val activator: SkillActivator,
    private val clock: MonotonicClock,
) {
    fun create(vehicle: Flow<Vehicle?>, checklistReady: Flow<Boolean>, scope: CoroutineScope): SkillSession =
        SkillSession(vehicle, checklistReady, registry, activator, clock, scope)
}
