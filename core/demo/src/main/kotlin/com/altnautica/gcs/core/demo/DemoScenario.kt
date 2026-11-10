package com.altnautica.gcs.core.demo

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
    /** The `hold_reason` the pilot reports while pinned, for the hold pins. */
    val holdReason: String? = null,
    /** The code a `goto` action is refused with while pinned, for the goto refusal pins. */
    val gotoRefusal: String? = null,
) {
    /** On the ground with the position readiness item failing (block): the chip reads NOT READY. */
    ReadinessBlocked,

    /** Flying the flight controller's mission, waypoint 4 of 6, with an ETA to the next waypoint. */
    MissionActive,

    /** The mission paused at waypoint 4 of 6, the pilot holding. */
    MissionPaused,

    /** The mission complete (6 of 6). */
    MissionComplete,

    /** The flight controller entered RTL on its own (a failsafe); the pin holds it there. */
    FcFailsafeRtl,

    /** The pilot stops sending frames and answering actions while its socket stays open. */
    PilotStalled,

    /** The control link timed out: `link_loss_in_s` counts down to a return home. */
    LinkLoss,

    /** No GNSS fix: position comes from the EKF origin only. */
    NoGnss,

    /** Nine flight-controller status texts at warning severity, one of them long. */
    StatusTexts,

    /** The follow skill flies the vehicle; the demo's follow and track feeds run. */
    Follow,

    /** Airborne in a pilot hold offering `goto`, so Fly Here takes the pilot route. */
    FlyHere,

    HoldControllerLost(holdReason = "controller-lost"),
    HoldLinkLoss(holdReason = "link-loss"),
    HoldObstacleClose(holdReason = "obstacle-close"),
    HoldLeaseRevoked(holdReason = "lease-revoked"),
    HoldLeaseBusy(holdReason = "lease-busy"),
    HoldBlocked(holdReason = "blocked"),

    GotoRefusedBadArgs(gotoRefusal = "bad_args"),
    GotoRefusedNotArmed(gotoRefusal = "not_armed"),
    GotoRefusedLanded(gotoRefusal = "landed"),
    GotoRefusedTuning(gotoRefusal = "tuning"),
    GotoRefusedNoHome(gotoRefusal = "no_home"),
    GotoRefusedLaunching(gotoRefusal = "launching"),
    GotoRefusedControlMode(gotoRefusal = "control_mode"),
    GotoRefusedFailsafe(gotoRefusal = "failsafe"),
    ;

    /** True when the pilot offers `goto` (`surface_ops`) while pinned. */
    val offersGoto: Boolean get() = this == FlyHere || gotoRefusal != null
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
