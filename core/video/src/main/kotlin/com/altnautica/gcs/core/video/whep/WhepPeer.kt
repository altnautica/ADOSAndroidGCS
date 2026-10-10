package com.altnautica.gcs.core.video.whep

import kotlinx.coroutines.flow.StateFlow
import org.webrtc.VideoSink

/** Connection state of one peer, reduced to what the re-dial policy acts on. */
enum class PeerLinkState { New, Connecting, Connected, Disconnected, Failed, Closed }

/**
 * The WebRTC half of a WHEP session: one receive-only video peer. Kept behind
 * an interface so the re-dial policy is testable without native WebRTC.
 */
interface WhepPeer {
    val connection: StateFlow<PeerLinkState>

    /** Builds the offer and waits for ICE gathering, so candidates ride in-band. */
    suspend fun createOffer(): String

    suspend fun applyAnswer(sdp: String)

    /** Average jitter-buffer delay of the inbound video, when stats report it. */
    suspend fun latencyMs(): Int?

    fun close()
}

/** Creates a peer whose remote video track feeds [sink]. */
fun interface WhepPeerFactory {
    fun create(sink: VideoSink): WhepPeer
}
