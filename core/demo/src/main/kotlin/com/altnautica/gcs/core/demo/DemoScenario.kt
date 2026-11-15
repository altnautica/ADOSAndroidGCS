package com.altnautica.gcs.core.demo

import androidx.annotation.StringRes
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A state the demo drone's onboard pilot can be pinned to, so the demo shows flight states
 * it cannot reach by flying alone. A pin lays its fields over the pilot's live state; the
 * demo's layer and follow feeds follow the pinned state too.
 */
enum class DemoScenario(
    /** The operator's name for the state, as the demo picker lists it. */
    @StringRes val label: Int,
    /** The `hold_reason` the pilot reports while pinned, for the hold pins. */
    val holdReason: String? = null,
    /** The code a `goto` action is refused with while pinned, for the goto refusal pins. */
    val gotoRefusal: String? = null,
) {
    /** On the ground with the position readiness item failing (block): the chip reads NOT READY. */
    ReadinessBlocked(R.string.demo_scenario_readiness_blocked),

    /** On the ground with the battery failsafe doing nothing: blocks under ADOS control, warns otherwise; the checklist offers its fix. */
    BatteryFailsafeOff(R.string.demo_scenario_battery_failsafe_off),

    /** On the ground with a battery failsafe RTL that would climb without GNSS: a warning the checklist offers to fix. */
    BatteryFailsafeConfinedRtl(R.string.demo_scenario_battery_failsafe_confined_rtl),

    /** Flying the flight controller's mission, waypoint 4 of 6, with an ETA to the next waypoint. */
    MissionActive(R.string.demo_scenario_mission_active),

    /** The mission paused at waypoint 4 of 6, the pilot holding. */
    MissionPaused(R.string.demo_scenario_mission_paused),

    /** The mission complete (6 of 6). */
    MissionComplete(R.string.demo_scenario_mission_complete),

    /** The flight controller entered RTL on its own (a failsafe); the pin holds it there. */
    FcFailsafeRtl(R.string.demo_scenario_fc_failsafe_rtl),

    /** The setup wizard's air run aborted at its altitude floor: `setup_abort` names why. */
    SetupAborted(R.string.demo_scenario_setup_aborted),

    /** On the ground in a confined place with a mission loaded: the route check finds every leg clear. */
    ConfinedRouteFeasible(R.string.demo_scenario_confined_route_feasible),

    /** On the ground in a confined place: the last legs run through cells not seen yet. */
    ConfinedRouteUnverified(R.string.demo_scenario_confined_route_unverified),

    /** On the ground in a confined place: waypoint 5 has no position, so the check cannot place it and a launch is refused. */
    ConfinedRouteUnchecked(R.string.demo_scenario_confined_route_unchecked),

    /** On the ground in a confined place: leg 3→4 passes too close to a wall, so a launch is refused. */
    ConfinedRouteInfeasible(R.string.demo_scenario_confined_route_infeasible),

    /** Flying the mission in a confined place near the clearance limit: the slow speed cap is on. */
    ConfinedNearLimit(R.string.demo_scenario_confined_near_limit),

    /** The pilot stops sending frames and answering actions while its socket stays open. */
    PilotStalled(R.string.demo_scenario_pilot_stalled),

    /** The control link timed out: `link_loss_in_s` counts down to a return home. */
    LinkLoss(R.string.demo_scenario_link_loss),

    /** The battery is low and the pilot may fly without GNSS: `rth_countdown_s` counts down to a landing. */
    LowBatteryCountdownLand(R.string.demo_scenario_low_battery_countdown_land),

    /** The low-battery countdown ran out: the pilot lands where it is and announces it. */
    LowBatteryLand(R.string.demo_scenario_low_battery_land),

    /** The low-battery countdown ran out: the flight controller flies SmartRTL home and the pilot announces it. */
    LowBatteryRth(R.string.demo_scenario_low_battery_rth),

    /** No GNSS fix: the demo flight controller loses its fix and position comes from the EKF origin only. */
    NoGnss(R.string.demo_scenario_no_gnss),

    /** Nine flight-controller status texts at warning severity, one of them long. */
    StatusTexts(R.string.demo_scenario_status_texts),

    /** The follow skill flies the vehicle; the demo's follow and track feeds run. */
    Follow(R.string.demo_scenario_follow),

    /** Airborne in a pilot hold offering `goto`, so Fly Here takes the pilot route. */
    FlyHere(R.string.demo_scenario_fly_here),

    HoldControllerLost(R.string.demo_scenario_hold_controller_lost, holdReason = "controller-lost"),
    HoldLinkLoss(R.string.demo_scenario_hold_link_loss, holdReason = "link-loss"),
    HoldObstacleClose(R.string.demo_scenario_hold_obstacle_close, holdReason = "obstacle-close"),
    HoldLeaseRevoked(R.string.demo_scenario_hold_lease_revoked, holdReason = "lease-revoked"),
    HoldBlocked(R.string.demo_scenario_hold_blocked, holdReason = "blocked"),

    /** A confined mission's next leg is too tight: the pilot holds and offers skip, return or land. */
    HoldRouteInfeasible(R.string.demo_scenario_hold_route_infeasible, holdReason = "route-infeasible"),

    GotoRefusedBadArgs(R.string.demo_scenario_goto_bad_args, gotoRefusal = "bad_args"),

    /** Reachable only through the pilot API: on the ground a surface's long-press opens Launch instead. */
    GotoRefusedNotArmed(R.string.demo_scenario_goto_not_armed, gotoRefusal = "not_armed"),

    /** Reachable only through the pilot API: on the ground a surface's long-press opens Launch instead. */
    GotoRefusedLanded(R.string.demo_scenario_goto_landed, gotoRefusal = "landed"),
    GotoRefusedTuning(R.string.demo_scenario_goto_tuning, gotoRefusal = "tuning"),
    GotoRefusedNoHome(R.string.demo_scenario_goto_no_home, gotoRefusal = "no_home"),
    GotoRefusedLaunching(R.string.demo_scenario_goto_launching, gotoRefusal = "launching"),

    /** Outside ADOS control the pilot offers no `goto`, so a surface refuses Fly Here itself. */
    GotoRefusedControlMode(R.string.demo_scenario_goto_control_mode, gotoRefusal = "control_mode"),
    GotoRefusedFailsafe(R.string.demo_scenario_goto_failsafe, gotoRefusal = "failsafe"),
}

/** The demo pilot's pinned scenario: null runs the demo live. In memory; demo mode only. */
@Singleton
class DemoScenarioStore @Inject constructor() {
    private val _pinned = MutableStateFlow<DemoScenario?>(null)
    val pinned: StateFlow<DemoScenario?> = _pinned.asStateFlow()

    /** Pins [scenario]; null unpins and the demo pilot reports its live state again. */
    fun pin(scenario: DemoScenario?) {
        _pinned.value = scenario
    }
}
