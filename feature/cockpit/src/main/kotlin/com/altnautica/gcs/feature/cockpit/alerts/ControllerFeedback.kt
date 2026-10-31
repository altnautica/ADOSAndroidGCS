package com.altnautica.gcs.feature.cockpit.alerts

import androidx.compose.ui.graphics.toArgb
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.input.controller.Rumble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the controller light bar shows while the cockpit is started, most urgent first. */
enum class LightbarState { Critical, ArmedStick, Armed, Idle }

/** Light bar state for the current alerts, arm state and stick control. */
fun lightbarState(alerts: List<CockpitAlert>, armed: Boolean, stickOn: Boolean): LightbarState = when {
    alerts.any { it.level == AlertLevel.Critical } -> LightbarState.Critical
    armed && stickOn -> LightbarState.ArmedStick
    armed -> LightbarState.Armed
    else -> LightbarState.Idle
}

/** The light bar colour of [state] in the theme [colors]. */
fun lightbarColor(state: LightbarState, colors: AdosColorRoles): Int = when (state) {
    LightbarState.Critical -> colors.statusError
    LightbarState.ArmedStick -> colors.statusSuccess
    LightbarState.Armed -> colors.statusWarning
    LightbarState.Idle -> colors.accentPrimary
}.toArgb()

/**
 * Controller light bar and rumble for the cockpit. The light bar follows the flight state
 * only while the cockpit is started and returns to the accent colour when it stops. New
 * warning and critical alerts rumble the controller in addition to the phone's own
 * vibration; a confirmed skill gives a short pulse. All of it is best effort.
 */
internal class ControllerFeedback(
    private val scope: CoroutineScope,
    private val controllers: ControllerManager,
    private val alerts: StateFlow<List<CockpitAlert>>,
    private val armed: StateFlow<Boolean>,
    private val stickOn: StateFlow<Boolean>,
) {
    private val palette = MutableStateFlow(AdosColors.BrandDark)
    private var job: Job? = null

    /** The current theme's colour roles, which the cockpit screen supplies. */
    fun setPalette(colors: AdosColorRoles) {
        palette.value = colors
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                val state = combine(alerts, armed, stickOn) { a, arm, on -> lightbarState(a, arm, on) }.distinctUntilChanged()
                // Re-applied when another controller becomes active.
                val device = controllers.active.map { it?.deviceId }.distinctUntilChanged()
                combine(state, palette, device) { s, p, _ -> lightbarColor(s, p) }.collect { controllers.setLightbar(it) }
            }
            launch {
                // Alerts already up when the cockpit starts were raised before; only new ones rumble.
                var known = alerts.value.mapTo(HashSet()) { it.id }
                alerts.collect { list ->
                    val fresh = list.filter { it.id !in known }
                    known = list.mapTo(HashSet()) { it.id }
                    when {
                        fresh.any { it.level == AlertLevel.Critical } -> controllers.rumble(Rumble.Critical)
                        fresh.any { it.level == AlertLevel.Warning } -> controllers.rumble(Rumble.Warning)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        controllers.setLightbar(palette.value.accentPrimary.toArgb())
    }

    /** A confirm sheet resolved confirmed. */
    fun onConfirmed() = controllers.rumble(Rumble.Confirm)
}
