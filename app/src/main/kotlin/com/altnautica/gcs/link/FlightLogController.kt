package com.altnautica.gcs.link

import com.altnautica.gcs.core.link.vehicle.MavlinkVehicle
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.feature.logs.FlightSessionTracker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Records a flight log for every open vehicle: one [FlightSessionTracker] job per
 * vehicle instance, fed with its telemetry and the raw frames its link receives.
 *
 * A link that closes and reopens on another reach keeps the same vehicle, so its
 * job survives a gap of up to [CLOSE_GRACE_MS]; a flight is not split by a
 * reach fallback. A new vehicle instance for the same id starts a new job.
 */
@Singleton
class FlightLogController internal constructor(
    private val openVehicles: Flow<Map<DeviceId, Vehicle>>,
    private val track: (Vehicle, CoroutineScope) -> Job,
    private val closeGraceMs: Long = CLOSE_GRACE_MS,
) {
    @Inject
    constructor(vehicles: VehicleRepository, tracker: FlightSessionTracker) : this(
        vehicles.openVehicles,
        { v, scope -> tracker.track(v.id, v.telemetry, (v as? MavlinkVehicle)?.link?.frames, scope) },
    )

    private class Tracked(val vehicle: Vehicle, val job: Job, var closing: Job? = null)

    /** Starts observing; the controller's state is touched only from [scope]'s collector. */
    fun start(scope: CoroutineScope) {
        // Frame taps and session stats run off the main thread.
        val work = scope + Dispatchers.Default
        val tracked = HashMap<DeviceId, Tracked>()
        scope.launch {
            openVehicles.collect { open ->
                for ((id, vehicle) in open) {
                    val current = tracked[id]
                    if (current != null && current.vehicle === vehicle) {
                        current.closing?.cancel()
                        current.closing = null
                        continue
                    }
                    current?.let { it.closing?.cancel(); it.job.cancel() }
                    tracked[id] = Tracked(vehicle, track(vehicle, work))
                }
                for ((id, t) in tracked) {
                    if (id in open || t.closing != null) continue
                    t.closing = scope.launch {
                        delay(closeGraceMs)
                        t.job.cancel()
                        if (tracked[id] === t) tracked.remove(id)
                    }
                }
            }
        }
    }

    companion object {
        /** How long a closed link may stay closed before its flight log is finalised. */
        const val CLOSE_GRACE_MS = 15_000L
    }
}
