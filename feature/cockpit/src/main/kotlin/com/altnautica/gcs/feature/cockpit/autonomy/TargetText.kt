package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/**
 * Formats HUD v2 tags and nav-chip words from the cockpit spec's strings. The spec is
 * sentence case; the HUD draws its tags and nav words in capitals.
 */
internal class TargetText(private val strings: SetupStringsSpec) {
    private fun s(key: String, vararg args: Pair<String, Any>): String =
        strings.format(key, args.associate { (k, v) -> k to v.toString() })

    /** The spec text drawn in capitals; placeholders and unit words (s, m, Hz) keep their case. */
    private fun up(key: String, vararg args: Pair<String, Any>): String {
        val template = strings.strings[key] ?: return key
        var text = WORD.replace(template) { m -> if (m.value in UNITS || m.value.startsWith('{')) m.value else m.value.uppercase() }
        for ((name, value) in args) text = text.replace("{$name}", value.toString())
        return text
    }

    private fun q(text: String, uncertain: Boolean) = if (uncertain) up("hud.target.uncertain", "v" to text) else text

    fun tag(tag: TargetTag): String = when (tag) {
        is TargetTag.Candidate -> up("hud.target.candidate", "label" to tag.cls, "pct" to tag.pct)
        is TargetTag.Tracked -> q(up("hud.target.tracked", "label" to tag.cls, "id" to tag.id), tag.uncertain)
        is TargetTag.Lock -> q(up("hud.target.lock", "id" to tag.id, "pct" to tag.pct), tag.uncertain)
        is TargetTag.Acquiring -> q(up("hud.target.acquiring", "id" to tag.id), tag.uncertain)
        is TargetTag.Track -> q(up("hud.target.track", "pct" to tag.pct), tag.uncertain)
        is TargetTag.Hold -> tag.reason?.let { up("hud.target.hold", "label" to it) } ?: up("follow.phase.holding")
        is TargetTag.Coast -> up("hud.target.coast", "s" to HudGeometry.oneDecimal(tag.seconds))
        TargetTag.Searching -> up("follow.phase.searching")
        is TargetTag.SearchingBearing -> up("hud.target.searching_bearing", "deg" to HudGeometry.bearing3(tag.deg.toFloat()))
        TargetTag.Lost -> up("follow.phase.lost")
        TargetTag.FollowStale -> up("hud.target.follow_stale")
        is TargetTag.Range -> s("hud.target.range", "m" to HudGeometry.formatNumber(tag.m))
        is TargetTag.Sigma -> s("hud.target.sigma", "m" to HudGeometry.oneDecimal(tag.twoSigmaM))
        is TargetTag.Estimate -> up("hud.target.est", "m" to HudGeometry.formatNumber(tag.m))
    }

    /** TalkBack text, e.g. "Person tracking, 12, 10.2 metres". */
    fun spoken(item: DrawItem, classLabel: String?, rangeM: Float?): String {
        val state = s(
            when (item.kind) {
                TargetKind.Candidate -> "target.candidate"
                TargetKind.Tracked -> "hud.target.state.tracked"
                TargetKind.Locked -> "hud.target.state.locked"
                TargetKind.Acquiring -> "follow.phase.acquiring"
                TargetKind.Following -> "hud.target.state.tracking"
                TargetKind.Holding -> "hud.target.state.holding"
                TargetKind.Coasting -> "follow.phase.coasting"
                TargetKind.Searching, TargetKind.SearchingOffFrame -> "follow.phase.searching"
                TargetKind.Lost -> "follow.phase.lost"
            },
        ).lowercase()
        val who = classLabel.orEmpty().replaceFirstChar { it.uppercase() }
        val base = s("hud.target.aria", "label" to who, "state" to state, "track" to item.trackId?.toString().orEmpty())
            .trim().removeSuffix(",").trim()
        return rangeM?.let { s("hud.target.aria_range", "target" to base, "m" to HudGeometry.formatNumber(it)) } ?: base
    }

    fun navLabel(chip: NavChip): String = when (chip.label) {
        NavLabel.DeadReckoning -> up("nav_source.dr")
        NavLabel.ExtNav -> up("nav_source.ext_nav")
        NavLabel.Flow -> up("nav_source.flow")
        NavLabel.Stale -> up("nav_source.stale")
        NavLabel.Gps -> up("nav_source.chip_gps", "fix" to fixWord(chip.gpsFixType), "sats" to (chip.sats?.toString() ?: "—"))
    }

    fun fixWord(fix: Int?): String = up(
        when (fix) {
            3 -> "nav_source.fix.3d"
            4 -> "nav_source.fix.dgps"
            5 -> "nav_source.fix.rtk_float"
            6 -> "nav_source.fix.rtk_fixed"
            else -> "nav_source.q.no_fix"
        },
    )

    fun quality(q: NavQuality): String = up(
        when (q) {
            NavQuality.Good -> "nav_source.q.good"
            NavQuality.Fair -> "nav_source.q.fair"
            NavQuality.Poor -> "nav_source.q.poor"
            NavQuality.Degraded -> "nav_source.q.degraded"
            NavQuality.Lost -> "nav_source.q.lost"
            NavQuality.NoFix -> "nav_source.q.no_fix"
            NavQuality.Glitch -> "nav_source.q.glitch"
            NavQuality.HoldOk -> "nav_source.q.hold_ok"
            NavQuality.NoFlow -> "nav_source.q.no_flow"
            NavQuality.Drift -> "nav_source.q.drift"
        },
    )

    fun source(w: SourceWord): String = when (w) {
        SourceWord.None -> s("controls.action.none")
        SourceWord.Gps -> s("nav_source.gps")
        SourceWord.Beacon -> s("nav_source.src.beacon")
        SourceWord.OpticalFlow -> s("nav_source.src.flow")
        SourceWord.ExternalNav -> s("nav_source.src.extnav")
        SourceWord.WheelEncoder -> s("nav_source.src.wheel")
        SourceWord.Other -> s("nav_source.src.other")
        SourceWord.Unknown -> "—"
    }
}

private val WORD = Regex("""\{[^}]*\}|[^{\s]+""")

private val UNITS = setOf("s", "m", "Hz", "fps", "%")
