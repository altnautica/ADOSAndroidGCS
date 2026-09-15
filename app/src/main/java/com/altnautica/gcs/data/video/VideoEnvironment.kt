package com.altnautica.gcs.data.video

/**
 * The seam between the video fallback decision and the hardware, radio and
 * network state it depends on.
 *
 * [ModeDetector] is the production implementation and reaches into USB, Wi-Fi
 * and ConnectivityManager; nothing behind this interface can be constructed in
 * a JVM test. Naming the four questions the decision actually asks lets
 * [VideoModeSelector] be driven with a fake, which is the only way the
 * USB → local agent → cloud relay ordering gets exercised without a rig.
 */
interface VideoEnvironment {

    /**
     * A reachable local agent — the ground-station Wi-Fi AP or the USB-C
     * tether — with the WHEP URL to pull video from, or null when neither is
     * present.
     */
    fun localAgentMode(): VideoMode.GroundStation?

    /**
     * The cloud relay, or null when it cannot work: no internet, or no paired
     * node whose device id the relay keys the stream on.
     */
    fun cloudRelayMode(): VideoMode.CloudRelay?

    /** Whether a validated internet-capable network is up. */
    fun hasInternet(): Boolean

    /** Device id of the node this install is paired with, when known. */
    fun pairedDeviceId(): String?
}
