package com.altnautica.gcs.core.skills.hud

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** The shared readout formatters (web module `hud/format.ts`), held to `readout-cases.json`. */
object ReadoutFormat {
    private const val NO_DATA = "—"

    /** One decimal with its sign ("1.2", "-0.4"); |v| < 0.05 reads "0.0", never "-0.0"; unknown reads a dash. */
    fun signed1(v: Double?): String {
        if (v == null || !v.isFinite()) return NO_DATA
        return if (abs(v) < 0.05) "0.0" else toFixed(v, 1)
    }

    /** Whole metres below 1 km ("120 m"), kilometres with two decimals from 1 km ("1.25 km"); unknown reads a dash. */
    fun distance(m: Double?): String {
        if (m == null || !m.isFinite()) return NO_DATA
        return if (abs(m) >= 1000) "${toFixed(m / 1000, 2)} km" else "${jsRound(m)} m"
    }

    /** Whole seconds left on a countdown: 1.2 s left reads 2, never below 0. */
    fun countdownS(remainingMs: Long): Long = maxOf(0L, ceil(remainingMs / 1000.0).toLong())

    /** What a bottom-row cell reads. */
    enum class Kind { Distance, Metres, Speed }

    /**
     * A bottom-row value as text and unit (web `bottom-row.ts` `readoutParts`): a distance in
     * whole metres, then kilometres from 1 km; every other value one decimal, never "-0.0". A
     * missing value reads a dash with the plain unit.
     */
    fun readoutParts(kind: Kind, v: Double?): Pair<String, String> {
        if (kind == Kind.Distance) {
            val s = distance(v)
            val i = s.lastIndexOf(' ')
            return if (i < 0) s to "m" else s.substring(0, i) to s.substring(i + 1)
        }
        return signed1(v) to if (kind == Kind.Speed) "m/s" else "m"
    }

    /** JavaScript `Math.round`: halves round up. */
    private fun jsRound(v: Double): Long = floor(v + 0.5).toLong()

    /** JavaScript `toFixed`: rounds the exact binary value half away from zero (0.35 reads "0.3"). */
    private fun toFixed(v: Double, digits: Int): String = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).toPlainString()
}
