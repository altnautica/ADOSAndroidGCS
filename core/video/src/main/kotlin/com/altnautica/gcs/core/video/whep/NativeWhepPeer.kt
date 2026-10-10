package com.altnautica.gcs.core.video.whep

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A receive-only libwebrtc peer with one video transceiver and no audio. */
class NativeWhepPeer(
    environment: WebRtcEnvironment,
    private val sink: VideoSink,
    private val iceGatherTimeoutMs: Long = ICE_GATHER_MS,
) : WhepPeer {

    private val _connection = MutableStateFlow(PeerLinkState.New)
    override val connection: StateFlow<PeerLinkState> = _connection

    private val gathered = CompletableDeferred<Unit>()
    private var remoteTrack: VideoTrack? = null
    private var lastDelayS = 0.0
    private var lastEmitted = 0L

    private val pc: PeerConnection = requireNotNull(
        environment.factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            },
            Observer(),
        ),
    ) { "PeerConnectionFactory refused to create a peer" }

    init {
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )
    }

    override suspend fun createOffer(): String {
        val offer = sdpCall<SessionDescription> { pc.createOffer(it, MediaConstraints()) }
        sdpCall<Unit> { pc.setLocalDescription(it, offer) }
        withTimeoutOrNull(iceGatherTimeoutMs) { gathered.await() }
        return pc.localDescription?.description ?: offer.description
    }

    override suspend fun applyAnswer(sdp: String) {
        sdpCall<Unit> { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
    }

    override suspend fun latencyMs(): Int? = suspendCancellableCoroutine { cont ->
        pc.getStats { report ->
            val inbound = report.statsMap.values.firstOrNull {
                it.type == "inbound-rtp" && it.members["kind"] == "video"
            }
            val delay = (inbound?.members?.get("jitterBufferDelay") as? Number)?.toDouble()
            val emitted = (inbound?.members?.get("jitterBufferEmittedCount") as? Number)?.toLong()
            val ms = if (delay != null && emitted != null && emitted > lastEmitted) {
                ((delay - lastDelayS) / (emitted - lastEmitted) * 1000).toInt().also {
                    lastDelayS = delay
                    lastEmitted = emitted
                }
            } else {
                null
            }
            if (cont.isActive) cont.resume(ms)
        }
    }

    override fun close() {
        remoteTrack?.removeSink(sink)
        remoteTrack = null
        _connection.value = PeerLinkState.Closed
        pc.dispose()
    }

    private suspend fun <T> sdpCall(block: (SdpObserver) -> Unit): T =
        suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                @Suppress("UNCHECKED_CAST")
                override fun onCreateSuccess(desc: SessionDescription) { if (cont.isActive) cont.resume(desc as T) }

                @Suppress("UNCHECKED_CAST")
                override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit as T) }

                override fun onCreateFailure(error: String?) = fail(error)
                override fun onSetFailure(error: String?) = fail(error)
                private fun fail(error: String?) {
                    if (cont.isActive) cont.resumeWithException(WhepException(error ?: "SDP error"))
                }
            })
        }

    private inner class Observer : PeerConnection.Observer {
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            _connection.value = when (newState) {
                PeerConnection.PeerConnectionState.NEW -> PeerLinkState.New
                PeerConnection.PeerConnectionState.CONNECTING -> PeerLinkState.Connecting
                PeerConnection.PeerConnectionState.CONNECTED -> PeerLinkState.Connected
                PeerConnection.PeerConnectionState.DISCONNECTED -> PeerLinkState.Disconnected
                PeerConnection.PeerConnectionState.FAILED -> PeerLinkState.Failed
                PeerConnection.PeerConnectionState.CLOSED -> PeerLinkState.Closed
            }
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track() as? VideoTrack ?: return
            remoteTrack = track
            track.addSink(sink)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    companion object {
        /** LAN gathering finishes in well under this; past it, send what we have. */
        const val ICE_GATHER_MS = 2_000L
    }
}
