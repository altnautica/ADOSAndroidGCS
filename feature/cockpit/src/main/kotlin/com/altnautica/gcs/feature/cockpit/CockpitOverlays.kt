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

    /** The camera-settings sheet (camera and stream, picture-in-picture, recording). */
    val camera = MutableStateFlow(false)

    /** The status pill's alert list. */
    val statusList = MutableStateFlow(false)

    /** The controller tour card on screen (0-based), or null. It opens only on the ground. */
    val tour = MutableStateFlow<Int?>(null)

    /** Called when Back closes the tour, so it is marked seen like its Done button. */
    var onTourClosed: () -> Unit = {}

    /**
     * True while an overlay that navigates like the rest of the app is open (Mode sheet,
     * launch, preflight, quick settings, camera settings, the controller tour) and no confirm sheet is:
     * controller buttons then move focus and activate controls instead of firing cockpit
     * actions. The skill picker is driven by the controller bindings (D-pad focus, Cross
     * GO, Circle Stop), so it is not one of them.
     */
    val uiNavigation: Boolean
        get() = !confirm.isOpen && (quickSettings.value || camera.value || preflight.value || modePicker.value || launch.value || tour.value != null)

    private fun flag(sheet: CockpitSheet): MutableStateFlow<Boolean>? = when (sheet) {
        CockpitSheet.Status -> statusList
        CockpitSheet.Quick -> quickSettings
        CockpitSheet.Picker -> picker
        CockpitSheet.Mode -> modePicker
        CockpitSheet.Launch -> launch
        CockpitSheet.Camera -> camera
        CockpitSheet.Preflight -> preflight
        CockpitSheet.Confirm -> null
    }

    /** The sheets open now; [SheetStack.visible] picks the one drawn. */
    fun open(): Set<CockpitSheet> = CockpitSheet.entries.filterTo(LinkedHashSet()) { sheet ->
        if (sheet == CockpitSheet.Confirm) confirm.isOpen else flag(sheet)?.value == true
    }

    /**
     * Closes the overlay drawn on top ([SheetStack.visible]: a confirm, then the pre-flight
     * gate, then the open peer sheet), else the tour. False when none was open.
     */
    fun closeTop(): Boolean {
        val top = SheetStack.visible(open())
        when {
            top == CockpitSheet.Confirm -> confirm.cancel()
            top != null -> flag(top)?.value = false
            tour.value != null -> {
                tour.value = null
                onTourClosed()
            }
            else -> return false
        }
        return true
    }
}
