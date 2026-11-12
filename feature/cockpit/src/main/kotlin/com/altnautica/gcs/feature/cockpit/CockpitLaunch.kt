package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.NoticeLevel
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.builtin.FlyHereAltitude
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.hud.FlyHereFc
import com.altnautica.gcs.core.skills.hud.FlyHereReason
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.flyHereOffered
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.confirm.ARG_FLY_HERE_DISTANCE_M
import com.altnautica.gcs.feature.cockpit.launch.LaunchAfter
import com.altnautica.gcs.feature.cockpit.launch.LaunchDraft
import com.altnautica.gcs.feature.cockpit.launch.LaunchUi
import com.altnautica.gcs.feature.cockpit.launch.NedPoint
import com.altnautica.gcs.feature.cockpit.launch.abortRefusedNotice
import com.altnautica.gcs.feature.cockpit.launch.launchArgs
import com.altnautica.gcs.feature.cockpit.launch.launchGotoNed
import com.altnautica.gcs.feature.cockpit.launch.sendLaunchAbort
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.pilotActionEvent
import com.altnautica.gcs.feature.cockpit.preflight.ArmRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Launch sheet and the map long-press: on the ground a long-press picks the sheet's
 * go-to point, in the air it flies there (Fly Here). The launch itself and its Abort go to
 * the pilot.
 */
