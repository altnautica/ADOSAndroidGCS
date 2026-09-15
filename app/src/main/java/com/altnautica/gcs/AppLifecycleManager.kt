package com.altnautica.gcs

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.altnautica.gcs.data.flightlog.FlightSessionTracker
import com.altnautica.gcs.data.alerts.AlertEngine
import com.altnautica.gcs.data.alerts.TtsManager
import com.altnautica.gcs.data.cloud.CloudVideoClient
import com.altnautica.gcs.data.cloud.MqttTelemetryClient
import com.altnautica.gcs.data.mavlink.HeartbeatPump
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import com.altnautica.gcs.data.mavlink.MavLinkWiring
import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.data.video.VideoStreamManager
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import com.altnautica.gcs.data.pairing.PairingRepository
import com.altnautica.gcs.data.wifi.GroundStationApStore
import com.altnautica.gcs.data.wifi.WifiConnectionManager
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
    private val heartbeatPump: HeartbeatPump,
    private val alertEngine: AlertEngine,
    private val ttsManager: TtsManager,
    private val flightSessionTracker: FlightSessionTracker,
    private val pairingRepository: PairingRepository,
    private val apStore: GroundStationApStore,
) : DefaultLifecycleObserver {

    private companion object {
        const val TAG = "AppLifecycleManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        mavLinkWiring.initialize()
        heartbeatPump.start()
        ttsManager.initialize()
        alertEngine.start()
        flightSessionTracker.initialize()
        scope.launch {
            // Pairing posture first: the key gates every agent REST call, and a
            // probe here means the UI can name an unpaired node rather than
            // showing generic failures on every surface.
            pairingRepository.refresh()

            val mode = modeDetector.detect()
            when (mode) {
                is VideoMode.GroundStation -> {
                    joinGroundStationApIfCredentialed()
                    groundStationRepository.startPolling()
                    mavLinkRepository.connect()
                }
                is VideoMode.CloudRelay -> {
                    // Cloud mode: telemetry via MQTT, video via fMP4 WebSocket.
                    // No direct MAVLink connection needed. The device id comes
                    // from the pairing record — the mode cannot exist without
                    // one — so nothing here invents a placeholder.
                    mqttTelemetryClient.connect(mode.deviceId)
                    cloudVideoClient.connect(mode.deviceId)
                }
                is VideoMode.DirectUsb -> {
                    mavLinkRepository.connect()
                }
                is VideoMode.NoConnection -> {
                    Log.w(TAG, "No connection mode detected, skipping MAVLink connect")
                    // Still poll and dial: the operator may be on a LAN the
                    // detector has no signal for (a routed network rather than
                    // the AP), where the configured base URL is correct.
                    groundStationRepository.startPolling()
                    mavLinkRepository.connect()
                }
            }
        }
    }

    /**
     * Join the ground-station AP only when the operator has supplied this
     * unit's passphrase.
     *
     * The agent generates that passphrase per unit, so there is nothing to
     * guess: without a stored credential the join is skipped and Settings
     * prompts for it. Joining is also not required — an operator already on the
     * same LAN by another route reaches the agent without it.
     */
    private suspend fun joinGroundStationApIfCredentialed() {
        val credential = apStore.current()
        if (credential == null) {
            Log.i(TAG, "ground-station AP passphrase not set; not attempting a join")
            return
        }
        wifiManager.requestGroundStationNetwork(
            passphrase = credential.passphrase,
            ssidSuffix = credential.ssidSuffix,
        )
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
        heartbeatPump.release()
        mavLinkWiring.shutdown()
    }
}
