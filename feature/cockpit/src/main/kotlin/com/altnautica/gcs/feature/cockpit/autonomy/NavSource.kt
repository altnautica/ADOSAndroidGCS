package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.model.PositionUnverified

/** What the nav-source chip labels the EKF's horizontal source as. */
enum class NavLabel { DeadReckoning, ExtNav, Gps, Flow, Stale }

/** Quality word on the chip. */
enum class NavQuality { Good, Fair, Poor, Degraded, Lost, NoFix, Glitch, HoldOk, NoFlow, Drift, FlowDisagrees, Corridor, Unverified }

/** Colour role of a quality word. */
enum class NavTone { Good, Warn, Crit, Muted }

/** Resolved nav-source chip content. [gpsFixWord] and [sats] fill the GPS label. */
data class NavChip(
    val label: NavLabel,
    val quality: NavQuality?,
    val tone: NavTone,
    val gpsFixType: Int? = null,
    val sats: Int? = null,
    val activeSet: Int = 1,
)

/** The status pill's view of this chip: its source kind and tone. */
fun NavChip.pillSource(): com.altnautica.gcs.core.skills.hud.PillNavSource = com.altnautica.gcs.core.skills.hud.PillNavSource(
    kind = when (label) {
        NavLabel.Gps -> com.altnautica.gcs.core.skills.hud.PillNavSource.Kind.Gps
        NavLabel.ExtNav -> com.altnautica.gcs.core.skills.hud.PillNavSource.Kind.Ext
        NavLabel.Flow -> com.altnautica.gcs.core.skills.hud.PillNavSource.Kind.Flow
        NavLabel.DeadReckoning -> com.altnautica.gcs.core.skills.hud.PillNavSource.Kind.Dr
        NavLabel.Stale -> com.altnautica.gcs.core.skills.hud.PillNavSource.Kind.Stale
    },
    tone = when (tone) {
        NavTone.Good -> com.altnautica.gcs.core.skills.hud.PillNavSource.Tone.Good
        NavTone.Warn -> com.altnautica.gcs.core.skills.hud.PillNavSource.Tone.Warn
        NavTone.Crit -> com.altnautica.gcs.core.skills.hud.PillNavSource.Tone.Crit
        NavTone.Muted -> com.altnautica.gcs.core.skills.hud.PillNavSource.Tone.Muted
    },
)

/** Inputs for [NavSource.resolve]. Fix type is MAVLink `GPS_FIX_TYPE` (0-6). */
data class NavInputs(
    val ekfFlags: Int?,
    val ekfAgeMs: Long?,
    val nav: NavState?,
    val navFresh: Boolean,
    val commandedSet: Int?,
    /** `EK3_SRC<n>_<FIELD>` values from the parameter cache, keyed by name. */
    val params: Map<String, Int>,
    val gpsFixType: Int?,
    val sats: Int?,
    val hdop: Float?,
    /** The pilot's `position_unverified`, or null while the pilot trusts the position or is unheard. */
    val positionUnverified: PositionUnverified? = null,
)

object NavSource {
    const val VEL_H = 2
    const val POS_H_REL = 8
    const val POS_H_ABS = 16
    const val CONST_POS = 128
    const val GPS_GLITCH = 1024
    const val EKF_STALE_MS = 2_000L

    /** Source-set field values. */
    const val SRC_NONE = 0
    const val SRC_GPS = 3
    const val SRC_FLOW = 5
    const val SRC_EXTNAV = 6

    fun param(set: Int, field: String) = "EK3_SRC${set}_$field"

    /** Active set: fresh Navigation, else the set this GCS last commanded, else 1. */
    fun activeSet(i: NavInputs): Int = i.nav?.activeSet?.takeIf { i.navFresh && it in 1..3 } ?: i.commandedSet ?: 1

    /**
     * HUD v2 §8.3, rows in order; the first match wins. The pilot's `position_unverified`
     * then turns any live row (not stale, not dead reckoning) crit with its reason.
     */
    fun resolve(i: NavInputs): NavChip {
        val row = sourceRow(i)
        val u = i.positionUnverified ?: return row
        if (row.label == NavLabel.Stale || row.label == NavLabel.DeadReckoning) return row
        return row.copy(quality = unverifiedWord(u.detail ?: u.reason), tone = NavTone.Crit)
    }

