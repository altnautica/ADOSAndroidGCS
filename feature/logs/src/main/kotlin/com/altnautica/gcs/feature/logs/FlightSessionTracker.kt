package com.altnautica.gcs.feature.logs

import android.util.Log
import com.altnautica.gcs.core.database.FlightSessionDao
import com.altnautica.gcs.core.database.FlightSessionEntity
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks flight sessions by observing a vehicle's armed state.
 *
 * On arm: starts tlog recording and begins tracking max altitude, speed,
 * distance from home, and total path distance.
 *
 * On disarm, or when the tracking job ends while armed: stops tlog recording,
 * finalizes the session stats, and inserts the completed session.
 */
@Singleton
class FlightSessionTracker @Inject constructor(
    private val tlogRecorder: TlogRecorder,
    private val flightSessionDao: FlightSessionDao,
) {

    private companion object {
        const val TAG = "FlightSessionTracker"
        const val EARTH_RADIUS_M = 6371000.0
        const val MAX_SEGMENT_M = 1000.0
    }

    private sealed interface Sample {
        data class Position(val fix: PositionFix) : Sample
        data class Speed(val groundspeedMs: Float) : Sample
    }

    /** Accumulated stats for one armed period. Only touched by its own collector. */
    private class Session(val startTime: Long, val batteryStart: Int, val tlogPath: String) {
        var maxAltitude = 0f
        var maxSpeed = 0f
        var maxDistance = 0f
        var totalDistance = 0f
        var lastLat = 0.0
        var lastLon = 0.0
        var hasLastPosition = false
    }

    /**
     * Track [telemetry] for [vehicleId] until the returned job, or [scope], is cancelled.
     */
    fun track(vehicleId: DeviceId, telemetry: VehicleTelemetry, scope: CoroutineScope): Job =
        scope.launch {
            telemetry.heartbeat
                .filterNotNull()
                .map { it.armed }
                .distinctUntilChanged()
                .collectLatest { armed ->
                    if (armed) runSession(vehicleId, telemetry)
                }
        }

    /** Runs for one armed period; cancelled by the next disarm. */
    private suspend fun runSession(vehicleId: DeviceId, telemetry: VehicleTelemetry) {
        Log.i(TAG, "${vehicleId.value} armed, starting flight session")
        val session = Session(
            startTime = System.currentTimeMillis(),
            batteryStart = telemetry.battery.value?.remainingPct ?: -1,
            tlogPath = tlogRecorder.start() ?: "",
        )
        try {
            merge(
                telemetry.position.filterNotNull().map { Sample.Position(it) },
                telemetry.vfr.filterNotNull().map { Sample.Speed(it.groundspeedMs) },
            ).collect { sample ->
                when (sample) {
                    is Sample.Position -> onPosition(session, sample.fix, telemetry)
                    is Sample.Speed -> {
                        session.maxSpeed = max(session.maxSpeed, sample.groundspeedMs)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                finish(vehicleId, session, telemetry)
            }
        }
    }

    private fun onPosition(session: Session, pos: PositionFix, telemetry: VehicleTelemetry) {
        session.maxAltitude = max(session.maxAltitude, pos.altRelM)

        // Distance from home
        val home = telemetry.home.value
        if (home != null && home.latDeg != 0.0) {
            val dist = haversineMeters(home.latDeg, home.lonDeg, pos.latDeg, pos.lonDeg)
            session.maxDistance = max(session.maxDistance, dist.toFloat())
        }

        // Total distance (cumulative path), ignoring GPS jumps over 1 km
        if (session.hasLastPosition && pos.latDeg != 0.0) {
            val segDist = haversineMeters(session.lastLat, session.lastLon, pos.latDeg, pos.lonDeg)
            if (segDist < MAX_SEGMENT_M) {
                session.totalDistance += segDist.toFloat()
            }
        }
        if (pos.latDeg != 0.0) {
            session.lastLat = pos.latDeg
            session.lastLon = pos.lonDeg
            session.hasLastPosition = true
        }
    }

    private suspend fun finish(vehicleId: DeviceId, session: Session, telemetry: VehicleTelemetry) {
        Log.i(TAG, "${vehicleId.value} ending flight session")
        tlogRecorder.stop()

        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - session.startTime) / 1000).toInt()

        val entity = FlightSessionEntity(
            startTime = session.startTime,
            endTime = endTime,
            durationSeconds = durationSec,
            maxAltitude = session.maxAltitude,
            maxSpeed = session.maxSpeed,
            maxDistance = session.maxDistance,
            totalDistance = session.totalDistance,
            batteryStart = session.batteryStart,
            batteryEnd = telemetry.battery.value?.remainingPct ?: -1,
            tlogPath = session.tlogPath,
        )

        try {
            flightSessionDao.insert(entity)
            Log.i(TAG, "Flight session saved: ${durationSec}s, maxAlt=${session.maxAltitude}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flight session: ${e.message}", e)
        }
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }
}
