package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.agent.gimbal.GimbalApi
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The gimbal pad's link to the vehicle: polls `GET /api/v1/gimbal/rate` so the pad shows
 * only where rate control is available, and while the pad is deflected sends the rate
 * every [SEND_MS] with a [HOLD_MS] hold (the agent stops the gimbal when the holds lapse).
 * Letting go sends one zero rate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GimbalRateController(
    private val scope: CoroutineScope,
    node: StateFlow<DeviceId?>,
    private val api: suspend (DeviceId) -> GimbalApi?,
) {
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private var current: GimbalApi? = null
    private var target = 0f to 0f
    private var sender: Job? = null

    init {
        scope.launch {
            node.collectLatest { id ->
                current = null
                _available.value = false
                if (id == null) return@collectLatest
                while (isActive) {
                    val g = api(id)
                    current = g
                    val status = g?.rateStatus()
                    _available.value = (status as? AgentResult.Ok)?.value?.available == true
                    delay(POLL_MS)
                }
            }
        }
    }

    /** The pad's deflection, pitch and yaw in -1..1; (0, 0) stops the gimbal. */
    fun deflect(pitch: Float, yaw: Float) {
        target = pitch.coerceIn(-1f, 1f) * MAX_DPS to yaw.coerceIn(-1f, 1f) * MAX_DPS
        val g = current ?: return
        if (target.first == 0f && target.second == 0f) {
            sender?.cancel()
            sender = null
            scope.launch { g.setRate(0f, 0f, GimbalApi.HOLD_MIN_MS) }
            return
        }
        if (sender?.isActive == true) return
        sender = scope.launch {
            while (isActive) {
                val (p, y) = target
                g.setRate(p, y, HOLD_MS)
                delay(SEND_MS)
            }
        }
    }

    companion object {
        const val MAX_DPS = 60f
        const val HOLD_MS = 500
        const val SEND_MS = 150L
        const val POLL_MS = 10_000L
    }
}