internal class CockpitLaunch(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val telemetry: StateFlow<VehicleTelemetry?>,
    private val pilot: CockpitPilot,
    private val overlays: CockpitOverlays,
    private val pickerSkills: StateFlow<List<Skill>>,
    private val strings: SetupStringsSpec,
    private val specText: SpecText,
    private val notices: MutableSharedFlow<SkillNotice>,
    private val events: MutableSharedFlow<CockpitEvent>,
    /** Possibly in the air: a long-press flies there instead of opening Launch. */
    private val airborne: () -> Boolean,
    private val activate: (String, Map<String, Any?>) -> Unit,
    private val pilotAction: (PilotAction) -> Unit,
    /** True when [ArmRequest] may go ahead; false parks it behind the first-arm checklist. */
    private val armGate: (ArmRequest) -> Boolean,
) {
    private val draft = MutableStateFlow(LaunchDraft())

    /** The map point a long-press picked for the launch's go-to option. */
    private val point = MutableStateFlow<GeoPoint?>(null)

    val ui: StateFlow<LaunchUi?> = combine(overlays.launch, draft, pickerSkills, pilot.state, point) { open, d, skills, p, pt ->
        if (!open) return@combine null
        LaunchUi(
            draft = d,
            skills = skills.filter { it.category == SkillCategory.Behavior && it.motion },
            mission = p?.mission?.state,
            gotoPoint = gotoNed(pt, d.altM),
            vehicleClass = p?.vehicleClass,
            sequence = p?.sequence,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    fun open(open: Boolean) {
        overlays.launch.value = open
    }

    fun setDraft(d: LaunchDraft) {
        draft.value = d
    }

    /**
     * A long-press on the map. On the ground it picks the Launch sheet's go-to point and
     * opens the sheet on that option; in the air it flies there at the current height above
     * home, held to the agent's 2..120 m: through the pilot's goto action when the agent
     * offers it, else the fly-here skill (behind its hold confirm). Without a position it
     * says Fly Here needs one; when Fly Here is not offered it says why and opens no confirm.
     */
    fun pickMapPoint(target: GeoPoint) {
        if (airborne()) {
            flyHereRefusal()?.let { reason ->
                val why = strings.format(reason.refusalKey).replaceFirstChar(Char::lowercase)
                warn(strings.format("refusal.fly_here_prefix", mapOf("reason" to why)))
                return
            }
            // Height above home from the global position, else (no GNSS) from the local frame.
            val rel = telemetry.value?.let { t -> t.position.value?.altRelM ?: t.localPosition.value?.let { -it.downM } }
            if (rel == null) {
                // Without a position there is no height to fly at: say so instead of doing nothing.
                warn(strings.format("map.fly_here.needs_position"))
                return
            }
            // The agent's bounds: never lower than 2 m (a hover near the ground) nor above 120 m.
            val alt = rel.coerceIn(FlyHereAltitude.MIN_M, FlyHereAltitude.MAX_M)
            // One entry, behind fly-here's hold confirm and gates: the skill sends the pilot's
            // goto only while the pilot owns the vehicle, the FC's guided goto otherwise. The
            // distance is for the confirm, which shows how far the point is.
            val dist = vehicleGeo(telemetry.value)?.let { flatDistanceM(it, target) }
            activate(
                FlyHereSkill.id,
                mapOf(SkillArgs.LAT to target.latDeg, SkillArgs.LON to target.lonDeg, SkillArgs.ALTITUDE_M to alt, ARG_FLY_HERE_DISTANCE_M to dist),
            )
            return
        }
        point.value = target
        draft.update { it.copy(after = LaunchAfter.Goto) }
        overlays.launch.value = true
    }

    /**
     * A long-press on the local-frame minimap [northM], [eastM] metres from the drone as
     * drawn (the drone sits at its SLAM pose or its FC local position, whichever the map
     * draws): that offset is added to the drone's own position on the globe, then handled as
     * any map point ([pickMapPoint]). Without a position on the globe it says Fly Here needs one.
     */
    fun pickLocalPoint(northM: Float, eastM: Float) {
        val drone = vehicleGeo(telemetry.value)
        if (drone == null) {
            warn(strings.format("map.fly_here.needs_position"))
            return
        }
        pickMapPoint(localToGlobal(drone, northM, eastM))
    }

    /** Sends the launch sequence from the sheet's choices, behind the first-arm checklist. */
    fun launchNow() {
        val d = draft.value
        val args = launchArgs(d, pickerSkills.value, gotoNed(point.value, d.altM)) ?: return
        if (armGate(ArmRequest.Launch(args))) send(args)
    }

    /**
     * Sends the launch and closes the Launch sheet: left open, its armed "Hold to launch"
     * stayed live through the flight and the stale sheet state carried past the landing.
     */
    fun send(args: Map<String, Any?>) {
        overlays.launch.value = false
        pilotAction(PilotAction(PilotAction.LAUNCH, args))
    }

    /**
     * Abort of a running launch (the banner's and the chip's): the pilot's pause, sent
     * directly so it works in the checks and arm steps too; a refusal reads `flow.launch.abort_refused`.
     */
    fun abort() {
        scope.launch {
            when (val result = sendLaunchAbort(pilot.link.value)) {
                is PilotActionResult.Refused -> notices.tryEmit(abortRefusedNotice(result.reason, strings, specText))
                else -> pilotActionEvent(result, specText)?.let(events::tryEmit)
            }
        }
    }

    private fun warn(message: String) {
        notices.tryEmit(SkillNotice(FlyHereSkill.id, NoticeLevel.Warning, null, message))
    }

    /** Why Fly Here is not offered now (the shared offer's reason), or null when it is or no reason is known. */
    private fun flyHereRefusal(): FlyHereReason? {
        val p = pilot.state.value
        val t = telemetry.value
        val fc = t?.heartbeat?.value?.let { hb ->
            FlyHereFc(guided = hb.mode.name.equals("GUIDED", ignoreCase = true), armed = hb.armed, landed = t.onGround.value == true)
        }
        val offer = flyHereOffered(p, p?.let { clock.nowMs() - it.receivedAtMs } ?: Long.MAX_VALUE, fc)
        return offer.reason.takeIf { offer.route == null }
    }

    /** [target] in the pilot's local frame at [altM] above it: from the vehicle's local position, else from home. */
    private fun gotoNed(target: GeoPoint?, altM: Float): NedPoint? {
        if (target == null) return null
        val t = telemetry.value ?: return null
        val local = t.localPosition.value
        val fix = t.position.value
        if (local != null && fix != null) {
            return launchGotoNed(fix.latDeg, fix.lonDeg, local.northM, local.eastM, target.latDeg, target.lonDeg, altM)
        }
        val home = t.home.value ?: return null
        return launchGotoNed(home.latDeg, home.lonDeg, 0f, 0f, target.latDeg, target.lonDeg, altM)
    }
}
