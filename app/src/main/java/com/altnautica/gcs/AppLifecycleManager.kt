package com.altnautica.gcs

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.altnautica.gcs.data.cloud.CloudVideoClient
import com.altnautica.gcs.data.cloud.MqttTelemetryClient
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.data.video.VideoStreamManager
import com.altnautica.gcs.data.wifi.WifiConnectionManager
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleManager @Inject constructor(
    private val modeDetector: ModeDetector,
    private val wifiManager: WifiConnectionManager,
    private val mavLinkRepository: MavLinkRepository,
    private val videoStreamManager: VideoStreamManager,
    private val groundStationRepository: GroundStationRepository,
    private val mqttTelemetryClient: MqttTelemetryClient,
    private val cloudVideoClient: CloudVideoClient,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            val mode = modeDetector.detect()
            when (mode) {
                is VideoMode.GroundStation -> {
                    wifiManager.requestGroundStationNetwork()
                    groundStationRepository.startPolling()
                    mavLinkRepository.connect()
                }
                is VideoMode.CloudRelay -> {
                    // Cloud mode: telemetry via MQTT, video via fMP4 WebSocket.
                    // No direct MAVLink connection needed.
                    val deviceId = "default"
                    mqttTelemetryClient.connect(deviceId)
                    cloudVideoClient.connect(deviceId)
                }
                is VideoMode.DirectUsb -> {
                    mavLinkRepository.connect()
                }
                is VideoMode.NoConnection -> {
                    mavLinkRepository.connect()
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        // App going to background — pause video, keep MAVLink alive
        videoStreamManager.pause()
    }

    override fun onStart(owner: LifecycleOwner) {
        // App coming to foreground — resume video
        videoStreamManager.resume()
    }

    fun shutdown() {
        scope.cancel()
        videoStreamManager.stop()
        mavLinkRepository.disconnect()
        wifiManager.releaseNetwork()
        groundStationRepository.stopPolling()
        mqttTelemetryClient.disconnect()
        cloudVideoClient.disconnect()
    }
}
