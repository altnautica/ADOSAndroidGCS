package com.altnautica.gcs.data.alerts

import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

enum class Severity { INFO, WARNING, CRITICAL }

sealed class Alert(val severity: Severity, val message: String) {
    data class BatteryLow(val pct: Int) : Alert(Severity.WARNING, "Battery low, $pct percent")
    data class BatteryCritical(val pct: Int) : Alert(Severity.CRITICAL, "Battery critical. Land now.")
    data class SignalLost(val msg: String = "Signal lost") : Alert(Severity.CRITICAL, msg)
    data class SignalRecovered(val msg: String = "Signal recovered") : Alert(Severity.INFO, msg)
    data class GpsLost(val sats: Int) : Alert(Severity.WARNING, "GPS lost, $sats satellites")
    data class FailsafeActive(val type: String) : Alert(Severity.CRITICAL, "Failsafe activated. $type")
    data class AltitudeLimit(val alt: Int) : Alert(Severity.WARNING, "Altitude limit. $alt meters")
    data class Armed(val msg: String = "Vehicle armed") : Alert(Severity.INFO, msg)
    data class Disarmed(val msg: String = "Vehicle disarmed") : Alert(Severity.INFO, msg)
    data class ModeChanged(val mode: String) : Alert(Severity.INFO, "Mode: $mode")
    data class WaypointReached(val seq: Int) : Alert(Severity.INFO, "Waypoint $seq")
    data class GeofenceBreach(val msg: String = "Geofence breach") : Alert(Severity.CRITICAL, msg)
}

data class AlertEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val alert: Alert,
)

@Singleton
class AlertEngine @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val ttsManager: TtsManager,
    private val vibrationManager: VibrationManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Debounce tracking: alert class simple name -> last fire time
    private val lastAlertTimes = mutableMapOf<String, Long>()
    private val debouncePeriodMs = 10_000L

    // Configurable thresholds
    var batteryWarningPct = 20
    var batteryCriticalPct = 10
    var altitudeLimitMeters: Int? = null // null = disabled

    private val _alertHistory = MutableStateFlow<List<AlertEntry>>(emptyList())
    val alertHistory: StateFlow<List<AlertEntry>> = _alertHistory.asStateFlow()

    fun start() {
        watchBattery()
        watchConnection()
        watchGps()
        watchArmed()
        watchFlightMode()
        watchMissionProgress()
        watchAltitude()
    }

    fun clearHistory() {
        _alertHistory.value = emptyList()
    }

    fun shutdown() {
        scope.cancel()
    }

    // -- Watchers --

    private fun watchBattery() {
        scope.launch {
            telemetryStore.battery
                .map { it.remaining }
                .distinctUntilChanged()
                .collect { remaining ->
                    if (remaining < 0) return@collect // unknown
                    when {
                        remaining <= batteryCriticalPct -> fire(Alert.BatteryCritical(remaining))
                        remaining <= batteryWarningPct -> fire(Alert.BatteryLow(remaining))
                    }
                }
        }
    }

    private fun watchConnection() {
        scope.launch {
            telemetryStore.connection
                .map { it.status }
                .distinctUntilChanged()
                .collectLatest { status ->
                    when (status) {
                        ConnectionStatus.LOST -> {
                            // Wait 3 seconds before confirming signal lost
                            delay(3000)
                            if (telemetryStore.connection.value.status == ConnectionStatus.LOST) {
                                fire(Alert.SignalLost())
                            }
                        }
                        ConnectionStatus.CONNECTED -> {
                            // Only fire recovered if we previously fired signal lost
                            val lostKey = Alert.SignalLost::class.simpleName ?: "SignalLost"
                            if (lastAlertTimes.containsKey(lostKey)) {
                                fire(Alert.SignalRecovered())
                            }
                        }
                        else -> { /* no alert */ }
                    }
                }
        }
    }

    private fun watchGps() {
        scope.launch {
            telemetryStore.gps
                .map { it.fixType to it.satellites }
                .distinctUntilChanged()
                .collect { (fixType, sats) ->
                    if (fixType in 1..2) {
                        fire(Alert.GpsLost(sats))
                    }
                }
        }
    }

    private fun watchArmed() {
        scope.launch {
            var first = true
            telemetryStore.armed
                .distinctUntilChanged()
                .collect { armed ->
                    // Skip the initial value emission
                    if (first) {
                        first = false
                        return@collect
                    }
                    if (armed) {
                        fire(Alert.Armed())
                    } else {
                        fire(Alert.Disarmed())
                    }
                }
        }
    }

    private fun watchFlightMode() {
        scope.launch {
            var first = true
            telemetryStore.flightMode
                .distinctUntilChanged()
                .collect { mode ->
                    if (first) {
                        first = false
                        return@collect
                    }
                    if (mode != null) {
                        fire(Alert.ModeChanged(mode.label))
                    }
                }
        }
    }

    private fun watchMissionProgress() {
        scope.launch {
            var lastReached = -1
            telemetryStore.missionProgress
                .map { it.lastReachedSeq }
                .distinctUntilChanged()
                .collect { seq ->
                    if (seq >= 0 && seq != lastReached) {
                        lastReached = seq
                        fire(Alert.WaypointReached(seq))
                    }
                }
        }
    }

    private fun watchAltitude() {
        scope.launch {
            telemetryStore.position
                .map { it.altRel }
                .distinctUntilChanged()
                .collect { altRel ->
                    val limit = altitudeLimitMeters ?: return@collect
                    if (altRel >= limit) {
                        fire(Alert.AltitudeLimit(limit))
                    }
                }
        }
    }

    // -- Core fire logic --

    private fun fire(alert: Alert) {
        val key = alert::class.simpleName ?: return
        val now = System.currentTimeMillis()
        val lastTime = lastAlertTimes[key]

        if (lastTime != null && (now - lastTime) < debouncePeriodMs) {
            return // debounced
        }
        lastAlertTimes[key] = now

        // Add to history (cap at 200 entries)
        _alertHistory.update { current ->
            (listOf(AlertEntry(timestamp = now, alert = alert)) + current).take(200)
        }

        // TTS + vibration
        ttsManager.speak(alert.message, alert.severity)
        vibrationManager.vibrate(alert.severity)
    }
}
