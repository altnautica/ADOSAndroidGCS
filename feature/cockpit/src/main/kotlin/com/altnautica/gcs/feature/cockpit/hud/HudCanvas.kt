package com.altnautica.gcs.feature.cockpit.hud

import com.altnautica.gcs.feature.cockpit.flow.specText
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
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.chrome.rememberRedrawBeat
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The instrument HUD as one canvas. Telemetry is read inside the draw pass
 * from the per-topic flows' current values; a new sample only marks the HUD dirty, and the
 * flight screen's [RedrawBeat] bumps a draw-phase counter when it is, so telemetry never
 * recomposes this composable and redraws the flight screen at most once a beat (10 Hz),
 * in the same window frame as everything else that changed in that beat, however many
 * topics update. Readouts are not interactive: input passes through to the video below.
 */
@Composable
fun HudCanvas(
    telemetry: VehicleTelemetry?,
    altitudeReference: AltitudeReference,
    density: CockpitDensity,
    /** False draws only the centre marks (reticle, heading and horizon marks); true adds the flight instruments. */
    instruments: Boolean = true,
    modifier: Modifier = Modifier,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
    recompositionProbe: (() -> Unit)? = null,
    drawProbe: (() -> Unit)? = null,
    invalidationProbe: (() -> Unit)? = null,
) {
    recompositionProbe?.let { probe -> SideEffect { probe() } }
    val frame = remember { mutableLongStateOf(0L) }
    val beat = rememberRedrawBeat()
    LaunchedEffect(telemetry, beat) {
        if (telemetry == null) return@LaunchedEffect
        var dirty = true
        launch {
            merge(telemetry.attitude, telemetry.position, telemetry.vfr, telemetry.wind, telemetry.home, telemetry.heartbeat)
                .collect { dirty = true }
        }
        beat.ticks.collect { n ->
            // Every few beats regardless, so instruments go stale on time when telemetry stops.
            if (!dirty && n % STALE_BEATS != 0L) return@collect
            dirty = false
            // A plain snapshot write: only the draw pass reads it, so no composition runs for it.
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
    val chrome = com.altnautica.gcs.feature.cockpit.chrome.LocalChrome.current
    val painter = remember(measurer, colors, small, medium, chrome) {
        HudPainter(measurer, colors, small, medium, plate = chrome.panelNoBlur, plateRadius = chrome.radiusSmall)
    }
    val summary = specText("cockpit.hud.aria")
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
                with(painter) { paint(telemetry, altitudeReference, density, nowMs(), instruments) }
            },
    )
}

/** Without new telemetry the HUD still redraws every 500 ms, so stale readouts dim on time. */
private const val STALE_BEATS = 500L / RedrawBeat.BEAT_MS

internal const val TEXT_CACHE_SIZE = 96
