package com.altnautica.gcs.feature.logs.detail

import com.altnautica.gcs.feature.logs.tlog.FlightSeries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decoded series of the last few finished flights, so reopening a flight does not decode
 * its telemetry log again. A flight still being recorded is never cached.
 */
@Singleton
class FlightSeriesCache @Inject constructor() {
    private val entries = object : LinkedHashMap<String, FlightSeries>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FlightSeries>?) = size > MAX_ENTRIES
    }

    fun get(key: String): FlightSeries? = synchronized(entries) { entries[key] }

    fun put(key: String, series: FlightSeries) {
        synchronized(entries) { entries[key] = series }
    }

    private companion object {
        const val MAX_ENTRIES = 8
    }
}
