package com.altnautica.gcs

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
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
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun initialize() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            val mode = modeDetector.detect()
            if (mode is VideoMode.GroundStation) {
                wifiManager.requestGroundStationNetwork()
                groundStationRepository.startPolling()
            }
            mavLinkRepository.connect()
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
    }
}
