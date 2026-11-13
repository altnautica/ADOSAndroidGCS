package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The flight screen's one redraw clock. Everything there that changes on its own (the HUD's
 * telemetry, the data layers, the local minimap, the status-pill flash and the view model's
 * 2 Hz state) changes only on a beat, so whatever changes in one beat lands in one window
 * frame. Each paced at its own phase, they would redraw the whole window once each, and on a
 * device whose compositor is slow (an emulator's host-GPU pipe) the window's frames then take
 * the video's place.
 */
@Stable
class RedrawBeat(shown: Boolean = true) {
    private val all = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val beats = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val shown = MutableStateFlow(shown)

    /** The beat numbers, from 1, every [BEAT_MS]; only beats while [run] runs and the screen is shown. */
    val ticks: SharedFlow<Long> = beats.asSharedFlow()

    /**
     * Every beat while shown; while hidden, only every [HIDDEN_STEP_BEATS]th beat number, one
     * per [HIDDEN_STEP_BEATS] beats of time, so a slower clock read off it keeps its rate.
     */
    val cadence: SharedFlow<Long> = all.asSharedFlow()

    /** Whether the screen this beat redraws is shown; hidden, [ticks] stays silent. */
    fun setShown(shown: Boolean) {
        this.shown.value = shown
    }

    /** Beats until cancelled; the screen (or view model) that owns the beat runs it once. */
    suspend fun run(): Nothing {
        var n = 0L
        while (true) {
            if (shown.value) {
                delay(BEAT_MS)
                all.emit(++n)
                if (shown.value) beats.emit(n)
            } else if (withTimeoutOrNull(HIDDEN_STEP_BEATS * BEAT_MS) { shown.first { it } } == null) {
                n = (n / HIDDEN_STEP_BEATS + 1) * HIDDEN_STEP_BEATS
                all.emit(n)
            }
        }
    }

    companion object {
        /** 10 Hz: the HUD's redraw rate, and the fastest anything on the flight screen redraws. */
        const val BEAT_MS = 100L

        /** While hidden the beat steps 500 ms at a time, the slowest clock read off it. */
        const val HIDDEN_STEP_BEATS = 5L
    }
}

/** The flight screen's [RedrawBeat]; null outside it. */
val LocalRedrawBeat = staticCompositionLocalOf<RedrawBeat?> { null }

/** The flight screen's beat, or, outside the flight screen, one this composable runs while started. */
@Composable
fun rememberRedrawBeat(): RedrawBeat {
    val shared = LocalRedrawBeat.current
    if (shared != null) return shared
    val own = remember { RedrawBeat() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(own, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { own.run() } }
    return own
}

/**
 * [read] sampled on every beat, as state: a value that changes faster than the beat (a hold or
 * gate progress, a countdown) reaches the screen only on beats, in the same window frame as
 * everything else that changed. Read it in a draw lambda to redraw without recomposing.
 */
@Composable
fun <T> rememberOnBeat(read: () -> T): State<T> {
    val beat = rememberRedrawBeat()
    val latest = rememberUpdatedState(read)
    val state = remember { mutableStateOf(read()) }
    LaunchedEffect(beat) { beat.ticks.collect { state.value = latest.value() } }
    return state
}
