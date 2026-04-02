package com.altnautica.gcs.data.followme

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class FollowMeEngine @Inject constructor(
    private val commandSender: MavLinkCommandSender,
    private val telemetryStore: TelemetryStore,
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "FollowMeEngine"
        private const val LOCATION_INTERVAL_MS = 500L // 2Hz
        private const val GPS_ACCURACY_START_GATE = 10f // refuse to start if >10m
        private const val GPS_ACCURACY_STOP_GATE = 50f // auto-stop if >50m
        private const val EARTH_RADIUS_M = 6_371_000.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _phoneLocation = MutableStateFlow<Location?>(null)
    val phoneLocation: StateFlow<Location?> = _phoneLocation.asStateFlow()

    private val _gpsAccuracy = MutableStateFlow(Float.MAX_VALUE)
    val gpsAccuracy: StateFlow<Float> = _gpsAccuracy.asStateFlow()

    private val _lastTarget = MutableStateFlow<GuidedTarget?>(null)
    val lastTarget: StateFlow<GuidedTarget?> = _lastTarget.asStateFlow()

    private var algorithm: FollowAlgorithm = FollowAlgorithm.Leash()
    private var altitudeOffset: Float = 15f
    private var orbitAngleRad: Double = 0.0
    private var lastUpdateTimeMs: Long = 0L
    private var previousLocation: Location? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            _phoneLocation.value = location
            _gpsAccuracy.value = location.accuracy

            // Auto-stop: GPS degraded
            if (location.accuracy > GPS_ACCURACY_STOP_GATE) {
                Log.w(TAG, "GPS accuracy degraded to ${location.accuracy}m, stopping follow-me")
                stop()
                return
            }

            // Auto-stop: connection lost
            val connStatus = telemetryStore.connection.value.status
            if (connStatus == ConnectionStatus.DISCONNECTED || connStatus == ConnectionStatus.LOST) {
                Log.w(TAG, "Drone connection lost, stopping follow-me")
                stop()
                return
            }

            scope.launch {
                val target = computeTarget(location)
                _lastTarget.value = target
                sendGuidedPosition(target)
                sendRoiToPhone(location)
            }

            previousLocation = location
            lastUpdateTimeMs = System.currentTimeMillis()
        }
    }

    /**
     * Start follow-me mode. Returns false if GPS accuracy is too low to begin.
     */
    @SuppressLint("MissingPermission")
    fun start(algo: FollowAlgorithm, altOffset: Float = 15f): Boolean {
        val currentAccuracy = _gpsAccuracy.value
        if (currentAccuracy > GPS_ACCURACY_START_GATE && currentAccuracy != Float.MAX_VALUE) {
            Log.w(TAG, "GPS accuracy too low ($currentAccuracy m), refusing to start")
            return false
        }

        algorithm = algo
        altitudeOffset = altOffset
        orbitAngleRad = 0.0
        lastUpdateTimeMs = System.currentTimeMillis()
        previousLocation = null

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .build()

        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        // Switch drone to GUIDED mode
        scope.launch {
            commandSender.sendSetMode(FlightMode.GUIDED)
        }

        _isActive.value = true
        Log.i(TAG, "Follow-me started: algorithm=${algo.name}, altOffset=$altOffset")
        return true
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        _isActive.value = false
        _lastTarget.value = null
        previousLocation = null
        Log.i(TAG, "Follow-me stopped")
    }

    /**
     * Request a single location fix to populate the accuracy indicator
     * before the user starts follow-me mode.
     */
    @SuppressLint("MissingPermission")
    fun requestSingleFix() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMaxUpdates(1)
            .build()
        fusedClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                _phoneLocation.value = loc
                _gpsAccuracy.value = loc.accuracy
                fusedClient.removeLocationUpdates(this)
            }
        }, Looper.getMainLooper())
    }

    // ---- Algorithm implementations ----

    private fun computeTarget(phone: Location): GuidedTarget {
        val phoneLat = phone.latitude
        val phoneLon = phone.longitude
        val phoneAltM = phone.altitude.toFloat()
        val targetAlt = phoneAltM + altitudeOffset

        return when (val algo = algorithm) {
            is FollowAlgorithm.Leash -> computeLeash(phoneLat, phoneLon, targetAlt, algo)
            is FollowAlgorithm.Lead -> computeLead(phone, targetAlt, algo)
            is FollowAlgorithm.Orbit -> computeOrbit(phoneLat, phoneLon, targetAlt, algo)
            is FollowAlgorithm.Above -> GuidedTarget(phoneLat, phoneLon, targetAlt)
        }
    }

    /**
     * Leash: target = phone position. The drone only moves when the distance
     * from the drone to the phone exceeds [FollowAlgorithm.Leash.radiusM].
     */
    private fun computeLeash(
        phoneLat: Double,
        phoneLon: Double,
        alt: Float,
        algo: FollowAlgorithm.Leash,
    ): GuidedTarget {
        val dronePos = telemetryStore.position.value
        val dist = haversineMeters(dronePos.lat, dronePos.lon, phoneLat, phoneLon)
        return if (dist > algo.radiusM) {
            GuidedTarget(phoneLat, phoneLon, alt)
        } else {
            // Stay put: send the drone's current position at the target altitude
            GuidedTarget(dronePos.lat, dronePos.lon, alt)
        }
    }

    /**
     * Lead: target = phone position projected forward by [FollowAlgorithm.Lead.leadDistanceM]
     * in the direction of travel (bearing from speed vector).
     */
    private fun computeLead(
        phone: Location,
        alt: Float,
        algo: FollowAlgorithm.Lead,
    ): GuidedTarget {
        val prev = previousLocation
        if (prev == null || phone.speed < 0.5f) {
            // Not moving: just hover at phone position
            return GuidedTarget(phone.latitude, phone.longitude, alt)
        }

        val bearingDeg = prev.bearingTo(phone).toDouble()
        val bearingRad = Math.toRadians(bearingDeg)
        val offset = offsetLatLon(phone.latitude, phone.longitude, bearingRad, algo.leadDistanceM.toDouble())
        return GuidedTarget(offset.first, offset.second, alt)
    }

    /**
     * Orbit: target = phone position + rotating offset. The drone circles the
     * operator at [FollowAlgorithm.Orbit.radiusM].
     */
    private fun computeOrbit(
        phoneLat: Double,
        phoneLon: Double,
        alt: Float,
        algo: FollowAlgorithm.Orbit,
    ): GuidedTarget {
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTimeMs).coerceAtLeast(1L) / 1000.0

        // Angular velocity = tangential speed / radius (rad/s)
        val angularVelocity = algo.speedMs / algo.radiusM
        orbitAngleRad += angularVelocity * dt

        val offset = offsetLatLon(phoneLat, phoneLon, orbitAngleRad, algo.radiusM.toDouble())
        return GuidedTarget(offset.first, offset.second, alt)
    }

    // ---- MAVLink senders ----

    /**
     * Send the drone to a guided position using MAV_CMD_DO_REPOSITION.
     * Params: p1=speed (-1=default), p5=lat, p6=lon, p7=alt.
     */
    private suspend fun sendGuidedPosition(target: GuidedTarget) {
        // MAV_CMD_DO_REPOSITION = 192
        commandSender.sendCommandLongRaw(
            commandId = 192,
            param1 = -1f, // default ground speed
            param2 = 1f, // MAV_DO_REPOSITION_FLAGS_CHANGE_MODE
            param5 = target.lat.toFloat(),
            param6 = target.lon.toFloat(),
            param7 = target.alt,
        )
    }

    /**
     * Point the drone camera back at the operator's phone.
     */
    private suspend fun sendRoiToPhone(phone: Location) {
        commandSender.sendSetRoi(
            lat = phone.latitude,
            lon = phone.longitude,
            alt = phone.altitude.toFloat(),
        )
    }

    // ---- Geometry helpers ----

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Returns (lat, lon) offset from [lat], [lon] by [distanceM] meters
     * at [bearingRad] radians (0 = north, clockwise).
     */
    private fun offsetLatLon(
        lat: Double,
        lon: Double,
        bearingRad: Double,
        distanceM: Double,
    ): Pair<Double, Double> {
        val dR = distanceM / EARTH_RADIUS_M
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)

        val newLatRad = Math.asin(
            sin(latRad) * cos(dR) + cos(latRad) * sin(dR) * cos(bearingRad)
        )
        val newLonRad = lonRad + atan2(
            sin(bearingRad) * sin(dR) * cos(latRad),
            cos(dR) - sin(latRad) * sin(newLatRad)
        )

        return Math.toDegrees(newLatRad) to Math.toDegrees(newLonRad)
    }
}
