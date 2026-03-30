package com.altnautica.gcs.data.video

sealed class VideoMode {
    data class GroundStation(val whepUrl: String) : VideoMode()
    data class DirectUsb(val deviceId: Int) : VideoMode()
    data class CloudRelay(val turnUrl: String) : VideoMode()
    data object NoConnection : VideoMode()
}