    /** The crit word for a position nobody can vouch for: the flow disagreeing, a corridor with no measured speed, or unverified. */
    private fun unverifiedWord(reason: String?): NavQuality = when (reason) {
        "flow_disagrees", "ekf_flow_disagree" -> NavQuality.FlowDisagrees
        "corridor_unmeasured" -> NavQuality.Corridor
        else -> NavQuality.Unverified
    }

    private fun sourceRow(i: NavInputs): NavChip {
        val set = activeSet(i)
        val flags = i.ekfFlags
        if (flags == null || i.ekfAgeMs == null || i.ekfAgeMs >= EKF_STALE_MS) return NavChip(NavLabel.Stale, null, NavTone.Muted, activeSet = set)
        fun has(bit: Int) = flags and bit != 0
        if (has(CONST_POS) || !(has(VEL_H) || has(POS_H_REL) || has(POS_H_ABS))) {
            return NavChip(NavLabel.DeadReckoning, NavQuality.Drift, NavTone.Crit, activeSet = set)
        }
        val posxy = i.params[param(set, "POSXY")]
        val velxy = i.params[param(set, "VELXY")]
        val nav = i.nav?.takeIf { i.navFresh }
        return when {
            posxy == SRC_EXTNAV -> extNav(nav, flags, set)
            posxy == SRC_GPS -> gps(i, flags, set)
            posxy == SRC_NONE && velxy == SRC_FLOW -> flow(flags, set)
            // Parameters unknown (or a source this chip has no row for): derive from the flags.
            has(POS_H_ABS) && (i.gpsFixType ?: 0) >= 3 -> gps(i, flags, set)
            nav?.mode == "external_nav" && nav.extnavHealth != null -> extNav(nav, flags, set)
            has(VEL_H) && !has(POS_H_ABS) && !has(POS_H_REL) -> flow(flags, set)
            else -> gps(i, flags, set)
        }
    }

    private fun extNav(nav: NavState?, flags: Int, set: Int): NavChip {
        // A reason from Navigation on a degraded or lost pose says why the position cannot be trusted.
        val why = nav?.healthReason
        if (why != null && (nav.extnavHealth == ExtNavHealth.Degraded || nav.extnavHealth == ExtNavHealth.Lost)) {
            return NavChip(NavLabel.ExtNav, unverifiedWord(why), NavTone.Crit, activeSet = set)
        }
        val (q, t) = when (nav?.extnavHealth) {
            ExtNavHealth.Good -> NavQuality.Good to NavTone.Good
            ExtNavHealth.Degraded -> NavQuality.Degraded to NavTone.Warn
            ExtNavHealth.Lost -> NavQuality.Lost to NavTone.Crit
            null -> if (flags and (POS_H_ABS or POS_H_REL) != 0) NavQuality.Good to NavTone.Good else NavQuality.NoFix to NavTone.Crit
        }
        return NavChip(NavLabel.ExtNav, q, t, activeSet = set)
    }

    private fun gps(i: NavInputs, flags: Int, set: Int): NavChip {
        val fix = i.gpsFixType ?: 0
        val (q, t) = when {
            fix < 3 -> NavQuality.NoFix to NavTone.Crit
            flags and GPS_GLITCH != 0 -> NavQuality.Glitch to NavTone.Warn
            i.hdop != null && i.hdop < 1.5f -> NavQuality.Good to NavTone.Good
            i.hdop != null && i.hdop < 2.5f -> NavQuality.Fair to NavTone.Warn
            else -> NavQuality.Poor to NavTone.Warn
        }
        return NavChip(NavLabel.Gps, q, t, gpsFixType = fix, sats = i.sats, activeSet = set)
    }

    private fun flow(flags: Int, set: Int): NavChip =
        if (flags and VEL_H != 0) NavChip(NavLabel.Flow, NavQuality.HoldOk, NavTone.Good, activeSet = set)
        else NavChip(NavLabel.Flow, NavQuality.NoFlow, NavTone.Crit, activeSet = set)

    /** Source-field value → word key for the popover table. */
    fun sourceWord(v: Int?): SourceWord = when (v) {
        0 -> SourceWord.None
        3 -> SourceWord.Gps
        4 -> SourceWord.Beacon
        5 -> SourceWord.OpticalFlow
        6 -> SourceWord.ExternalNav
        7 -> SourceWord.WheelEncoder
        null -> SourceWord.Unknown
        else -> SourceWord.Other
    }
}

enum class SourceWord { None, Gps, Beacon, OpticalFlow, ExternalNav, WheelEncoder, Other, Unknown }
