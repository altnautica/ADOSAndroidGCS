package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import kotlinx.coroutines.flow.MutableStateFlow

/** Which cockpit overlays are open, and the order Back closes them in. */
internal class CockpitOverlays(private val confirm: ConfirmController) {
    val quickSettings = MutableStateFlow(false)
    val drawer = MutableStateFlow(false)
    val preflight = MutableStateFlow(false)
    val modePicker = MutableStateFlow(false)

    /**
     * True while an overlay that navigates like the rest of the app is open (mode picker,
     * skill drawer, preflight, quick settings) and no confirm sheet is: controller buttons
     * then move focus and activate controls instead of firing cockpit actions.
     */
    val uiNavigation: Boolean
        get() = !confirm.isOpen && (quickSettings.value || drawer.value || preflight.value || modePicker.value)

    /** Closes the top overlay: confirm sheet, mode picker, skill drawer, preflight, quick settings. False when none was open. */
    fun closeTop(): Boolean {
        val top = listOf(modePicker, drawer, preflight, quickSettings).firstOrNull { it.value }
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
