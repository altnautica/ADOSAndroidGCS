package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.pilot.ThumbPad

/** The two on-screen thumb pads, with the operator's stick mode and deadzone. */
@Composable
internal fun OnScreenSticksRow(vm: CockpitViewModel, modifier: Modifier) {
    val sticks = vm.onScreenSticks
    val conditioning by vm.conditioning.collectAsStateWithLifecycle()
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        // Mode 1 swaps pitch and throttle between the sticks, so the pads say so.
        val suffix = if (conditioning.mode == StickMode.Mode1) "_mode1" else ""
        ThumbPad(specText("cockpit.sticks.left_aria$suffix"), conditioning.deadzone, sticks::setLeft, sticks::touch)
        ThumbPad(specText("cockpit.sticks.right_aria$suffix"), conditioning.deadzone, sticks::setRight, sticks::touch)
    }
}
