package com.altnautica.gcs.ui.gcs

import androidx.lifecycle.ViewModel
import com.altnautica.gcs.data.followme.FollowAlgorithm
import com.altnautica.gcs.data.followme.FollowMeEngine
import com.altnautica.gcs.data.video.VideoEnvironment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel boundary in front of the follow-me engine.
 *
 * The panel used to take [FollowMeEngine] and the mode detector directly and
 * collect their flows in the composition, so a location engine and a MAVLink
 * guided-target loop were both reachable from a recomposition.
 */
@HiltViewModel
class FollowMeViewModel @Inject constructor(
    private val engine: FollowMeEngine,
    private val environment: VideoEnvironment,
) : ViewModel() {

    val isActive: StateFlow<Boolean> = engine.isActive
    val gpsAccuracy: StateFlow<Float> = engine.gpsAccuracy

    /**
     * True when the only link is the direct USB radio.
     *
     * Follow-me needs a MAVLink uplink to push guided targets; on the
     * radio-only path there is no local agent to carry them.
     */
    val directUsbOnly: Boolean
        get() = environment.localAgentMode() == null && environment.cloudRelayMode() == null

    fun requestSingleFix() {
        engine.requestSingleFix()
    }

    fun start(algo: FollowAlgorithm, altOffset: Float): Boolean = engine.start(algo, altOffset)

    fun stop() {
        engine.stop()
    }
}
