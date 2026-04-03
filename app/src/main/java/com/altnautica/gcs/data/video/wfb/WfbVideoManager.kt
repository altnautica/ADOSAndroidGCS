package com.altnautica.gcs.data.video.wfb

import android.content.Context
import android.util.Log
import android.view.Surface
import com.openipc.videonative.DecodingInfo
import com.openipc.videonative.IVideoParamsChanged
import com.openipc.videonative.VideoPlayer
import com.openipc.wfbngrtl8812.WfbNGStats
import com.openipc.wfbngrtl8812.WfbNGStatsChanged
import com.openipc.wfbngrtl8812.WfbNgLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the WFB-ng native link and video player for Mode B (direct USB).
 *
 * Wraps the Java JNI shims (WfbNgLink + VideoPlayer) with Kotlin coroutine-friendly
 * APIs and exposes link stats as StateFlows.
 */
@Singleton
class WfbVideoManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val TAG = "WfbVideoManager"
        private const val STATS_POLL_INTERVAL_MS = 300L
        private const val VIDEO_CALLBACK_INTERVAL_MS = 200L
        private const val VIDEO_DATA_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var wfbLink: WfbNgLink? = null
    private var videoPlayer: VideoPlayer? = null
    private var wfbThread: Thread? = null
    private var statsJob: Job? = null
    private var videoCallbackJob: Job? = null

    private val _stats = MutableStateFlow(WfbLinkStats())
    val stats: StateFlow<WfbLinkStats> = _stats.asStateFlow()

    private val _videoWidth = MutableStateFlow(0)
    val videoWidth: StateFlow<Int> = _videoWidth.asStateFlow()

    private val _videoHeight = MutableStateFlow(0)
    val videoHeight: StateFlow<Int> = _videoHeight.asStateFlow()

    private val _decodingFps = MutableStateFlow(0f)
    val decodingFps: StateFlow<Float> = _decodingFps.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0f)
    val bitrateKbps: StateFlow<Float> = _bitrateKbps.asStateFlow()

    private val _hasVideoData = MutableStateFlow(false)
    val hasVideoData: StateFlow<Boolean> = _hasVideoData.asStateFlow()

    private var currentFd: Int = -1

    /**
     * Initialize the native WFB-ng link. Must be called before [startReceiving].
     * @return true if native initialization succeeded.
     */
    fun initialize(): Boolean {
        return try {
            wfbLink = WfbNgLink(context)
            wfbLink?.setStatsCallback(object : WfbNGStatsChanged {
                override fun onWfbNgStatsChanged(data: WfbNGStats) {
                    _stats.value = WfbLinkStats(
                        totalPackets = data.count_p_all,
                        decryptErrors = data.count_p_dec_err,
                        decryptOk = data.count_p_dec_ok,
                        fecRecovered = data.count_p_fec_recovered,
                        packetsLost = data.count_p_lost,
                        packetsBad = data.count_p_bad,
                        packetsOverride = data.count_p_override,
                        packetsOutgoing = data.count_p_outgoing,
                        avgRssi = data.avg_rssi,
                    )
                }
            })

            videoPlayer = VideoPlayer(context)
            videoPlayer?.setVideoParamsCallback(object : IVideoParamsChanged {
                override fun onVideoRatioChanged(videoW: Int, videoH: Int) {
                    _videoWidth.value = videoW
                    _videoHeight.value = videoH
                }

                override fun onDecodingInfoChanged(info: DecodingInfo) {
                    _decodingFps.value = info.currentFPS
                    _bitrateKbps.value = info.currentKiloBitsPerSecond
                }
            })

            Log.i(TAG, "WFB-ng native link initialized")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed: ${e.message}")
            false
        }
    }

    /**
     * Start WFB-ng receiving on the given USB file descriptor and display video
     * on the provided surface.
     *
     * @param usbFd File descriptor from UsbDeviceConnection.getFileDescriptor()
     * @param channel WiFi channel (e.g., 149, 161)
     * @param bandwidth Channel bandwidth: 20 or 40 MHz
     * @param surface Surface to render decoded video frames on
     */
    fun startReceiving(usbFd: Int, channel: Int, bandwidth: Int, surface: Surface) {
        val link = wfbLink ?: run {
            Log.e(TAG, "WFB link not initialized")
            return
        }
        val player = videoPlayer ?: run {
            Log.e(TAG, "Video player not initialized")
            return
        }

        currentFd = usbFd

        // Set the video surface (index 0 = primary)
        player.setVideoSurface(surface, 0)

        // Start the video decoder listener (receives UDP from WFB-ng on port 5600)
        player.start()

        // Run WFB-ng link on a background thread (blocks until stopped)
        wfbThread = Thread({
            Log.i(TAG, "WFB-ng link thread started (ch=$channel, bw=$bandwidth, fd=$usbFd)")
            link.run(context, channel, bandwidth, usbFd)
            Log.i(TAG, "WFB-ng link thread exited")
        }, "wfb-link-$usbFd").also { it.start() }

        // Start stats polling
        startStatsPolling()

        // Start video callback polling
        startVideoCallbackPolling()

        Log.i(TAG, "WFB-ng receiving started on channel $channel (${bandwidth}MHz)")
    }

    /**
     * Wait for video data to arrive within a timeout.
     * @return true if video data was received, false if timed out.
     */
    suspend fun waitForVideoData(timeoutMs: Long = VIDEO_DATA_TIMEOUT_MS): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val player = videoPlayer ?: return false
            if (player.hasVideoData()) {
                _hasVideoData.value = true
                return true
            }
            delay(500)
        }
        return false
    }

    /**
     * Stop all WFB-ng receiving and clean up.
     */
    fun stop() {
        statsJob?.cancel()
        statsJob = null
        videoCallbackJob?.cancel()
        videoCallbackJob = null

        val link = wfbLink
        val fd = currentFd
        if (link != null && fd >= 0) {
            try {
                WfbNgLink.nativeStop(link.nativeHandle, context, fd)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping WFB link: ${e.message}")
            }
        }

        wfbThread?.let { thread ->
            try {
                thread.join(3000)
                if (thread.isAlive) {
                    Log.w(TAG, "WFB thread did not exit within 3s")
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted waiting for WFB thread")
            }
        }
        wfbThread = null

        videoPlayer?.let { player ->
            try {
                player.stop()
                player.setVideoSurface(null, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping video player: ${e.message}")
            }
        }

        currentFd = -1
        _hasVideoData.value = false
        _stats.value = WfbLinkStats()

        Log.i(TAG, "WFB-ng receiving stopped")
    }

    /**
     * Release all native resources. Call when the manager is no longer needed.
     */
    fun release() {
        stop()
        videoPlayer?.release()
        videoPlayer = null
        wfbLink = null
        scope.cancel()
        Log.i(TAG, "WFB-ng resources released")
    }

    fun getRssi(): Int = _stats.value.avgRssi

    fun getPacketLossPercent(): Float = _stats.value.packetLossPercent

    fun refreshKey() {
        wfbLink?.refreshKey()
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                try {
                    wfbLink?.pollStats()
                } catch (e: Exception) {
                    Log.w(TAG, "Stats poll error: ${e.message}")
                }
                delay(STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun startVideoCallbackPolling() {
        videoCallbackJob?.cancel()
        videoCallbackJob = scope.launch {
            while (isActive) {
                try {
                    videoPlayer?.pollCallbacks()
                } catch (e: Exception) {
                    Log.w(TAG, "Video callback poll error: ${e.message}")
                }
                delay(VIDEO_CALLBACK_INTERVAL_MS)
            }
        }
    }
}
