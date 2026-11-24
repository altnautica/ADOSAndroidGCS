package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.spec.AlertGroupsSpec

/** The alert list's sections (`alert-groups.json` `order`). */
enum class AlertGroup(val wire: String) {
    Setup("setup"),
    FlightController("flight_controller"),
    Sensors("sensors"),
    Navigation("navigation"),
    Power("power"),
    Link("link"),
    System("system"),
    Other("other"),
    ;

    companion object {
        /** The group named [wire]; anything unknown is [Other]. */
        fun of(wire: String?): AlertGroup = entries.firstOrNull { it.wire == wire } ?: Other
    }
}

/** An alert in a section; [collapsed] marks a snoozed critical, which only folds. */
data class GroupedAlert(val alert: PillAlert, val collapsed: Boolean = false)

/** One section of the alert list; [hidden] counts the snoozed warnings left out of [alerts]. */
data class AlertSection(val group: AlertGroup, val title: String, val alerts: List<GroupedAlert>, val hidden: Int)

/**
 * The alert list's grouping, a port of `groupAlerts`/`alertGroup` in the shared web module
 * `hud/status-pill.ts` over the spec's `alert-groups.json`.
 */
class AlertGroups(private val spec: AlertGroupsSpec) {

    /** The section of an alert: a readiness item by its readiness id, else by the alert id, else by its source. */
    fun groupOf(id: String, source: PillSource): AlertGroup =
        if (id.startsWith(PILOT_PREFIX)) {
            AlertGroup.of(spec.readiness[id.removePrefix(PILOT_PREFIX)])
        } else {
            AlertGroup.of(spec.id[id] ?: spec.source[source.wire])
        }

    /** A section's title (`titles`); an untitled group reads as its wire name. */
    fun title(g: AlertGroup): String = spec.titles[g.wire] ?: g.wire

    /**
     * The sections for [alerts], in the spec's group order; inside one, critical before warning,
     * then as raised. One row per text in a section (the worst severity wins). A snoozed warning
     * is counted in `hidden`; a snoozed critical stays, collapsed. Empty sections are left out.
     */
    fun sections(alerts: List<PillAlert>, snoozed: Set<String>): List<AlertSection> {
        val byGroup = LinkedHashMap<AlertGroup, MutableList<PillAlert>>()
        for (a in alerts) {
            val g = byGroup.getOrPut(a.group) { mutableListOf() }
            val at = g.indexOfFirst { it.text == a.text }
            if (at >= 0) {
                if (a.severity == PillSeverity.Critical) g[at] = g[at].copy(severity = PillSeverity.Critical)
                continue
            }
            g += a
        }
        val out = mutableListOf<AlertSection>()
        for (name in spec.order) {
            val group = AlertGroup.of(name)
            val g = byGroup[group] ?: continue
            val shown = mutableListOf<GroupedAlert>()
            var hidden = 0
            for (a in g) {
                when {
                    a.key !in snoozed -> shown += GroupedAlert(a)
                    a.severity == PillSeverity.Critical -> shown += GroupedAlert(a, collapsed = true)
                    else -> hidden += 1
                }
            }
            // Stable: equal severities keep the order they were raised in.
            out += AlertSection(group, title(group), shown.sortedBy { if (it.alert.severity == PillSeverity.Critical) 0 else 1 }, hidden)
        }
        return out
    }

    companion object {
        private const val PILOT_PREFIX = "pilot:"

        /** The alerts the pill counts: a snoozed warning drops out, a snoozed critical stays (it only folds). */
        fun unsnoozed(alerts: List<PillAlert>, snoozed: Set<String>): List<PillAlert> =
            alerts.filter { it.severity == PillSeverity.Critical || it.key !in snoozed }
    }
}

/**
 * Alert snooze (`hud/alert-snooze.ts`): the alert keys ([PillAlert.key], `id|text`) the operator
 * hid. A key whose alert has cleared is pruned on the next update, so an alert that clears and
 * comes back shows again.
 */
object AlertSnooze {
    /** The snoozed keys still live; the same set when nothing was pruned, so a caller can skip a write. */
    fun prune(snoozed: Set<String>, liveKeys: Collection<String>): Set<String> {
        val live = liveKeys as? Set<String> ?: liveKeys.toHashSet()
        return if (snoozed.all { it in live }) snoozed else snoozed.filterTo(LinkedHashSet()) { it in live }
    }

    /** [snoozed] with [key] snoozed ([on]) or shown again. */
    fun with(snoozed: Set<String>, key: String, on: Boolean): Set<String> = if (on) snoozed + key else snoozed - key
}
