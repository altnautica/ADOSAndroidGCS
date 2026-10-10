package com.altnautica.gcs.feature.cockpit.hud

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.settings.AltitudeReference
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * The instrument HUD (CS-3) as one canvas. Telemetry is read inside the draw pass
 * from the per-topic flows' current values; a new sample only bumps a draw-phase
 * counter (at most once per frame), so telemetry never recomposes this composable.
 * Readouts are not interactive: input passes through to the video below.
 */
@Composable
fun HudCanvas(
    telemetry: VehicleTelemetry?,
    altitudeReference: AltitudeReference,
    density: CockpitDensity,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    recompositionProbe: (() -> Unit)? = null,
    drawProbe: (() -> Unit)? = null,
    invalidationProbe: (() -> Unit)? = null,
) {
    recompositionProbe?.let { probe -> SideEffect { probe() } }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(telemetry) {
        if (telemetry == null) return@LaunchedEffect
        val staleTicks: Flow<Any?> = flow {
            while (true) {
                emit(Unit)
                delay(STALE_REDRAW_MS)
            }
        }
        merge(
            telemetry.attitude, telemetry.position, telemetry.vfr, telemetry.wind,
            telemetry.home, telemetry.heartbeat, staleTicks,
        ).conflate().collect {
            // A plain snapshot write: the draw pass that reads it is invalidated once per
            // frame however many samples land before the vsync, and no composition frame
            // (frame-clock awaiter, recomposer pass) runs for a telemetry tick.
            frame.longValue++
            invalidationProbe?.invoke()
        }
    }
    val colors = AdosTheme.colors
    // One frame lays out about thirty labels (ladder, heading tape, both tapes); the default
    // eight-entry cache would evict them all every frame and re-run text layout each time.
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)
    val small = AdosTypography.numeric.small
    val medium = AdosTypography.numeric.medium
    val painter = remember(measurer, colors, small, medium) { HudPainter(measurer, colors, small, medium) }
    val summary = stringResource(R.string.cockpit_hud_description)
    Spacer(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = summary }
            // Its own render node: a telemetry redraw re-records only the HUD, not the cockpit around it.
            .graphicsLayer()
            .drawBehind {
                // Reading the counter subscribes the draw pass, not composition.
                frame.longValue
                drawProbe?.invoke()
                with(painter) { paint(telemetry, altitudeReference, density, nowMs()) }
            },
    )
}

private const val STALE_REDRAW_MS = 500L

internal const val TEXT_CACHE_SIZE = 96
