package com.altnautica.gcs.feature.logs.tlog

import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.messages.GlobalPositionInt
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.SysStatus
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.map.GeoPoint
import java.io.InputStream

/** One chart sample; [tS] is seconds since the first record of the log. */
data class SeriesPoint(val tS: Float, val value: Float)


/** Time series extracted from one telemetry log for the flight detail charts and map track. */
data class FlightSeries(
    val altitudeRelM: List<SeriesPoint>,
    val groundspeedMs: List<SeriesPoint>,
    val batteryPct: List<SeriesPoint>,
    val track: List<GeoPoint>,
    val durationS: Float,
) {
    companion object {
        private const val MAV_TYPE_GCS = 6
        private const val MAV_AUTOPILOT_INVALID = 8

        /** Minimum spacing between kept samples, so long flights stay chartable. */
        private const val MIN_STEP_S = 0.2f

        /**
         * Reads a whole log. Samples come from the first vehicle (a component that is neither a
         * ground station nor an invalid autopilot); frames from any other system are ignored.
         */
        fun read(input: InputStream): FlightSeries {
            val alt = ArrayList<SeriesPoint>()
            val speed = ArrayList<SeriesPoint>()
            val battery = ArrayList<SeriesPoint>()
            val track = ArrayList<GeoPoint>()
            val parser = MavlinkFrameParser()
            var vehicle: Pair<Int, Int>? = null
            var firstUs = -1L
            var lastUs = 0L
            TlogReader(input).use { reader ->
                for (record in reader) {
                    if (firstUs < 0) firstUs = record.timeUs
                    lastUs = record.timeUs
                    val t = (record.timeUs - firstUs) / 1_000_000f
                    for (frame in parser.parse(record.frame)) {
                        val msg = frame.decode() ?: continue
                        if (vehicle == null && msg is Heartbeat &&
                            msg.type != MAV_TYPE_GCS && msg.autopilot != MAV_AUTOPILOT_INVALID
                        ) {
                            vehicle = frame.sysId to frame.compId
                        }
                        if (vehicle != frame.sysId to frame.compId) continue
                        when (msg) {
                            is GlobalPositionInt -> {
                                alt.addSpaced(t, msg.relativeAlt / 1000f)
                                if (msg.lat != 0 || msg.lon != 0) {
                                    val p = GeoPoint(msg.lat / 1e7, msg.lon / 1e7)
                                    if (track.lastOrNull() != p) track += p
                                }
                            }
                            is VfrHud -> speed.addSpaced(t, msg.groundspeed)
                            is SysStatus -> if (msg.batteryRemaining in 0..100) {
                                battery.addSpaced(t, msg.batteryRemaining.toFloat())
                            }
                            else -> Unit
                        }
                    }
                }
            }
            val duration = if (firstUs < 0) 0f else (lastUs - firstUs) / 1_000_000f
            return FlightSeries(alt, speed, battery, track, duration)
        }

        private fun ArrayList<SeriesPoint>.addSpaced(t: Float, v: Float) {
            val last = lastOrNull()
            if (last == null || t - last.tS >= MIN_STEP_S) add(SeriesPoint(t, v))
        }
    }
}
