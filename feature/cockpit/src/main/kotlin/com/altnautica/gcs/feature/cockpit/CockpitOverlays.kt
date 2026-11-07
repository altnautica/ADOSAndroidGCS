package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import kotlinx.coroutines.flow.MutableStateFlow

/** Which cockpit overlays are open, and the order Back closes them in. */
internal class CockpitOverlays(private val confirm: ConfirmController) {
    val quickSettings = MutableStateFlow(false)
    val picker = MutableStateFlow(false)
    val preflight = MutableStateFlow(false)
    val modePicker = MutableStateFlow(false)
    val launch = MutableStateFlow(false)

    /**
     * True while an overlay that navigates like the rest of the app is open (Mode sheet,
     * launch, preflight, quick settings) and no confirm sheet is: controller buttons then
     * move focus and activate controls instead of firing cockpit actions. The skill picker
     * is driven by the controller bindings (D-pad focus, Cross GO, Circle Stop), so it is
     * not one of them.
     */
    val uiNavigation: Boolean
        get() = !confirm.isOpen && (quickSettings.value || preflight.value || modePicker.value || launch.value)

    /** Closes the top overlay: confirm sheet, Mode sheet, launch, skill picker, preflight, quick settings. False when none was open. */
    fun closeTop(): Boolean {
        val top = listOf(modePicker, launch, picker, preflight, quickSettings).firstOrNull { it.value }
        return when {
            confirm.isOpen -> {
                confirm.cancel()
                true
            }
            top != null -> {
                top.value = false
                true
            }
            else -> false
        }
    }
}
