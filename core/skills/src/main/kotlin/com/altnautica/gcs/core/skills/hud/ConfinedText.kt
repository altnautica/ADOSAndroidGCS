package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.SpeedCap
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToLong

/** How a route-check report reads: fine, allowed but slowed, or refused. */
enum class RouteCheckSeverity { Ok, Warn, Critical }

/**
 * The route-check report as every surface words it: the [title], the state [line]
 * (`route_check.<state>`) and where the tightest checked point is ([pinchAt], null while no
 * sample was observed).
 */
data class RouteCheckReport(
    val state: RouteCheckState,
    val severity: RouteCheckSeverity,
    val title: String,
    val line: String,
    val pinchAt: String?,
)

/**
 * The confined-place profile's text, a port of the shared web module `hud/route-check.ts`: the
 * "Confined" chip, the route-check report, the speed-cap line by its reason and the place-class
 * picker's labels and explain line. Text from the spec's string table only.
 */
class ConfinedText(private val text: SpecText) {

    /** The "Confined" chip text while the place class is confined, else null. */
    fun chip(p: PilotState?): String? = text.text("chip.confined").takeIf { p?.placeClass == PlaceClass.Confined }

    /**
     * The route-check report, or null with no route check. The infeasible line names the pinch
     * leg (`3→4`) with its clearance and the clearance it needs, two decimals each; the pinch
     * position reads one decimal, its height up (the negated down component). An unverified
     * check on a mission feed not in step with the FC says so first (`route_check.stale`), then
     * names the waypoints it could not place (`route_check.unchecked`), then a mission too long
     * to hold (`route_check.truncated`), before the generic "not seen yet".
     */
    fun report(rc: RouteCheck?): RouteCheckReport? {
        if (rc == null) return null
        val pinch = rc.pinch
        val args = if (rc.state == RouteCheckState.Infeasible) {
            mapOf(
                "leg" to (pinch?.let { "${it.fromSeq}→${it.toSeq}" } ?: NO_DATA),
                "clearance_m" to metres2(pinch?.clearanceM),
                "required_m" to metres2(pinch?.requiredM ?: rc.requiredM),
            )
        } else {
            mapOf("required_m" to metres2(rc.requiredM), "items" to rc.unchecked.joinToString(", "))
        }
        val key = when {
            rc.state != RouteCheckState.Unverified -> rc.state.wire
            rc.stale -> "stale"
            rc.unchecked.isNotEmpty() -> "unchecked"
            rc.truncated -> "truncated"
            else -> rc.state.wire
        }
        return RouteCheckReport(
            state = rc.state,
            severity = when (rc.state) {
                RouteCheckState.Feasible -> RouteCheckSeverity.Ok
                RouteCheckState.Unverified -> RouteCheckSeverity.Warn
                RouteCheckState.Infeasible -> RouteCheckSeverity.Critical
            },
            title = text.text("route_check.title"),
            line = text.text("route_check.$key", args),
            pinchAt = pinch?.let {
                text.text("route_check.pinch_at", mapOf("n" to metres1(it.ned[0]), "e" to metres1(it.ned[1]), "h" to metres1(-it.ned[2])))
            },
        )
    }

    /** The speed-cap line by its reason (`pilot.speed_cap.<reason>`, dashes as underscores), or null with no cap or a reason the spec does not name. */
    fun speedCapLine(cap: SpeedCap?): String? {
        if (cap == null) return null
        val key = "pilot.speed_cap.${cap.reason.replace('-', '_')}"
        return if (text.has(key)) text.text(key, mapOf("v" to speedCapText(cap.vHMps.toDouble()))) else null
    }

    /** The picker label of a place class. */
    fun label(c: PlaceClass): String = text.text("flow.launch.place_class.${c.wire}")

    /** The picker's explain line for [c] with its effective margin (at most two decimals); an unknown margin reads "—". */
    fun explain(c: PlaceClass, marginM: Double?): String =
        text.text("flow.launch.place_class.explain_${c.wire}", mapOf("margin_m" to (marginM?.let(::trimmed2) ?: NO_DATA)))

    companion object {
        private const val NO_DATA = "—"

        /** The pilot's default `pilot.obstacle_margin_m`. */
        private const val DEFAULT_MARGIN_M = 1.5

        /** The margin floor in an open place. */
        private const val OPEN_FLOOR_M = 0.5

        /** A confined place keeps the prop-tip radius plus this. */
        private const val CONFINED_EXTRA_M = 0.1

        /**
         * The obstacle margin the pilot flies with: in an open place the configured margin
         * ([configuredM], null for the default) raised to the open floor; in a confined place the
         * prop-tip radius plus 0.1 m, whatever the configured open margin. Null for a confined
         * place without a prop-tip radius, which the pilot refuses to launch.
         */
        fun effectiveMarginM(c: PlaceClass, configuredM: Double?, propTipRadiusM: Double?): Double? = when (c) {
            PlaceClass.Open -> maxOf(configuredM ?: DEFAULT_MARGIN_M, OPEN_FLOOR_M)
            PlaceClass.Confined -> propTipRadiusM?.let { it + CONFINED_EXTRA_M }
        }

        /** The cap in m/s with at most one decimal (1.5 → "1.5", 2 → "2"), as every surface prints it. */
        fun speedCapText(v: Double): String {
            val r = Math.round(v * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }

        /** Two decimals ("0.58"), halves up as the web's `Math.round`; "—" when unknown. */
        internal fun metres2(v: Double?): String {
            if (v == null) return NO_DATA
            return BigDecimal((v * 100.0).roundToLong()).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        }

        /** A position component: one decimal, halves away from zero, never "-0.0". */
        internal fun metres1(v: Double): String {
            val r = Math.round(abs(v) * 10) * (if (v < 0) -1 else 1)
            return if (r == 0L) "0.0" else BigDecimal(r).movePointLeft(1).setScale(1, RoundingMode.UNNECESSARY).toPlainString()
        }

        /** At most two decimals, trailing zeros dropped (0.477 → "0.48", 1.5 → "1.5", 2 → "2"). */
        private fun trimmed2(v: Double): String =
            BigDecimal(Math.round(v * 100)).movePointLeft(2).stripTrailingZeros().toPlainString()
    }
}
