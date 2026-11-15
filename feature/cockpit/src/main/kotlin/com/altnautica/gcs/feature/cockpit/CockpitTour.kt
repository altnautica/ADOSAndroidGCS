package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The first-connect controller tour: three cards, shown once, on the ground only. */
internal class CockpitTour(
    private val scope: CoroutineScope,
    router: AdosInputRouter,
    private val controls: ControlsRepository,
    tick500: Flow<Long>,
    private val overlays: CockpitOverlays,
    airborne: () -> Boolean,
) {
    init {
        // The first pad connected anywhere opens the three-card tour once, here as in Settings,
        // but only on the ground: in the air it waits for the landing, and a takeoff puts an
        // open tour away unseen, so the pad never leaves flight control for it.
        overlays.onTourClosed = ::finishTour
        scope.launch {
            combine(router.gamepadConnected, controls.tourSeen, tick500) { pad, seen, _ -> (pad && !seen) to airborne() }
                .distinctUntilChanged()
                .collect { (show, air) ->
                    when {
                        air -> overlays.tour.value = null
                        show && overlays.tour.value == null -> overlays.tour.value = 0
                    }
                }
        }
    }

    /** Next card; past the last one the tour is marked seen. */
    fun nextTourStep() {
        val step = overlays.tour.value ?: return
        if (step + 1 < CockpitViewModel.TOUR_STEPS) overlays.tour.value = step + 1 else finishTour()
    }

    fun finishTour() {
        overlays.tour.value = null
        scope.launch { controls.setTourSeen(true) }
    }
}
