package com.altnautica.gcs.link

import android.util.Log
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.data.home.SelectedVehicleStore
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.node.canFly
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.video.HeroProfileHold
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the selected drone's link open while the app is on screen, so the
 * cockpit, parameters and setup screens find a live link instead of dialling
 * one on entry. An armed drone's link is also kept while the app is in the
 * background. A LAN drone is held on the full-rate video profile while the app
 * is on screen, so the cockpit's first frame does not wait for an encoder restart.
 *
 * It only observes the vehicle; a Disconnect from the link notification still
 * closes the link while this holder keeps observing.
 */
@Singleton
class SelectedLinkHolder @Inject constructor(
    private val selected: SelectedVehicleStore,
    private val nodes: NodeRepository,
    private val vehicles: VehicleRepository,
    private val heroHold: HeroProfileHold,
    private val endpoints: AgentEndpoints,
) {
    private data class Held(val id: DeviceId, val prewarm: Boolean)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, foreground: Flow<Boolean>) {
        var lastHeld: DeviceId? = null
        val target: Flow<NodeSummary?> = combine(selected.selected, nodes.nodes) { stored, list -> pick(stored, list) }
        val held: Flow<Held?> = foreground.distinctUntilChanged().flatMapLatest { onScreen ->
            if (onScreen) {
                combine(target, armedIds()) { n, armed ->
                    n?.takeIf { it.canFly() || it.id in armed }?.let { Held(it.id, prewarm = it.prewarmable()) }
                }
            } else {
                // Only the armed drone stays held off screen; node discovery is not observed here.
                armedIds().map { armed -> lastHeld?.takeIf { it in armed }?.let { Held(it, prewarm = false) } }
            }
        }
        scope.launch {
            held.distinctUntilChanged().collectLatest { h ->
                if (h == null) {
                    lastHeld = null
                    return@collectLatest
                }
                lastHeld = h.id
                Log.i(TAG, "selected_hold node=${h.id.value} prewarm=${h.prewarm}")
                coroutineScope {
                    if (h.prewarm) launch { holdHero(h.id) }
                    vehicles.vehicle(h.id).collect {}
                }
            }
        }
    }

    private suspend fun holdHero(id: DeviceId) {
        // The same base URL the cockpit passes, so its own hold joins this one without a move.
        val url = endpoints.endpointFor(id)?.baseUrl?.toString() ?: return
        val release = heroHold.acquire(id, url)
        try {
            awaitCancellation()
        } finally {
            release()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun armedIds(): Flow<Set<DeviceId>> = vehicles.openVehicles.flatMapLatest { open ->
        if (open.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(open.values.map { v -> v.telemetry.heartbeat.map { hb -> v.id.takeIf { hb?.armed == true } } }) { ids ->
                ids.filterNotNullTo(HashSet())
            }
        }
    }.distinctUntilChanged()

    private fun NodeSummary.prewarmable(): Boolean = profile == NodeProfile.Drone && Reach.Lan in reaches

    private companion object {
        const val TAG = "AdosLink"

        /** The stored selection when it is listed, else the first live drone that can fly. */
        fun pick(stored: DeviceId?, list: List<NodeSummary>?): NodeSummary? {
            if (list == null) return null
            return list.firstOrNull { it.id == stored } ?: list.firstOrNull { it.canFly() && it.liveness == Liveness.Live }
        }
    }
}
