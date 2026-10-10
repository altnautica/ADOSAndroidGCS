package com.altnautica.gcs.core.video

import androidx.annotation.StringRes
import com.altnautica.gcs.core.model.VideoSource
import javax.inject.Inject

/**
 * The video fallback order, and what to say when none of it works.
 *
 * Nothing here touches hardware: it asks [VideoSourceEnvironment] which
 * sources are worth trying, so a JVM test can drive the whole decision.
 */
class VideoSourceSelector @Inject constructor(
    private val environment: VideoSourceEnvironment,
) {

    /**
     * Sources to try, in order: the phone's own radio, the local agent's WHEP
     * endpoint, the cloud peer-to-peer path, then the cloud relay.
     *
     * Each is offered only when its prerequisites hold, so an entry here is a
     * source that could work, not one that is certain to fail. Dialling a
     * fixed AP address while the phone is on neither the AP nor the tether
     * only buys a timeout.
     */
    fun candidates(): List<VideoSource> =
        listOfNotNull(
            environment.phoneRadio(),
            environment.agentWhep(),
            environment.cloudP2p(),
            environment.cloudRelay(),
        )

    /**
     * Why no video source is available, specific enough to act on.
     *
     * A bare "no video source available" sends operators looking for a fault
     * that is not there: the common case is a handset with internet but no
     * paired node, where the fix is to pair, not to check the radio.
     */
    @StringRes
    fun noSourceReason(): Int = when {
        environment.phoneRadio() != null || environment.agentWhep() != null ->
            R.string.video_no_source_all_failed
        environment.hasInternet() && environment.pairedNode() == null ->
            R.string.video_no_source_needs_pairing
        !environment.hasInternet() -> R.string.video_no_source_no_network
        else -> R.string.video_no_source_all_failed
    }
}
