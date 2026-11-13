package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The flight screen's one redraw clock. Everything there that changes on its own (the HUD's
 * telemetry, the data layers, the local minimap, the status-pill flash and the view model's
 * 2 Hz state) changes only on a beat, so whatever changes in one beat lands in one window
 * frame. Each paced at its own phase, they would redraw the whole window once each, and on a
 * device whose compositor is slow (an emulator's host-GPU pipe) the window's frames then take
 * the video's place.
 */
@Stable
class RedrawBeat {
    private val beats = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The beat numbers, from 1, every [BEAT_MS]; only beats while [run] runs. */
    val ticks: SharedFlow<Long> = beats.asSharedFlow()

    /** Beats until cancelled; the screen (or view model) that owns the beat runs it once. */
    suspend fun run(): Nothing {
        var n = 0L
        while (true) {
            delay(BEAT_MS)
            beats.emit(++n)
        }
    }

    companion object {
        /** 10 Hz: the HUD's redraw rate, and the fastest anything on the flight screen redraws. */
        const val BEAT_MS = 100L
    }
}

/** The flight screen's [RedrawBeat]; null outside it. */
val LocalRedrawBeat = staticCompositionLocalOf<RedrawBeat?> { null }

/** The flight screen's beat, or, outside the flight screen, one this composable runs itself. */
@Composable
fun rememberRedrawBeat(): RedrawBeat {
    val shared = LocalRedrawBeat.current
    if (shared != null) return shared
    val own = remember { RedrawBeat() }
    LaunchedEffect(own) { own.run() }
    return own
}
