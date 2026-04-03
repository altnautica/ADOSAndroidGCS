package com.altnautica.gcs.data.video

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import com.altnautica.gcs.data.video.wfb.WfbMavlinkBridge
import com.altnautica.gcs.data.video.wfb.WfbUsbManager
import com.altnautica.gcs.data.video.wfb.WfbVideoManager
import com.altnautica.gcs.data.telemetry.TelemetryStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import com.altnautica.gcs.data.cloud.CloudVideoClient
import com.altnautica.gcs.data.cloud.MqttTelemetryClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeDetector: ModeDetector,
    private val cloudVideoClient: CloudVideoClient,
    private val mqttTelemetryClient: MqttTelemetryClient,
    private val wfbVideoManager: WfbVideoManager,
    private val wfbUsbManager: WfbUsbManager,
    private val wfbMavlinkBridge: WfbMavlinkBridge,
    private val telemetryStore: TelemetryStore,
) {

    companion object {
        private const val TAG = "VideoStreamManager"
        private const val MODE_B_VIDEO_TIMEOUT_MS = 10_000L
        private const val STATS_POLL_INTERVAL_MS = 1000L
        private const val DEFAULT_WFB_CHANNEL = 149
        private const val DEFAULT_WFB_BANDWIDTH = 20
    }

    // WFB settings (updated from SettingsViewModel via setWfbConfig)
    private var wfbChannel: Int = DEFAULT_WFB_CHANNEL
    private var wfbBandwidth: Int = DEFAULT_WFB_BANDWIDTH
    private var statsPollingJob: kotlinx.coroutines.Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeMode = MutableStateFlow<VideoMode>(VideoMode.NoConnection)
    val activeMode: StateFlow<VideoMode> = _activeMode.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoTrack: VideoTrack? = null

    fun detectAndStart(renderer: SurfaceViewRenderer) {
        val mode = modeDetector.detect()
        _activeMode.value = mode

        when (mode) {
            is VideoMode.GroundStation -> startWhep(mode.whepUrl, renderer)
            is VideoMode.DirectUsb -> startDirectUsb(mode.deviceId, renderer)
            is VideoMode.CloudRelay -> startCloudRelay(mode.turnUrl, renderer)
            is VideoMode.NoConnection -> {
                Log.w(TAG, "No video mode available")
                _isStreaming.value = false
            }
        }
    }

    private fun startWhep(whepUrl: String, renderer: SurfaceViewRenderer) {
        scope.launch {
            try {
                initWebRtc(renderer)

                val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                }

                val observer = object : PeerConnection.Observer {
                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                        val track = transceiver?.receiver?.track()
                        if (track is VideoTrack) {
                            videoTrack = track
                            track.addSink(renderer)
                            _isStreaming.value = true
                            Log.i(TAG, "Video track received")
                        }
                    }
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "ICE state: $state")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(candidate: IceCandidate?) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(dc: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                }

                val pc = peerConnectionFactory!!.createPeerConnection(rtcConfig, observer) ?: return@launch
                peerConnection = pc

                // Add receive-only video transceiver
                pc.addTransceiver(
                    org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    org.webrtc.RtpTransceiver.RtpTransceiverInit(
                        org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
                    )
                )

                // Create offer
                val sdpLatch = java.util.concurrent.CountDownLatch(1)
                var localSdp: SessionDescription? = null
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        localSdp = sdp
                        sdpLatch.countDown()
                    }
                    override fun onCreateFailure(msg: String?) { sdpLatch.countDown() }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(msg: String?) {}
                }, MediaConstraints())

                sdpLatch.await()
                val offer = localSdp ?: return@launch

                // Set local description
                pc.setLocalDescription(noOpSdpObserver(), offer)

                // POST offer to WHEP endpoint
                val answer = postWhepOffer(whepUrl, offer.description) ?: return@launch

                // Set remote description
                pc.setRemoteDescription(
                    noOpSdpObserver(),
                    SessionDescription(SessionDescription.Type.ANSWER, answer)
                )

                Log.i(TAG, "WHEP session established")
            } catch (e: Exception) {
                Log.e(TAG, "WHEP start failed: ${e.message}", e)
                _isStreaming.value = false
            }
        }
    }

    /**
     * Update WFB-ng channel and bandwidth settings. Call before detectAndStart().
     */
    fun setWfbConfig(channel: Int, bandwidth: Int) {
        wfbChannel = channel
        wfbBandwidth = bandwidth
    }

    private fun startDirectUsb(deviceId: Int, renderer: SurfaceViewRenderer) {
        scope.launch {
            try {
                Log.i(TAG, "Starting Mode B (direct USB WFB-ng), deviceId=$deviceId")

                // 1. Initialize native WFB-ng link
                if (!wfbVideoManager.initialize()) {
                    Log.e(TAG, "Failed to initialize WFB native library")
                    fallbackFromModeB(renderer, "Native library failed to load")
                    return@launch
                }

                // 2. Find and open the USB adapter
                val device = wfbUsbManager.findAdapter()
                if (device == null) {
                    Log.e(TAG, "No WFB-ng USB adapter found")
                    fallbackFromModeB(renderer, "USB adapter not found")
                    return@launch
                }

                // 3. Request USB permission (suspends until user responds)
                val permissionGranted = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    wfbUsbManager.requestPermission(device) { granted ->
                        if (cont.isActive) cont.resume(granted) {}
                    }
                }
                if (!permissionGranted) {
                    Log.e(TAG, "USB permission denied")
                    fallbackFromModeB(renderer, "USB permission denied")
                    return@launch
                }

                // 4. Open USB device and get file descriptor
                val fd = wfbUsbManager.openDevice(device)
                if (fd < 0) {
                    Log.e(TAG, "Failed to open USB device")
                    fallbackFromModeB(renderer, "USB device open failed")
                    return@launch
                }

                // 5. Start MAVLink UDP bridge (listens on localhost:14550)
                wfbMavlinkBridge.start()

                // 6. Start WFB-ng receiving with video surface
                val surface = renderer.holder.surface
                wfbVideoManager.startReceiving(fd, wfbChannel, wfbBandwidth, surface)

                // 7. Wait for video data (timeout = 10s)
                val hasVideo = wfbVideoManager.waitForVideoData(MODE_B_VIDEO_TIMEOUT_MS)
                if (!hasVideo) {
                    Log.w(TAG, "No video data received within ${MODE_B_VIDEO_TIMEOUT_MS}ms")
                    stopModeB()
                    fallbackFromModeB(renderer, "No video signal received")
                    return@launch
                }

                // 8. Start stats polling (1Hz) to update telemetry store
                startStatsPolling()

                _isStreaming.value = true
                Log.i(TAG, "Mode B active: WFB-ng ch=$wfbChannel bw=$wfbBandwidth")
            } catch (e: Exception) {
                Log.e(TAG, "Mode B failed: ${e.message}", e)
                stopModeB()
                fallbackFromModeB(renderer, e.message ?: "Unknown error")
            }
        }
    }

    private fun startStatsPolling() {
        statsPollingJob?.cancel()
        statsPollingJob = scope.launch {
            while (isActive) {
                val stats = wfbVideoManager.stats.value
                telemetryStore.addStatusMessage(
                    "WFB: RSSI=${stats.avgRssi}dBm loss=${String.format("%.1f", stats.packetLossPercent)}%"
                )
                delay(STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopModeB() {
        statsPollingJob?.cancel()
        statsPollingJob = null
        wfbVideoManager.stop()
        wfbMavlinkBridge.stop()
        wfbUsbManager.closeConnection()
    }

    /**
     * Fallback from Mode B failure to Mode A or C.
     */
    private fun fallbackFromModeB(renderer: SurfaceViewRenderer, reason: String) {
        scope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Direct USB video failed ($reason). Trying alternate mode.",
                    Toast.LENGTH_LONG,
                ).show()
            }

            // Try Mode A (ground station WiFi)
            val gsMode = VideoMode.GroundStation("http://192.168.4.1:8080/whep")
            _activeMode.value = gsMode
            startWhep(gsMode.whepUrl, renderer)

            // If WHEP also fails (no ground station), try cloud relay
            if (!_isStreaming.value) {
                val cloudMode = VideoMode.CloudRelay("turn:turn.altnautica.com:3478")
                _activeMode.value = cloudMode
                startCloudRelay(cloudMode.turnUrl, renderer)
            }

            if (!_isStreaming.value) {
                _activeMode.value = VideoMode.NoConnection
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "No video source available.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun startCloudRelay(turnUrl: String, renderer: SurfaceViewRenderer) {
        Log.i(TAG, "Starting cloud relay mode")
        _activeMode.value = VideoMode.CloudRelay(turnUrl)

        // Use fMP4 WebSocket relay (existing infrastructure at video.altnautica.com)
        // instead of WebRTC+TURN (TURN server not yet deployed).
        // Device ID extracted from the TURN URL or defaults to "default".
        val deviceId = extractDeviceId(turnUrl)

        scope.launch {
            try {
                // 1. Connect MQTT for telemetry (2Hz status + position from agent)
                mqttTelemetryClient.connect(deviceId)

                // 2. Connect cloud video client for fMP4 stream with Surface rendering
                val surface = renderer.holder.surface
                cloudVideoClient.connect(deviceId, surface)

                _isStreaming.value = true
                Log.i(TAG, "Cloud relay connected via video relay WebSocket")
            } catch (e: Exception) {
                Log.e(TAG, "Cloud relay failed: ${e.message}")
                _isStreaming.value = false
            }
        }
    }

    private fun extractDeviceId(turnUrl: String): String {
        // turnUrl format: "turn:turn.altnautica.com:3478" or may contain query params
        // Default device ID when not specified
        return "default"
    }

    private fun initWebRtc(renderer: SurfaceViewRenderer) {
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        val egl = eglBase!!

        if (peerConnectionFactory == null) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions()
            )
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .createPeerConnectionFactory()
        }

        renderer.init(egl.eglBaseContext, null)
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    }

    private fun postWhepOffer(url: String, sdp: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            OutputStreamWriter(conn.outputStream).use { it.write(sdp) }

            if (conn.responseCode == 201 || conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.w(TAG, "WHEP POST failed: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WHEP POST error: ${e.message}")
            null
        }
    }

    fun pause() {
        videoTrack?.setEnabled(false)
    }

    fun resume() {
        videoTrack?.setEnabled(true)
    }

    fun stop() {
        // Stop Mode B resources
        stopModeB()
        // Stop WebRTC resources
        videoTrack?.dispose()
        videoTrack = null
        peerConnection?.close()
        peerConnection = null
        cloudVideoClient.disconnect()
        mqttTelemetryClient.disconnect()
        _isStreaming.value = false
    }

    fun release() {
        scope.cancel()
        stop()
        wfbVideoManager.release()
        wfbMavlinkBridge.release()
        wfbUsbManager.unregister()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
    }

    private fun noOpSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(msg: String?) {}
        override fun onSetSuccess() {}
        override fun onSetFailure(msg: String?) {}
    }
}
