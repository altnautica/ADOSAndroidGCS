package com.altnautica.gcs.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A DataStore-backed value kept hot in memory so a synchronous caller — an
 * OkHttp `Interceptor`, which runs once per request and has no coroutine scope
 * — can read the current value without a disk round trip.
 *
 * Two things this deliberately is not:
 *
 * - **Not a one-shot snapshot.** The value is re-read from the backing flow
 *   every time it changes, so a setting the operator edits takes effect on the
 *   next request rather than at the next process start.
 * - **Not a blocking read on the hot path.** [current] returns the cached
 *   value. The single bounded exception is the window between construction and
 *   the collector's first emission: there [current] primes once from disk,
 *   because a default returned in that window would send the first request of
 *   the session to the wrong host, or without a credential.
 *
 * Observe the value in a ViewModel from the source flow directly; this type
 * exists only to serve the synchronous reader.
 */
class CachedPreference<T>(
    private val source: Flow<T>,
    scope: CoroutineScope,
) {

    /**
     * Wrapper so "not primed yet" is distinguishable from a legitimately null
     * value — the stored pairing key is absent until the device is paired, and
     * treating absent as unprimed would re-read the store on every request.
     */
    private class Box<V>(val value: V)

    private val cached = MutableStateFlow<Box<T>?>(null)

    init {
        scope.launch {
            source.collect { cached.value = Box(it) }
        }
    }

    /**
     * The current value, priming from the backing store at most once if the
     * collector has not emitted yet. Never call from the main thread.
     */
    fun current(): T {
        cached.value?.let { return it.value }
        val primed = runBlocking { source.first() }
        cached.value = Box(primed)
        return primed
    }
}
