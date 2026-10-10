package com.altnautica.gcs.core.link.manual

import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.mavlink.messages.ManualControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Stick input for `MANUAL_CONTROL`. [pitch], [roll] and [yaw] are -1..1
 * (positive = nose down / right / clockwise), [throttle] is 0..1. [buttons]
 * must already exclude buttons the cockpit reserves for skills.
 */
data class ManualControlInput(
    val pitch: Float,
    val roll: Float,
    val throttle: Float,
    val yaw: Float,
    val buttons: Int,
) {
    companion object {
        /** Sticks centred, throttle at its bottom. */
        val NEUTRAL = ManualControlInput(0f, 0f, 0f, 0f, 0)
    }
}

/** Streams `MANUAL_CONTROL` while started. */
interface ManualControlStreamer {
    val enabled: StateFlow<Boolean>

    /** Starts (or retargets) the 25 Hz stream reading the newest value of [input]. */
    fun start(input: StateFlow<ManualControlInput>)

    fun stop()
}

class MavlinkManualControlStreamer(
    private val scope: CoroutineScope,
    private val link: MavlinkLink,
) : ManualControlStreamer {

    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private var job: Job? = null

    @Synchronized
    override fun start(input: StateFlow<ManualControlInput>) {
        job?.cancel()
        _enabled.value = true
        job = scope.launch {
            while (isActive) {
                val target = link.target.value
                if (target != null) link.send(encode(target.sysId, input.value))
                delay(PERIOD_MS)
            }
        }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
        job = null
        _enabled.value = false
    }

    companion object {
        /** 25 Hz. */
        const val PERIOD_MS = 40L

        fun encode(targetSystem: Int, input: ManualControlInput): ManualControl = ManualControl(
            target = targetSystem,
            x = scale(input.pitch),
            y = scale(input.roll),
            z = (input.throttle.coerceIn(0f, 1f) * 1000f).toInt(),
            r = scale(input.yaw),
            buttons = input.buttons and 0xFFFF,
        )

        private fun scale(v: Float): Int = (v.coerceIn(-1f, 1f) * 1000f).toInt()
    }
}
