package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.chrome.rememberOnBeat
import com.altnautica.gcs.feature.cockpit.flow.GateRing

/**
 * The centre-sticks gate ring and the controller hold ring. Each fills from a progress read on
 * the beat while drawing, so a filling ring redraws with the beat and recomposes only when it
 * appears, goes or changes action.
 */
@Composable
internal fun CentreRings(vm: CockpitViewModel, modifier: Modifier) {
    val flow by vm.flow.collectAsStateWithLifecycle()
    val hold by vm.controllerHold.collectAsStateWithLifecycle()
    Box(modifier) {
        if (flow.gatePending) {
            val progress = rememberOnBeat { vm.pilotState.value?.gate?.progress ?: 0f }
            GateRing(progress = { progress.value })
        }
        hold?.let { action ->
            val progress = rememberOnBeat { vm.controllerHoldProgress() }
            val colors = AdosTheme.colors
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(64.dp).testTag("hold-ring")) {
                    val stroke = Stroke(6.dp.toPx())
                    drawArc(colors.borderStrong, 0f, 360f, useCenter = false, style = stroke)
                    drawArc(colors.accentPrimary, -90f, 360f * progress.value, useCenter = false, style = stroke)
                }
                Text(vm.cockpitSpec.ds4.actions.firstOrNull { it.id == action }?.label ?: action, color = colors.hudInk)
            }
        }
    }
}
