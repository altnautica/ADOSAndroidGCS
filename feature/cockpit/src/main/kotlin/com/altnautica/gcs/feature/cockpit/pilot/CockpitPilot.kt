package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.skills.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Where operator sticks go right now. */
enum class StickRoute {
    /** The onboard pilot over the pilot channel (`control_mode` ados). */
    Pilot,

    /** MANUAL_CONTROL to the flight controller (`fc_manual`, or a flight controller attached to the phone). */
    FcManual,

    /** Nowhere from this surface: an ELRS lane flies, or the pilot channel is down in ados mode. */
    None,
}

/**
 * Stick routing: the pilot channel when the vehicle's pilot is in `ados` and the channel
 * is up; MANUAL_CONTROL when the pilot is in `fc_manual`, or when the flight controller is
 * attached to the phone ([directFc]) and no onboard pilot reports a control mode;
 * otherwise nothing, so the two paths never fight. A vehicle reached through an agent
 * whose pilot has not reported a control mode gets no sticks.
 */
fun stickRoute(pilotLinked: Boolean, pilot: PilotState?, directFc: Boolean): StickRoute = when (pilot?.controlMode) {
    ControlMode.Ados -> if (pilotLinked) StickRoute.Pilot else StickRoute.None
    ControlMode.Elrs -> StickRoute.None
    ControlMode.FcManual -> StickRoute.FcManual
    null -> if (directFc) StickRoute.FcManual else StickRoute.None
}

/** The newer of two pilot states by local receive time. */
fun newestPilot(a: PilotState?, b: PilotState?): PilotState? = when {
    a == null -> b
    b == null -> a
    b.receivedAtMs > a.receivedAtMs -> b
    else -> a
}

/**
 * The flown vehicle's pilot channel and pilot state. One [PilotChannel] runs per flown
 * node while this object's scope lives; it is replaced when the node changes. [state]
 * is the newest of the channel's state and the navigation stream's `pilot` kind.
 *
 * Channels run in their own scope on the same dispatcher, so a channel closed with its
 * owner (the node changes, or the cockpit is cleared) still says `bye` before its socket
 * closes ([PilotChannel.leave]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitPilot(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    vehicleNodeId: StateFlow<DeviceId?>,
    private val api: suspend (DeviceId) -> AutonomyFeedApi?,
    navPilot: StateFlow<PilotState?>,
    /** How the flown vehicle is reached, or null without one. */
    reach: Flow<Reach?>,
    /** Where pilot frames are parsed ([PilotChannel]). */
    private val parseContext: CoroutineContext = EmptyCoroutineContext,
    /** True while the link notification's Pause, Land and Return can be reached. */
    private val notificationReachable: () -> Boolean = { false },
    /** The no-RC heartbeat's main-looper tick, one per channel. */
    private val ticker: () -> Ticker = { MainLooperTicker(PilotChannel.TICK_MS) },
) {
    private val channelScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())

    /** True while the cockpit screen is resumed: its Pause (brake) is under the operator's finger. */
    @Volatile var resumed: Boolean = false

    /** A stop control is reachable: the cockpit is resumed, or the link notification carries Pause, Land and Return. */
    fun controlReachable(): Boolean = resumed || notificationReachable()

    /** The flown node's pilot channel, or null without a node. */
    val link: StateFlow<PilotChannel?> = vehicleNodeId
        .runningFold<DeviceId?, Pair<DeviceId?, PilotChannel?>>(null to null) { (_, old), id ->
            old?.let { channelScope.launch { it.leave() } }
            id to id?.let { channelFor(it).also(PilotChannel::start) }
        }
        .map { it.second }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // The owner is going (the cockpit is closing): the channel leaves on purpose, then its
        // scope ends. Taken when the owner starts cancelling, not when it has completed: a child
        // of the owner that takes a while to finish must not keep the channel's ticks running,
        // and the scope ends even if the leave fails.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                channelScope.launch {
                    try {
                        link.value?.leave()
                    } finally {
                        channelScope.cancel()
                    }
                }
            }
        }
    }

    /** True while the pilot channel of the flown node receives frames. */
    val linked: StateFlow<Boolean> = link.flatMapLatest { it?.connected ?: flowOf(false) }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** The newest pilot state from the channel or the navigation stream. */
    val state: StateFlow<PilotState?> = combine(link.flatMapLatest { it?.state ?: flowOf(null) }, navPilot, ::newestPilot)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Where the sticks go. */
    val route: StateFlow<StickRoute> = combine(linked, state, reach.map { it == Reach.DirectFc }, ::stickRoute)
        .stateIn(scope, SharingStarted.Eagerly, StickRoute.None)

    /** The sink the stick streamer sends to: the channel while the route is the pilot. */
    val stickSink: StateFlow<StickSink?> = combine(link, route) { l, r -> l?.takeIf { r == StickRoute.Pilot } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun channelFor(id: DeviceId) =
        PilotChannel(channelScope, clock, parseContext, ::controlReachable, ticker()) { outgoing -> pilotStream(id, outgoing) }

    private fun pilotStream(id: DeviceId, outgoing: Flow<String>): Flow<String> = flow {
        val a = api(id) ?: throw IOException("no agent path to the vehicle")
        emitAll(a.pilotStream(outgoing))
    }
}
