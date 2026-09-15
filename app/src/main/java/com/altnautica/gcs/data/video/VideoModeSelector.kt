package com.altnautica.gcs.data.video

import androidx.annotation.StringRes
import com.altnautica.gcs.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The video fallback order, and what to say when none of it works.
 *
 * Extracted from [VideoStreamManager] because this is the behaviour most likely
 * to regress and the hardest to reproduce: it only runs after the USB radio has
 * already failed on real hardware. Left inline beside WebRTC and the USB radio
 * it could not be exercised at all, which is how a placeholder cloud-relay
 * device id survived in it.
 *
 * Nothing here touches hardware — it asks [VideoEnvironment] which transports
 * are worth trying — so a JVM test can drive the whole decision.
 */
@Singleton
class VideoModeSelector @Inject constructor(
    private val environment: VideoEnvironment,
) {

    /**
     * Candidate modes to try, in order, after the direct-USB radio has failed.
     *
     * The local agent first (ground-station AP or USB-C tether), then the cloud
     * relay. Each is offered only when its prerequisites hold — the relay needs
     * the paired node's device id — so an entry here is a transport that could
     * work, not one that is certain to fail. Dialling a fixed AP address while
     * the phone is on neither the AP nor the tether only bought a timeout.
     */
    fun fallbacksAfterDirectUsb(): List<VideoMode> =
        listOfNotNull(
            environment.localAgentMode(),
            environment.cloudRelayMode(),
        )

    /**
     * Why no video source is available, specific enough to act on.
     *
     * A bare "no video source available" sent operators looking for a fault
     * that was not there: the common case is a handset with internet but no
     * paired node, where the fix is to pair, not to check the radio.
     */
    @StringRes
    fun noSourceReason(): Int = when {
        environment.localAgentMode() != null -> R.string.video_no_source_all_failed
        environment.hasInternet() && environment.pairedDeviceId() == null ->
            R.string.video_no_source_needs_pairing
        !environment.hasInternet() -> R.string.video_no_source_no_network
        else -> R.string.video_no_source_all_failed
    }
}
