package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every running video session, one per source (node + stream).
 *
 * Screens [acquire] a lease while they show a source and release it when they
 * leave. A session outlives its last lease by [IDLE_STOP_MS], so a rotation or
 * a cockpit→PiP swap reuses the live picture instead of re-dialling.
 */
@Singleton
class VideoSessionManager @Inject constructor(
    private val factories: Set<@JvmSuppressWildcards VideoSourceFactory>,
    @VideoScope private val scope: CoroutineScope,
) {
    private class Entry(val handle: VideoSessionHandle) {
        var leases = 0
        var stopJob: Job? = null
    }

    private val entries = HashMap<VideoSource, Entry>()

    /** A claim on a running session; [release] exactly once. */
    inner class Lease internal constructor(val handle: VideoSessionHandle) {
        private var released = false

        fun release() {
            synchronized(entries) {
                if (released) return
                released = true
                val entry = entries[handle.source] ?: return
                entry.leases--
                if (entry.leases == 0) {
                    entry.stopJob = scope.launch {
                        delay(IDLE_STOP_MS)
                        val stale = synchronized(entries) {
                            if (entry.leases == 0 && entries[handle.source] === entry) {
                                entries.remove(handle.source)
                                published.value = entries.mapValues { it.value.handle }
                                true
                            } else {
                                false
                            }
                        }
                        if (stale) entry.handle.stop()
                    }
                }
            }
        }
    }

    /** True when some registered factory can play [source]. */
    fun canPlay(source: VideoSource): Boolean = factories.any { it.supports(source) }

    /**
     * Starts (or reuses) the session for [source]. Returns null when no factory
     * supports it, which the caller shows as [VideoState.NoSource].
     */
    fun acquire(source: VideoSource): Lease? = synchronized(entries) {
        val entry = entries[source] ?: run {
            val factory = factories.firstOrNull { it.supports(source) } ?: return null
            Entry(factory.create(source)).also {
                entries[source] = it
                published.value = entries.mapValues { e -> e.value.handle }
            }
        }
        entry.stopJob?.cancel()
        entry.stopJob = null
        entry.leases++
        entry.handle.start()
        Lease(entry.handle)
    }

    /** The current session for [source] if one is running. */
    fun running(source: VideoSource): VideoSessionHandle? = synchronized(entries) { entries[source]?.handle }

    /**
     * State of [source] while something holds a lease on it; [VideoState.Idle]
     * when nothing does. Does not start a session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun state(source: VideoSource): Flow<VideoState> =
        published.map { it[source] }
            .distinctUntilChanged()
            .flatMapLatest { it?.state ?: flowOf(VideoState.Idle) }
            .distinctUntilChanged()

    private val published = MutableStateFlow<Map<VideoSource, VideoSessionHandle>>(emptyMap())

    companion object {
        const val IDLE_STOP_MS = 10_000L
    }
}
