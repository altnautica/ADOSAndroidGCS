package com.altnautica.gcs.data.video

sealed class VideoMode {
    data class GroundStation(val whepUrl: String) : VideoMode()
    data class DirectUsb(val deviceId: Int) : VideoMode()

    /**
     * Cloud relay: the last-resort path when neither the USB radio nor the
     * ground-station Wi-Fi is reachable.
     *
     * [deviceId] is the paired node's agent device id, which is what the relay
     * keys both the video stream and the MQTT telemetry topic on. It is not
     * derivable from [turnUrl] and has no usable default: a placeholder here
     * subscribes to a device that exists for nobody, and the operator sees an
     * empty stream rather than "this needs a paired node". The mode is only
     * constructed once a device id is known.
     */
    data class CloudRelay(val turnUrl: String, val deviceId: String) : VideoMode()

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
