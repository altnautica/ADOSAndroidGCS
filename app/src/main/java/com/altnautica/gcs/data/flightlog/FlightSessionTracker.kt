package com.altnautica.gcs.data.flightlog

import android.util.Log
import com.altnautica.gcs.data.telemetry.TelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tracks flight sessions by observing the armed state.
 *
 * On arm: creates a new FlightSession, starts tlog recording, and begins
 * tracking max altitude, speed, distance from home, and total distance.
 *
 * On disarm: stops tlog recording, finalizes session stats, and inserts
 * the completed session into the Room database.
 */
@Singleton
class FlightSessionTracker @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val tlogRecorder: TlogRecorder,
    private val flightSessionDao: FlightSessionDao,
) {

    companion object {
        private const val TAG = "FlightSessionTracker"
        private const val EARTH_RADIUS_M = 6371000.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-flight tracking state
    private var sessionStartTime = 0L
    private var maxAltitude = 0f
    private var maxSpeed = 0f
    private var maxDistance = 0f
    private var totalDistance = 0f
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasLastPosition = false
    private var batteryStart = -1
    private var tlogPath = ""
    private var isTracking = false

    fun initialize() {
        scope.launch {
            telemetryStore.armed
                .distinctUntilChanged()
                .collect { armed ->
                    if (armed) {
                        onArmed()
                    } else if (isTracking) {
                        onDisarmed()
                    }
                }
        }

        // Track telemetry during flight
        scope.launch {
            telemetryStore.position.collect { pos ->
                if (!isTracking) return@collect
                val alt = pos.altRel
                maxAltitude = max(maxAltitude, alt)

                // Distance from home
                val home = telemetryStore.homePosition.value
                if (home != null && home.lat != 0.0) {
                    val dist = haversineMeters(home.lat, home.lon, pos.lat, pos.lon)
                    maxDistance = max(maxDistance, dist.toFloat())
                }

                // Total distance (cumulative path)
                if (hasLastPosition && pos.lat != 0.0) {
                    val segDist = haversineMeters(lastLat, lastLon, pos.lat, pos.lon)
                    if (segDist < 1000) { // Ignore GPS jumps > 1km
                        totalDistance += segDist.toFloat()
                    }
                }
                if (pos.lat != 0.0) {
                    lastLat = pos.lat
                    lastLon = pos.lon
                    hasLastPosition = true
                }
            }
        }

        scope.launch {
            telemetryStore.vfr.collect { vfr ->
                if (!isTracking) return@collect
                maxSpeed = max(maxSpeed, vfr.groundspeed)
            }
        }
    }

    private fun onArmed() {
        Log.i(TAG, "Armed -- starting flight session")
        isTracking = true
        sessionStartTime = System.currentTimeMillis()
        maxAltitude = 0f
        maxSpeed = 0f
        maxDistance = 0f
        totalDistance = 0f
        hasLastPosition = false
        batteryStart = telemetryStore.battery.value.remaining

        val path = tlogRecorder.start()
        tlogPath = path ?: ""
    }

    private fun onDisarmed() {
        Log.i(TAG, "Disarmed -- ending flight session")
        isTracking = false

        tlogRecorder.stop()

        val endTime = System.currentTimeMillis()
        val durationSec = ((endTime - sessionStartTime) / 1000).toInt()
        val batteryEnd = telemetryStore.battery.value.remaining

        val session = FlightSession(
            startTime = sessionStartTime,
            endTime = endTime,
            durationSeconds = durationSec,
            maxAltitude = maxAltitude,
            maxSpeed = maxSpeed,
            maxDistance = maxDistance,
            totalDistance = totalDistance,
            batteryStart = batteryStart,
            batteryEnd = batteryEnd,
            tlogPath = tlogPath,
        )

        scope.launch {
            try {
                flightSessionDao.insert(session)
                Log.i(TAG, "Flight session saved: ${durationSec}s, maxAlt=$maxAltitude")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save flight session: ${e.message}", e)
            }
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
