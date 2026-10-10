package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource

/**
 * The seam between the video fallback decision and the radio, network and
 * pairing state it depends on.
 *
 * Each source accessor returns a source only when its prerequisites hold, so
 * [VideoSourceSelector] can be driven by a fake in a JVM test without USB,
 * Wi-Fi or a paired node.
 */
interface VideoSourceEnvironment {

    /** The USB radio attached to this phone, or null when none is attached. */
    fun phoneRadio(): VideoSource.PhoneRadio?

    /**
     * A reachable local agent (ground-station Wi-Fi AP or USB-C tether) with
     * its WHEP endpoint, or null when neither is present.
     */
    fun agentWhep(): VideoSource.AgentWhep?

    /** A direct cloud peer-to-peer path to the paired node, or null when it cannot work. */
    fun cloudP2p(): VideoSource.CloudP2p?

    /**
     * The cloud relay, or null when it cannot work: no internet, or no paired
     * node whose device id the relay keys the stream on.
     */
    fun cloudRelay(): VideoSource.CloudRelay?

    /** Whether a validated internet-capable network is up. */
    fun hasInternet(): Boolean

    /** The node this install is paired with, when known. */
    fun pairedNode(): DeviceId?
}
