package com.altnautica.gcs.feature.cockpit.alerts

import androidx.compose.ui.graphics.toArgb
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.input.controller.Rumble
import com.altnautica.gcs.core.skills.spec.LightbarSpec
import com.altnautica.gcs.core.skills.spec.RumbleSpec
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.LightbarState
import com.altnautica.gcs.feature.cockpit.pilot.lightbarState
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/** One frame of the light bar: its colour (0xAARRGGBB), pattern and rate. */
data class LightbarFrame(val argb: Int, val pattern: String, val hz: Float?)

/** The light bar frame for [state] per the cockpit spec. */
fun lightbarFrame(state: LightbarState, spec: LightbarSpec): LightbarFrame {
    val s = spec.states.getValue(state.wire)
    return LightbarFrame(parseHex(s.color), s.pattern, s.hz)
}

/** `#RRGGBB` or `#RRGGBBAA` to 0xAARRGGBB. */
internal fun parseHex(hex: String): Int {
    val h = hex.removePrefix("#")
    val rgb = h.substring(0, 6).toLong(16)
    val a = if (h.length >= 8) h.substring(6, 8).toLong(16) else 0xFF
    return ((a shl 24) or rgb).toInt()
}

/** Rumble pattern of one spec entry. */
internal fun rumbleOf(spec: RumbleSpecPulses): Rumble = Rumble(spec.pulsesMs, spec.gapMs, maxOf(spec.strong, spec.weak))

private typealias RumbleSpecPulses = com.altnautica.gcs.core.skills.spec.RumblePulsesSpec

/**
 * Controller light bar and rumble for the cockpit. The light bar follows the flight state
 * (disarmed, armed idle, pilot, skill, mission, RTH blinking, low battery pulsing, failsafe
 * blinking fast) only while the cockpit is started, and returns to the accent colour when
 * it stops. A flight-state change gives one short pulse, a new warning two, a new critical
 * alert three long ones; a confirmed skill gives one short pulse. All of it is best effort.
 */
internal class ControllerFeedback(
    private val scope: CoroutineScope,
    private val controllers: ControllerManager,
    private val alerts: StateFlow<List<CockpitAlert>>,
    private val context: StateFlow<ControllerContext>,
    private val lightbar: LightbarSpec,
    private val rumble: RumbleSpec,
    /** Runs the light bar (off the main thread in the app): a blink or pulse is a 20 Hz loop of binder calls. */
    private val lightbarWork: CoroutineContext,
) {
    private val palette = MutableStateFlow(AdosColors.BrandDark)
    private var job: Job? = null

    /** Guards [lit]: a frame drawn off the main thread never lands after [stop] restored the accent. */
    private val lightLock = Any()
    private var lit = false

    /** The current theme's colour roles, used when the cockpit stops. */
    fun setPalette(colors: AdosColorRoles) {
        palette.value = colors
    }

    fun start() {
        if (job?.isActive == true) return
        synchronized(lightLock) { lit = true }
        job = scope.launch {
            launch(lightbarWork) {
                val state = context.map(::lightbarState).distinctUntilChanged()
                // Re-applied when another controller becomes active.
                val device = controllers.active.map { it?.deviceId }.distinctUntilChanged()
                combine(state, device) { s, _ -> lightbarFrame(s, lightbar) }.collectLatest(::show)
            }
            launch {
                var last: LightbarState? = null
                context.map(::lightbarState).distinctUntilChanged().collect { s ->
                    if (last != null) controllers.rumble(rumbleOf(rumble.modeChange))
                    last = s
                }
            }
            launch {
                // Alerts already up when the cockpit starts were raised before; only new ones rumble.
                var known = alerts.value.mapTo(HashSet()) { it.id }
                alerts.collect { list ->
                    val fresh = list.filter { it.id !in known }
                    known = list.mapTo(HashSet()) { it.id }
                    when {
                        fresh.any { it.level == AlertLevel.Critical } -> controllers.rumble(rumbleOf(rumble.critical))
                        fresh.any { it.level == AlertLevel.Warning } -> controllers.rumble(rumbleOf(rumble.warning))
                    }
                }
            }
        }
    }

    /** Draws [frame] until cancelled: solid, a 50 % blink at `hz`, or a cosine pulse at `hz`. */
    private suspend fun show(frame: LightbarFrame) {
        val hz = frame.hz
        if (frame.pattern == SOLID || hz == null || hz <= 0f) {
            light(frame.argb)
            return
        }
        val periodMs = (1_000f / hz).toLong()
        var t = 0L
        while (true) {
            val level = when (frame.pattern) {
                BLINK -> if (t % periodMs < periodMs / 2) 1f else 0f
                else -> (0.5f - 0.5f * cos(2.0 * PI * t / periodMs).toFloat()).coerceIn(PULSE_FLOOR, 1f)
            }
            light(scaled(frame.argb, level))
            delay(FRAME_MS)
            t += FRAME_MS
        }
    }

    private fun light(argb: Int) = synchronized(lightLock) {
        if (lit) controllers.setLightbar(argb)
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(lightLock) {
            lit = false
            controllers.setLightbar(palette.value.accentPrimary.toArgb())
        }
    }

    /** A confirm sheet resolved confirmed. */
    fun onConfirmed() = controllers.rumble(rumbleOf(rumble.modeChange))

    /** A ramp tick during a hold at [level] (0..1), or one countdown pulse. */
    fun onHoldCue(kind: String, level: Float) {
        when (kind) {
            "ramp" -> controllers.rumble(Rumble(listOf(rumble.ramp.tickMs), 0, level.coerceIn(0f, 1f)))
            "countdown" -> controllers.rumble(Rumble(listOf(rumble.countdown.pulseMs), 0, 1f))
            else -> controllers.rumble(rumbleOf(rumble.modeChange))
        }
    }

    private companion object {
        const val SOLID = "solid"
        const val BLINK = "blink"
        const val FRAME_MS = 50L
        const val PULSE_FLOOR = 0.15f

        fun scaled(argb: Int, level: Float): Int {
            val r = ((argb shr 16) and 0xFF) * level
            val g = ((argb shr 8) and 0xFF) * level
            val b = (argb and 0xFF) * level
            return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
        }
    }
}
