package com.altnautica.gcs.feature.cockpit.pilot

import android.os.Handler
import android.os.Looper

/** A periodic tick on one thread; [start] replaces any running tick. */
internal interface Ticker {
    fun start(tick: () -> Unit)

    fun stop()
}

/**
 * Ticks every [periodMs] from the main looper. A main thread that hangs (an ANR) stops the
 * ticks, which is the point: whatever it sends proves the operator's controls still run.
 */
internal class MainLooperTicker(private val periodMs: Long) : Ticker {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var tick: (() -> Unit)? = null

    private val runnable = object : Runnable {
        override fun run() {
            val t = tick ?: return
            t()
            handler.postDelayed(this, periodMs)
        }
    }

    override fun start(tick: () -> Unit) {
        this.tick = tick
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, periodMs)
    }

    override fun stop() {
        tick = null
        handler.removeCallbacks(runnable)
    }
}
