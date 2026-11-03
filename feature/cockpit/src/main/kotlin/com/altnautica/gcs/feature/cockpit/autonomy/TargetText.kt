package com.altnautica.gcs.feature.cockpit.autonomy

import android.content.res.Resources
import com.altnautica.gcs.feature.cockpit.R

/** Formats HUD v2 tags and nav-chip words from the cockpit's string resources. */
internal class TargetText(private val res: Resources) {
    private fun s(id: Int, vararg args: Any): String = res.getString(id, *args)

    private fun q(text: String, uncertain: Boolean) = if (uncertain) s(R.string.hud_target_uncertain, text) else text

    fun tag(tag: TargetTag): String = when (tag) {
        is TargetTag.Candidate -> s(R.string.hud_target_candidate, tag.cls, tag.pct)
        is TargetTag.Tracked -> q(s(R.string.hud_target_tracked, tag.cls, tag.id), tag.uncertain)
        is TargetTag.Lock -> q(s(R.string.hud_target_lock, tag.id, tag.pct), tag.uncertain)
        is TargetTag.Acquiring -> q(s(R.string.hud_target_acquiring, tag.id), tag.uncertain)
        is TargetTag.Track -> q(s(R.string.hud_target_track, tag.pct), tag.uncertain)
        is TargetTag.Hold -> tag.reason?.let { s(R.string.hud_target_hold, it) } ?: s(R.string.hud_target_hold_bare)
        is TargetTag.Coast -> s(R.string.hud_target_coast, HudGeometry.oneDecimal(tag.seconds))
        TargetTag.Searching -> s(R.string.hud_target_searching)
        is TargetTag.SearchingBearing -> s(R.string.hud_target_searching_bearing, HudGeometry.bearing3(tag.deg.toFloat()))
        TargetTag.Lost -> s(R.string.hud_target_lost)
        TargetTag.FollowStale -> s(R.string.hud_target_follow_stale)
        is TargetTag.Range -> s(R.string.hud_target_range, HudGeometry.formatNumber(tag.m))
        is TargetTag.Sigma -> s(R.string.hud_target_sigma, HudGeometry.oneDecimal(tag.twoSigmaM))
        is TargetTag.Estimate -> s(R.string.hud_target_est, HudGeometry.formatNumber(tag.m))
    }

    /** TalkBack text, e.g. "Person 12, tracking, 10.2 metres". */
    fun spoken(item: DrawItem, classLabel: String?, rangeM: Float?): String {
        val state = s(
            when (item.kind) {
                TargetKind.Candidate -> R.string.hud_a11y_candidate
                TargetKind.Tracked -> R.string.hud_a11y_tracked
                TargetKind.Locked -> R.string.hud_a11y_locked
                TargetKind.Acquiring -> R.string.hud_a11y_acquiring
                TargetKind.Following -> R.string.hud_a11y_following
                TargetKind.Holding -> R.string.hud_a11y_holding
                TargetKind.Coasting -> R.string.hud_a11y_coasting
                TargetKind.Searching, TargetKind.SearchingOffFrame -> R.string.hud_a11y_searching
                TargetKind.Lost -> R.string.hud_a11y_lost
            },
        )
        val who = classLabel.orEmpty().replaceFirstChar { it.uppercase() }
        val base = s(R.string.hud_a11y_target, who, item.trackId?.toString().orEmpty(), state).trim()
        return rangeM?.let { s(R.string.hud_a11y_target_range, base, HudGeometry.formatNumber(it)) } ?: base
    }

    fun navLabel(chip: NavChip): String = when (chip.label) {
        NavLabel.DeadReckoning -> s(R.string.hud_nav_dr)
        NavLabel.ExtNav -> s(R.string.hud_nav_ext)
        NavLabel.Flow -> s(R.string.hud_nav_flow)
        NavLabel.Stale -> s(R.string.hud_nav_stale)
        NavLabel.Gps -> s(R.string.hud_nav_gps, fixWord(chip.gpsFixType), chip.sats?.toString() ?: "—")
    }

    fun fixWord(fix: Int?): String = s(
        when (fix) {
            3 -> R.string.hud_nav_fix_3d
            4 -> R.string.hud_nav_fix_dgps
            5 -> R.string.hud_nav_fix_rtk_float
            6 -> R.string.hud_nav_fix_rtk_fixed
            else -> R.string.hud_nav_fix_none
        },
    )

    fun quality(q: NavQuality): String = s(
        when (q) {
            NavQuality.Good -> R.string.hud_nav_q_good
            NavQuality.Fair -> R.string.hud_nav_q_fair
            NavQuality.Poor -> R.string.hud_nav_q_poor
            NavQuality.Degraded -> R.string.hud_nav_q_degraded
            NavQuality.Lost -> R.string.hud_nav_q_lost
            NavQuality.NoFix -> R.string.hud_nav_q_no_fix
            NavQuality.Glitch -> R.string.hud_nav_q_glitch
            NavQuality.HoldOk -> R.string.hud_nav_q_hold_ok
            NavQuality.NoFlow -> R.string.hud_nav_q_no_flow
            NavQuality.Drift -> R.string.hud_nav_q_drift
        },
    )

    fun source(w: SourceWord): String = s(
        when (w) {
            SourceWord.None -> R.string.hud_nav_src_none
            SourceWord.Gps -> R.string.hud_nav_src_gps
            SourceWord.Beacon -> R.string.hud_nav_src_beacon
            SourceWord.OpticalFlow -> R.string.hud_nav_src_flow
            SourceWord.ExternalNav -> R.string.hud_nav_src_extnav
            SourceWord.WheelEncoder -> R.string.hud_nav_src_wheel
            SourceWord.Other -> R.string.hud_nav_src_other
            SourceWord.Unknown -> R.string.hud_nav_src_unknown
        },
    )
}
