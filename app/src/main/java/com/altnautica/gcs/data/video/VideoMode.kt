package com.altnautica.gcs.data.video

sealed class VideoMode {
    data class GroundStation(val whepUrl: String) : VideoMode()
    data class DirectUsb(val deviceId: Int) : VideoMode()
    data class CloudRelay(val turnUrl: String) : VideoMode()
    data object NoConnection : VideoMode()
}

/**
 * Connection mode for MAVLink telemetry. Separate from VideoMode because
 * Mode D (USB serial) provides telemetry without any video stream.
 */
sealed class ConnectionMode {
    /** WebSocket via ground station WiFi AP or cloud relay. */
    data class WebSocket(val videoMode: VideoMode) : ConnectionMode()

    /** Direct USB serial to flight controller. MAVLink only, no video. */
    data class DirectSerial(val deviceName: String) : ConnectionMode()
}
