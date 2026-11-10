package com.altnautica.gcs.feature.cockpit

/** The flight screen's overlays that can be open. */
enum class CockpitSheet {
    /** The status pill's alert list. */
    Status,
    Quick,
    Picker,
    Mode,
    Launch,

    /** The pre-flight checklist; as the launch gate it stands over the Launch sheet. */
    Preflight,

    /** A confirm opened from a control or from the sheet under it. */
    Confirm,
}

/**
 * One sheet at a time on the flight screen. Opening a peer sheet (alert list, quick
 * settings, skills, flight modes, launch) closes every other peer. The pre-flight gate
 * and a confirm stand over the sheet that opened them: only the topmost draws, and
 * closing it (Back, a tap outside, its own close) shows the one under it again.
 */
object SheetStack {
    private val PEERS = listOf(CockpitSheet.Status, CockpitSheet.Quick, CockpitSheet.Picker, CockpitSheet.Mode, CockpitSheet.Launch)

    /** The peers to close now that [now] is open, given what was open [before]. */
    fun toClose(before: Set<CockpitSheet>, now: Set<CockpitSheet>): Set<CockpitSheet> {
        val opened = PEERS.filter { it in now && it !in before }
        if (opened.isEmpty()) return emptySet()
        val keep = opened.last()
        return PEERS.filterTo(LinkedHashSet()) { it in now && it != keep }
    }

    /** The one overlay drawn: a confirm over the pre-flight gate over a peer sheet. */
    fun visible(open: Set<CockpitSheet>): CockpitSheet? = when {
        CockpitSheet.Confirm in open -> CockpitSheet.Confirm
        CockpitSheet.Preflight in open -> CockpitSheet.Preflight
        else -> PEERS.lastOrNull { it in open }
    }
}
