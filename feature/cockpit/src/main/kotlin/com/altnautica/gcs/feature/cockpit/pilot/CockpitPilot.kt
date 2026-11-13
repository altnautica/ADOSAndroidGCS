package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.skills.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
) {
    /** The flown node's pilot channel, or null without a node. */
    val link: StateFlow<PilotChannel?> = vehicleNodeId
        .runningFold<DeviceId?, Pair<DeviceId?, PilotChannel?>>(null to null) { (_, old), id ->
            old?.stop()
            id to id?.let { channelFor(it).also(PilotChannel::start) }
        }
        .map { it.second }
        .stateIn(scope, SharingStarted.Eagerly, null)

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

    private fun channelFor(id: DeviceId) = PilotChannel(scope, clock, parseContext) { outgoing -> pilotStream(id, outgoing) }

    private fun pilotStream(id: DeviceId, outgoing: Flow<String>): Flow<String> = flow {
        val a = api(id) ?: throw IOException("no agent path to the vehicle")
        emitAll(a.pilotStream(outgoing))
    }
}
