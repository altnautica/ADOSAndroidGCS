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

data class ParamValueState(
    val name: String = "",
    val value: Float = 0f,
    val type: Int = 0,
    val index: Int = 0,
    val count: Int = 0
)

data class CommandAckState(
    val commandId: Int = 0,
    val result: Int = 0
)

data class RcChannelsRawState(
    val chan1: Int = 0,
    val chan2: Int = 0,
    val chan3: Int = 0,
    val chan4: Int = 0,
    val chan5: Int = 0,
    val chan6: Int = 0,
    val chan7: Int = 0,
    val chan8: Int = 0,
    val rssi: Int = 0
)

data class MissionProgressState(
    val currentSeq: Int = -1,
    val lastReachedSeq: Int = -1
)

data class NavControllerState(
    val navRoll: Float = 0f,
    val navPitch: Float = 0f,
    val navBearing: Int = 0,
    val targetBearing: Int = 0,
    val wpDist: Int = 0,
    val altError: Float = 0f,
    val xtrackError: Float = 0f
)

data class ImuState(
    val xacc: Int = 0,
    val yacc: Int = 0,
    val zacc: Int = 0,
    val xgyro: Int = 0,
    val ygyro: Int = 0,
    val zgyro: Int = 0,
    val xmag: Int = 0,
    val ymag: Int = 0,
    val zmag: Int = 0
)

data class ServoOutputState(
    val servo1: Int = 0,
    val servo2: Int = 0,
    val servo3: Int = 0,
    val servo4: Int = 0,
    val servo5: Int = 0,
    val servo6: Int = 0,
    val servo7: Int = 0,
    val servo8: Int = 0
)

data class VibrationState(
    val vibrationX: Float = 0f,
    val vibrationY: Float = 0f,
    val vibrationZ: Float = 0f,
    val clipping0: Long = 0,
    val clipping1: Long = 0,
    val clipping2: Long = 0
)

data class EkfState(
    val velocityVariance: Float = 0f,
    val posHorizVariance: Float = 0f,
    val posVertVariance: Float = 0f,
    val compassVariance: Float = 0f,
    val terrainAltVariance: Float = 0f,
    val flags: Int = 0
)

data class RcChannelsState(
    val chancount: Int = 0,
    val channels: List<Int> = emptyList(),
    val rssi: Int = 0
)

data class WindState(
    val direction: Float = 0f,
    val speed: Float = 0f,
    val speedZ: Float = 0f
)

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
