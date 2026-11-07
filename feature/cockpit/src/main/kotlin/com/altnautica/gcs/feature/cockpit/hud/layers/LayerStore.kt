package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf

/** The last two frames of one layer kind with their local receive times. */
class LayerSlot<T : Any> {
    var cur: T? = null
        private set
    var prev: T? = null
        private set
    var curAt: Long = 0
        private set
    var prevAt: Long = 0
        private set
    private var curStamp: Long = Long.MIN_VALUE

    /** Local time the newest frame or its keep-alive arrived; staleness counts from here. */
    var seenAt: Long = 0
        private set

    /**
     * A new frame. A keep-alive (the agent re-sending an unchanged frame with its original
     * [stamp]) only refreshes [seenAt]: interpolation and fades carry on undisturbed.
     */
    fun push(frame: T, stamp: Long, atMs: Long) {
        seenAt = atMs
        if (cur != null && stamp == curStamp) return
        curStamp = stamp
        prev = cur
        prevAt = curAt
        cur = frame
        curAt = atMs
    }

    fun clear() {
        cur = null
        prev = null
        curAt = 0
        prevAt = 0
        seenAt = 0
        curStamp = Long.MIN_VALUE
    }

    /** The measured inter-frame interval, at least 30 ms; 100 ms before a second frame. */
    fun intervalMs(): Long = if (prev == null) DEFAULT_INTERVAL_MS else (curAt - prevAt).coerceAtLeast(MIN_INTERVAL_MS)

    /** Age of the newest frame (or keep-alive) at [now], or null without one. */
    fun ageMs(now: Long): Long? = cur?.let { now - seenAt }

    /**
     * Interpolation fraction at [now]: 0 shows [prev], 1 shows [cur]. A gap longer than
     * 250 ms (a dropped frame or a resumed stream) snaps to the new frame.
     */
    fun fraction(now: Long): Float {
        if (prev == null) return 1f
        val interval = intervalMs()
        if (interval > MAX_INTERVAL_MS) return 1f
        return ((now - curAt).toFloat() / interval).coerceIn(0f, 1f)
    }

    /** True while items still move between frames or fade in/out. */
    fun animating(now: Long): Boolean = cur != null && now - curAt < minOf(intervalMs(), MAX_INTERVAL_MS) + FADE_MS

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        const val MIN_INTERVAL_MS = 30L
        const val MAX_INTERVAL_MS = 250L
        const val FADE_MS = 120L
    }
}

/** Staleness of a layer: drawn, dimmed (older than 500 ms) or hidden (older than 2 s, or never seen). */
enum class Freshness { Fresh, Dim, Hidden }

/**
 * The overlay frames a surface draws. A plain mutable holder, never React-style UI state:
 * a new frame bumps [version], which only the draw phase reads, so a frame redraws the
 * canvas without recomposing anything.
 */
@Stable
class LayerStore {
    val ar = LayerSlot<ArFrame>()
    val obstacles = LayerSlot<ObstaclesFrame>()
    val depth = LayerSlot<DepthFrame>()
    val surfaces = LayerSlot<SurfacesFrame>()

    private val _version = mutableIntStateOf(0)

    /** Bumped on every new frame; read it inside a draw lambda only. */
    val version: Int get() = _version.intValue

    /** Frames received so far, by kind (for tests and the vision panel). */
    var received = 0L
        private set

    fun push(frame: LayerFrame, atMs: Long) {
        when (frame) {
            is LayerFrame.Ar -> ar.push(frame.frame, frame.frame.tsMs, atMs)
            is LayerFrame.Obstacles -> obstacles.push(frame.frame, frame.frame.tsMs, atMs)
            is LayerFrame.Depth -> depth.push(frame.frame, frame.frame.tsMs, atMs)
            is LayerFrame.Surfaces -> surfaces.push(frame.frame, frame.frame.tsMs, atMs)
        }
        received++
        _version.intValue++
    }

    fun clear() {
        ar.clear()
        obstacles.clear()
        depth.clear()
        surfaces.clear()
        _version.intValue++
    }

    fun animating(now: Long): Boolean = ar.animating(now) || obstacles.animating(now) || depth.animating(now) || surfaces.animating(now)

    companion object {
        const val DIM_MS = 500L
        const val HIDE_MS = 2_000L

        fun freshness(age: Long?): Freshness = when {
            age == null || age > HIDE_MS -> Freshness.Hidden
            age > DIM_MS -> Freshness.Dim
            else -> Freshness.Fresh
        }
    }
}
