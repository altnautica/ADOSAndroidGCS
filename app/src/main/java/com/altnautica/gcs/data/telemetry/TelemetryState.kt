package com.altnautica.gcs.data.telemetry

data class AttitudeState(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f
)

data class PositionState(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altMsl: Float = 0f,
    val altRel: Float = 0f,
    val heading: Int = 0
)

data class BatteryState(
    val voltage: Float = 0f,
    val current: Float = 0f,
    val remaining: Int = -1
)

data class GpsState(
    val fixType: Int = 0,
    val satellites: Int = 0,
    val hdop: Int = 0,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val alt: Float = 0f
)

data class VfrState(
    val airspeed: Float = 0f,
    val groundspeed: Float = 0f,
    val heading: Int = 0,
    val throttle: Int = 0,
    val alt: Float = 0f,
    val climb: Float = 0f
)

data class SysStatusState(
    val sensorsPresent: Long = 0,
    val sensorsEnabled: Long = 0,
    val sensorsHealth: Long = 0,
    val load: Int = 0,
    val voltageBattery: Int = 0,
    val currentBattery: Int = 0,
    val batteryRemaining: Int = -1
)

data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val message: String = ""
)

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    LOST
}

enum class FlightMode(val modeNumber: Int, val label: String) {
    STABILIZE(0, "Stabilize"),
    ACRO(1, "Acro"),
    ALT_HOLD(2, "AltHold"),
    AUTO(3, "Auto"),
    GUIDED(4, "Guided"),
    LOITER(5, "Loiter"),
    RTL(6, "RTL"),
    CIRCLE(7, "Circle"),
    LAND(9, "Land"),
    DRIFT(11, "Drift"),
    SPORT(13, "Sport"),
    FLIP(14, "Flip"),
    AUTOTUNE(15, "AutoTune"),
    POSHOLD(16, "PosHold"),
    BRAKE(17, "Brake"),
    THROW(18, "Throw"),
    SMART_RTL(21, "SmartRTL"),
    ZIGZAG(24, "ZigZag");

    companion object {
        fun fromNumber(n: Int): FlightMode? = entries.find { it.modeNumber == n }
    }
}
