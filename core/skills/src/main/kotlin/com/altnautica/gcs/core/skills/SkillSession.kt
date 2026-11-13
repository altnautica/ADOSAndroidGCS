package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.skills.builtin.OperatorRecovery
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NoticeLevel { Info, Warning, Error }

/**
 * Feedback for one activation; [reasonKey] is a [SkillReason] key, [message] free text from
 * the vehicle, [args] the values a notice text fills in (`skill`, `plugin`, `reason`).
 */
data class SkillNotice(
    val skillId: String,
    val level: NoticeLevel,
    val reasonKey: String?,
    val message: String?,
    val args: Map<String, String> = emptyMap(),
)

/** MAV_AUTOPILOT values whose firmware flies autonomous navigation (ArduPilot, PX4). */
private val AUTONOMOUS_AUTOPILOTS = setOf(3, 12)

private fun autonomousNav(heartbeat: HeartbeatState): Boolean? =
    if (heartbeat.autopilot in AUTONOMOUS_AUTOPILOTS) true else null

/** A toggle write waiting for the extension to echo [wantActive]. */
private data class PendingEcho(val wantActive: Boolean, val sinceMs: Long)

/**
 * Skill state for one vehicle. States are recomputed on every change of arm state, mode,
 * reach, available modes, link freshness, checklist readiness, the pilot state, the skill
 * set, a cooldown end, after each activation, and every [SkillTiming.STATE_TICK_MS] so the
 * extension freshness and pending rings age on screen.
 *
 * A toggle write shows a pending ring until its echo arrives; after
 * [SkillTiming.ECHO_TIMEOUT_MS] the ring clears with a "did not respond" notice. A Stop
 * pressed while the link is lost is queued and sent once the link returns. An active
 * extension skill that disappears (its extension stopped) raises a notice whose reason
 * comes from [stopReason] (the extension's reported status: crashed, disabled, uninstalled).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillSession internal constructor(
    vehicle: Flow<Vehicle?>,
    checklistReady: Flow<Boolean>,
    pilot: Flow<PilotState?>,
    pilotLink: Flow<PilotLink?>,
    registry: SkillRegistry,
    private val activator: SkillActivator,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    private val stopReason: (pluginId: String) -> String = { REASON_STOPPED },
) {
    private val vehicleState: StateFlow<Vehicle?> = vehicle.stateIn(scope, SharingStarted.Eagerly, null)
    private val checklist: StateFlow<Boolean> = checklistReady.stateIn(scope, SharingStarted.Eagerly, false)
    private val pilotState: StateFlow<PilotState?> = pilot.stateIn(scope, SharingStarted.Eagerly, null)
    private val link: StateFlow<PilotLink?> = pilotLink.stateIn(scope, SharingStarted.Eagerly, null)
    private val recomputeTick = MutableStateFlow(0L)
    private val pending = MutableStateFlow<Map<String, PendingEcho>>(emptyMap())
    private val queuedStops = MutableStateFlow<Set<String>>(emptySet())
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

    private val pilotInputs: Flow<Pair<PilotState?, PilotLink?>> = combine(pilotState, link) { p, l -> p to l }

    /** Emits on every input change, including reach and mode-list changes that leave the context equal. */
    private val contextInputs: SharedFlow<SkillContext> = vehicleState.flatMapLatest { v ->
        if (v == null) {
            combine(checklist, pilotInputs) { ready, (p, l) -> SkillContext(null, null, null, false, ready, null, p, l) }
        } else {
            combine(
                combine(
                    v.telemetry.heartbeat,
                    v.telemetry.systemHealth,
                    v.telemetry.onGround,
                ) { hb, sh, landed ->
                    hb?.let { OperatorRecovery.observeMode(v.id, it.mode.name, clock.nowMs()) }
                    Triple(hb?.let { Triple(it.armed, it.mode, autonomousNav(it)) }, sh?.systemStatus, landed)
                }.distinctUntilChanged(),
                freshness(v),
                checklist,
                combine(v.reach, v.control.availableModes) { r, m -> r to m },
                pilotInputs,
            ) { (hb, status, fcLanded), fresh, ready, _, (p, l) ->
                SkillContext(
                    vehicle = v,
                    armed = hb?.first,
                    mode = hb?.second,
                    linkFresh = fresh,
                    checklistReady = ready,
                    autonomousNav = hb?.third,
                    pilot = p,
                    pilotLink = l,
                    systemStatus = status,
                    fcLanded = fcLanded,
                )
            }
        }
    }.shareIn(scope, SharingStarted.Eagerly, replay = 1)

    val context: StateFlow<SkillContext> = contextInputs.stateIn(
        scope,
        SharingStarted.Eagerly,
        SkillContext(null, null, null, false, false, null),
    )

    /**
     * The once-a-second re-check for cooldowns and freshness. Not on the flight screen's redraw
     * beat (this module has none): its changes reach the screen through the screen's own pacing.
     */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(SkillTiming.STATE_TICK_MS)
        }
    }

    /**
     * The context as skill states read it: a pilot frame re-merges every skill only when it
     * changes a pilot field a state reads ([StatePilotKey]: the vehicle class, mission state,
     * profile, control mode, FC failsafe, arm and landed state, and offered operations), not on
     * each 10 Hz frame. The vehicle's reach and modes, read through [SkillContext.vehicle], are
     * part of the key.
     */
    private val stateContext: Flow<SkillContext> = contextInputs
        .map { ctx -> ctx to ctx.vehicle?.let { it.reach.value to it.control.availableModes.value } }
        .distinctUntilChanged { (a, ra), (b, rb) ->
            ra == rb && a.copy(pilot = null) == b.copy(pilot = null) && statePilotKey(a.pilot) == statePilotKey(b.pilot)
        }
        .map { it.first }

    /** Per-skill state keyed by skill id, with pending rings and queued stops merged in. */
    val states: StateFlow<Map<String, SkillState>> =
        combine(stateContext, allSkills, recomputeTick, combine(pending, queuedStops) { p, q -> p to q }, ticker) { ctx, skills, _, (pend, queued), _ ->
            val id = ctx.vehicle?.id
            skills.associate { skill ->
                val base = activator.mergedState(id, skill, ctx)
                skill.id to base.copy(
                    pending = skill.id in pend,
                    stopQueued = skill.id in queued,
                )
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Extension skills for the skill picker; a skill excluding the pilot's vehicle class is hidden. */
    val pickerSkills: StateFlow<List<Skill>> =
        combine(allSkills, pilotState.map { it?.vehicleClass }.distinctUntilChanged()) { skills, cls -> SkillRegistry.pickerSkills(skills, cls) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { watchEchoes() }
        scope.launch { watchRemovals() }
        scope.launch { flushQueuedStops() }
    }

    fun recompute() {
        recomputeTick.value = recomputeTick.value + 1
    }

    /** Runs the activation pipeline for [skillId] and reports the result on [notices]. */
    suspend fun activate(skillId: String, args: Map<String, Any?>, confirmer: SkillConfirmer): ActivationOutcome {
        val skills = allSkills.value
        val skill = skills.firstOrNull { it.id == skillId }
        val ctx = freshContext()
        val before = states.value[skillId]
        val outcome = activator.activate(skill, ctx, args, confirmer, skills)
        if (skill != null) track(skill, before, outcome)
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

    /**
     * Stops [skillId] if it is an active toggle (Circle / the picker's Stop). With the link
     * lost the stop is queued and sent once the link returns.
     */
    suspend fun stop(skillId: String): ActivationOutcome {
        val state = states.value[skillId]
        if (state?.active != true) return ActivationOutcome.Missing
        return activate(skillId, emptyMap()) { ConfirmResult.Confirmed() }
    }

    /** The active motion skill, if any. */
    fun activeMotionSkill(): Skill? = allSkills.value.firstOrNull { it.motion && states.value[it.id]?.active == true }

    private fun freshContext(): SkillContext {
        val base = context.value
        // Sample link freshness now rather than trusting the last 500 ms poll.
        return base.copy(linkFresh = base.vehicle != null && isLinkFresh(base.vehicle?.telemetry?.heartbeat?.value, clock.nowMs()))
    }

    private fun track(skill: Skill, before: SkillState?, outcome: ActivationOutcome) {
        if (!skill.toggle || skill.source !is SkillSource.Extension) return
        when (outcome) {
            is ActivationOutcome.Completed -> if (outcome.result.succeeded()) {
                pending.update { it + (skill.id to PendingEcho(true, clock.nowMs())) }
            }
            is ActivationOutcome.Deactivated -> when {
                outcome.result.succeeded() -> {
                    queuedStops.update { it - skill.id }
                    pending.update { it + (skill.id to PendingEcho(false, clock.nowMs())) }
                }
                before?.stale == true -> {
                    queuedStops.update { it + skill.id }
                    _notices.tryEmit(SkillNotice(skill.id, NoticeLevel.Info, SkillReason.STOP_QUEUED, null, mapOf("skill" to skill.labelKey)))
                }
            }
            else -> Unit
        }
    }

    /** Clears pending rings on their echo; a ring older than the echo timeout reverts with a notice. */
    private suspend fun watchEchoes() {
        combine(states, ticker) { s, now -> s to now }.collect { (s, now) ->
            val current = pending.value
            if (current.isEmpty()) return@collect
            val keep = HashMap<String, PendingEcho>()
            for ((id, p) in current) {
                val st = s[id]
                when {
                    st == null -> Unit
                    st.active == p.wantActive -> Unit
                    now - p.sinceMs >= SkillTiming.ECHO_TIMEOUT_MS -> {
                        val label = allSkills.value.firstOrNull { it.id == id }?.labelKey ?: id
                        _notices.tryEmit(SkillNotice(id, NoticeLevel.Warning, SkillReason.NO_RESPONSE, null, mapOf("skill" to label)))
                    }
                    else -> keep[id] = p
                }
            }
            if (keep.size != current.size) pending.value = keep
        }
    }

    /** An active extension skill that leaves the set means its extension stopped mid-flight. */
    private suspend fun watchRemovals() {
        var previous: Map<String, Pair<Skill, Boolean>> = emptyMap()
        combine(allSkills, states) { skills, s -> skills.associate { it.id to (it to (s[it.id]?.active == true)) } }
            .collect { now ->
                for ((id, entry) in previous) {
                    val (skill, wasActive) = entry
                    val src = skill.source as? SkillSource.Extension ?: continue
                    if (id in now || !wasActive) continue
                    // The whole node going away (cockpit closed) is not a plugin stop.
                    if (now.isEmpty() && vehicleState.value == null) continue
                    pending.update { it - id }
                    queuedStops.update { it - id }
                    _notices.tryEmit(
                        SkillNotice(id, NoticeLevel.Warning, SkillReason.PLUGIN_STOPPED, null, mapOf("plugin" to src.pluginName, "reason" to stopReason(src.pluginId))),
                    )
                }
                previous = now
            }
    }

    /** Sends queued stops once their skill is no longer link-stale. */
    private suspend fun flushQueuedStops() {
        states.collect { s ->
            for (id in queuedStops.value) {
                val st = s[id] ?: continue
                if (!st.active) {
                    queuedStops.update { it - id }
                } else if (!st.stale) {
                    scope.launch { activate(id, emptyMap()) { ConfirmResult.Confirmed() } }
                }
            }
        }
    }

    private fun report(skillId: String, outcome: ActivationOutcome) {
        val notice = when (outcome) {
            is ActivationOutcome.Disabled -> SkillNotice(skillId, NoticeLevel.Warning, outcome.reasonKey, null)
            is ActivationOutcome.Refused -> SkillNotice(skillId, NoticeLevel.Warning, outcome.reasonKey, null)
            is ActivationOutcome.Failed -> SkillNotice(skillId, NoticeLevel.Error, SkillReason.COMMAND_FAILED, outcome.message)
            is ActivationOutcome.Completed -> resultNotice(skillId, outcome.result)
            is ActivationOutcome.Deactivated ->
                if (skillId in queuedStops.value) null else resultNotice(skillId, outcome.result).takeIf { outcome.result != CommandResult.Unsupported }
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
    fun create(
        vehicle: Flow<Vehicle?>,
        checklistReady: Flow<Boolean>,
        scope: CoroutineScope,
        pilot: Flow<PilotState?> = flowOf(null),
        pilotLink: Flow<PilotLink?> = flowOf(null),
        stopReason: (pluginId: String) -> String = { REASON_STOPPED },
    ): SkillSession = SkillSession(vehicle, checklistReady, pilot, pilotLink, registry, activator, clock, scope, stopReason)
}

/** Stop reason when the extension's status is unknown. */
const val REASON_STOPPED = "stopped"

/**
 * The pilot fields skill states read: the vehicle class (picker and class gate), the mission
 * state (Resume), the profile (profile toggles), the control mode (routing to the pilot or the
 * FC), the FC failsafe (Pause, Fly Here), the arm and landed state (Disarm, Fly Here) and the
 * offered operations (Fly Here). Frames equal in these re-merge no skill state.
 */
internal data class StatePilotKey(
    val vehicleClass: PilotVehicleClass?,
    val missionState: MissionState?,
    val profile: PilotProfile?,
    val controlMode: ControlMode?,
    val fcFailsafe: Boolean,
    val armed: Boolean?,
    val landed: Boolean?,
    val surfaceOps: Set<String>,
)

/** The [StatePilotKey] of [p]; null without a pilot. */
internal fun statePilotKey(p: PilotState?): StatePilotKey? = p?.let {
    StatePilotKey(it.vehicleClass, it.mission?.state, it.profile, it.controlMode, it.fcFailsafe, it.armed, it.landed, it.surfaceOps)
}
