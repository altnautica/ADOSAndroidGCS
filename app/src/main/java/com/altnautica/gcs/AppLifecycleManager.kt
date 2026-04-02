package com.altnautica.gcs

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.altnautica.gcs.data.alerts.AlertEngine
import com.altnautica.gcs.data.alerts.TtsManager
import com.altnautica.gcs.data.cloud.CloudVideoClient
import com.altnautica.gcs.data.cloud.MqttTelemetryClient
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import com.altnautica.gcs.data.mavlink.MavLinkWiring
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
    private val mavLinkWiring: MavLinkWiring,
    private val alertEngine: AlertEngine,
    private val ttsManager: TtsManager,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        mavLinkWiring.initialize()
        ttsManager.initialize()
        alertEngine.start()
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
                    Log.w("AppLifecycleManager", "No connection mode detected, skipping MAVLink connect")
                    return@launch
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
        alertEngine.shutdown()
        ttsManager.shutdown()
        scope.cancel()
        videoStreamManager.stop()
        mavLinkRepository.disconnect()
        wifiManager.releaseNetwork()
        groundStationRepository.stopPolling()
        mqttTelemetryClient.disconnect()
        cloudVideoClient.disconnect()
        mavLinkWiring.shutdown()
    }
}
