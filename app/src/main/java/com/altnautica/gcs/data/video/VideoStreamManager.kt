package com.altnautica.gcs.data.video

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modeDetector: ModeDetector
) {

    companion object {
        private const val TAG = "VideoStreamManager"
    }

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

    private fun startDirectUsb(deviceId: Int, renderer: SurfaceViewRenderer) {
        // TODO: Implement Mode B via devourer USB driver + LiveVideo10ms decoder
        Log.i(TAG, "Direct USB mode not yet implemented (device $deviceId)")
        _isStreaming.value = false
    }

    private fun startCloudRelay(turnUrl: String, renderer: SurfaceViewRenderer) {
        // TODO: Implement Mode C cloud relay via TURN
        Log.i(TAG, "Cloud relay mode not yet implemented ($turnUrl)")
        _isStreaming.value = false
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

    fun stop() {
        videoTrack?.dispose()
        videoTrack = null
        peerConnection?.close()
        peerConnection = null
        _isStreaming.value = false
    }

    fun release() {
        stop()
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
