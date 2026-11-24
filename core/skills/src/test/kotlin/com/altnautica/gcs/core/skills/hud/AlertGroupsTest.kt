package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The alert list's sections and the snooze, as `hud/status-pill.ts` and `hud/alert-snooze.ts`. */
class AlertGroupsTest {
    private val groups = AlertGroups(testSpec.alertGroups)
    private val pill = StatusPill(SpecText(testSpec.setupStrings), 85, 95, groups)
    private val fcProfile = ReadinessItem(
        "fc_profile", false, ReadinessSeverity.Block, "FC parameters differ",
        listOf(ParamDiff("MAV_GCS_SYSID", 255.0, "1")), ReadinessFix.ApplyProfile,
    )
    private val fcTexts = listOf(PillFcStatus(4, "EKF variance"), PillFcStatus(4, "Compass interference"))

    private fun alerts(readiness: List<ReadinessItem> = emptyList(), fc: List<PillFcStatus> = emptyList()) =
        pill.alerts(emptyList(), readiness, null, null, fc)

    @Test
    fun `the spec orders every group and titles each`() {
        assertEquals(AlertGroup.entries.map { it.wire }.sorted(), testSpec.alertGroups.order.sorted())
        for (g in AlertGroup.entries) assertEquals(true, testSpec.alertGroups.titles.containsKey(g.wire))
    }

    @Test
    fun `maps readiness ids, alert ids and sources, and unmapped ids to other`() {
        assertEquals(AlertGroup.Setup, groups.groupOf("pilot:fc_profile", PillSource.PilotReadiness))
        assertEquals(AlertGroup.Sensors, groups.groupOf("pilot:flow", PillSource.PilotReadiness))
        assertEquals(AlertGroup.Other, groups.groupOf("pilot:something_new", PillSource.PilotReadiness))
        assertEquals(AlertGroup.FlightController, groups.groupOf("fcst:EKF variance", PillSource.FcStatusText))
        assertEquals(AlertGroup.Power, groups.groupOf("alert:batt_low", PillSource.Battery))
        assertEquals(AlertGroup.Navigation, groups.groupOf("alert:gps_no_3d", PillSource.NavSource))
        assertEquals(AlertGroup.System, groups.groupOf("storage", PillSource.Storage))
        assertEquals(AlertGroup.Setup, groups.groupOf("setup_abort", PillSource.Pilot))
    }

    @Test
    fun `an fc_profile critical and two FC texts group as setup, then flight controller`() {
        val sections = groups.sections(alerts(listOf(fcProfile), fcTexts), emptySet())
        assertEquals(
            listOf(
                listOf(AlertGroup.Setup, "Flight controller setup", 1, 0),
                listOf(AlertGroup.FlightController, "Flight controller messages", 2, 0),
            ),
            sections.map { listOf(it.group, it.title, it.alerts.size, it.hidden) },
        )
        val row = sections[0].alerts[0].alert
        assertEquals(fcProfile.params, row.params)
        assertEquals(ReadinessFix.ApplyProfile, row.fix)
        assertEquals("pilot:fc_profile|FC parameters differ", row.key)
    }

    @Test
    fun `orders critical before warning inside a group, then as raised`() {
        val a = alerts(fc = listOf(PillFcStatus(4, "A"), PillFcStatus(2, "B"), PillFcStatus(4, "C")))
        assertEquals(listOf("B", "A", "C"), groups.sections(a, emptySet())[0].alerts.map { it.alert.text })
    }

    @Test
    fun `folds identical texts in a group into one row at the worst severity`() {
        val a = listOf(
            PillAlert("x", PillSource.FcStatusText, PillSeverity.Warning, "Same", AlertGroup.FlightController),
            PillAlert("y", PillSource.FcPrearm, PillSeverity.Critical, "Same", AlertGroup.FlightController),
        )
        assertEquals(listOf("x" to PillSeverity.Critical), groups.sections(a, emptySet())[0].alerts.map { it.alert.id to it.alert.severity })
    }

    @Test
    fun `a snoozed warning moves to hidden, a snoozed critical stays collapsed`() {
        val a = alerts(listOf(fcProfile), fcTexts)
        val warn = a.first { it.text == "EKF variance" }
        val crit = a.first { it.id == "pilot:fc_profile" }
        val snoozed = setOf(warn.key, crit.key)
        val sections = groups.sections(a, snoozed)
        assertEquals(listOf(GroupedAlert(crit, collapsed = true)), sections[0].alerts)
        assertEquals(listOf("Compass interference"), sections[1].alerts.map { it.alert.text })
        assertEquals(1, sections[1].hidden)
        // The pill counts the snoozed critical and drops the snoozed warning.
        assertEquals(2, pill.summary(AlertGroups.unsnoozed(a, snoozed), emptySet()).count)
    }

    @Test
    fun `a section whose every alert is hidden stays, empty, with its hidden count`() {
        val a = alerts(fc = fcTexts.take(1))
        assertEquals(
            listOf(AlertSection(AlertGroup.FlightController, "Flight controller messages", emptyList(), 1)),
            groups.sections(a, setOf(a[0].key)),
        )
    }

    @Test
    fun `prune drops keys no longer live and keeps the same set when nothing cleared`() {
        val s = setOf("a|1", "b|2")
        assertSame(s, AlertSnooze.prune(s, listOf("a|1", "b|2", "c|3")))
        assertEquals(setOf("b|2"), AlertSnooze.prune(s, listOf("b|2")))
        assertEquals(setOf("a|1"), AlertSnooze.with(emptySet(), "a|1", true))
        assertEquals(emptySet<String>(), AlertSnooze.with(setOf("a|1"), "a|1", false))
    }
}
