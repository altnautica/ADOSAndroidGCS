package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.hud.ReadoutFormat
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.StatusPill
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightCheck
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production pill path: cockpit alerts and facts through the shared status pill. */
fun specStatusPill(): StatusPill = StatusPill(
    SpecText(testSpec.setupStrings),
    testSpec.layout.statusPill!!.storageWarnPct!!,
    testSpec.layout.statusPill!!.storageCritPct!!,
)

class StatusModelTest {
    private val none = PreflightResult(emptyList())
    private val rules = specStatusPill()

    private fun items(
        alerts: List<CockpitAlert>,
        preflight: PreflightResult = none,
        texts: List<ReceivedText> = emptyList(),
        facts: PillFacts = PillFacts(),
    ) = StatusModel.items(alerts, preflight, texts, facts, rules)

    @Test
    fun `nothing active reads all systems OK and never flashes`() {
        val pill = StatusModel.pill(items(emptyList()), emptySet())
        assertEquals(StatusPillUi(StatusSeverity.Ok, 0, flashing = false, top = null), pill)
    }

    @Test
    fun `every source contributes, worst first, then in the shared source order`() {
        val alerts = listOf(
            CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 10),
            CockpitAlert(AlertId.LINK_LOST, AlertLevel.Critical, "linkLost", null, 20),
            CockpitAlert(AlertId.PREARM, AlertLevel.Advisory, "prearm", "Compass not calibrated", 30),
            CockpitAlert("advisory", AlertLevel.Advisory, "x", null, 40),
        )
        val preflight = PreflightResult(
            checks = listOf(PreflightCheck(PreflightItem.Storage, passed = false, known = true, blocking = false)),
            readiness = listOf(ReadinessItem("agl", ok = false, severity = ReadinessSeverity.Warn, msg = "No AGL source")),
            storageUsedPct = 98.0,
        )
        val got = items(alerts, preflight, listOf(ReceivedText("EKF variance", 5)))
        assertEquals(
            listOf("alert:link_lost", "storage", "alert:batt_low", "pilot:agl", "fc:Compass not calibrated", "fcst:EKF variance"),
            got.map { it.key },
        )
        assertEquals(
            listOf(StatusSource.Link, StatusSource.Storage, StatusSource.Battery, StatusSource.PilotReadiness, StatusSource.FcPrearm, StatusSource.FcStatusText),
            got.map { it.source },
        )
        assertEquals("98 % used", got[1].text)
        assertEquals(StatusSeverity.Critical, got[1].severity)
        assertEquals("Flight controller: Compass not calibrated", got[4].text)
    }

    @Test
    fun `storage warns at the spec's percent used, not at a free-space floor`() {
        fun storage(pct: Double?) = items(emptyList(), PreflightResult(emptyList(), storageUsedPct = pct)).singleOrNull()?.severity
        assertEquals(null, storage(null))
        assertEquals(null, storage(89.4))
        assertEquals(StatusSeverity.Warning, storage(90.0))
        assertEquals(StatusSeverity.Critical, storage(98.0))
    }

    @Test
    fun `a critical FC text is a critical status-text item, and a warning text stays a warning`() {
        val got = items(
            listOf(CockpitAlert(AlertId.STATUS_TEXT, AlertLevel.Critical, "statusText", "Crash: Disarming", 1)),
            texts = listOf(ReceivedText("EKF3 lane switch 1", 2, severity = 4), ReceivedText("Crash: Disarming", 3, severity = 4)),
        )
        assertEquals(listOf("fcst:Crash: Disarming", "fcst:EKF3 lane switch 1"), got.map { it.key })
        assertEquals(listOf(StatusSeverity.Critical, StatusSeverity.Warning), got.map { it.severity })
        assertEquals(StatusSource.FcStatusText, got.first().source)
    }

    @Test
    fun `a new item flashes until the list is opened, and a later one flashes again`() {
        val first = items(listOf(CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 1)))
        assertTrue(StatusModel.pill(first, emptySet()).flashing)
        val acked = first.mapTo(HashSet()) { it.key }
        assertFalse(StatusModel.pill(first, acked).flashing)
        val second = items(
            listOf(
                CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 1),
                CockpitAlert(AlertId.LINK_STALE, AlertLevel.Warning, "linkStale", null, 2),
            ),
        )
        val pill = StatusModel.pill(second, acked)
        assertTrue(pill.flashing)
        assertEquals(2, pill.count)
        assertEquals(StatusSeverity.Warning, pill.severity)
    }

    @Test
    fun `bottom row values read through the shared readout formatters`() {
        assertEquals("—" to "m", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Distance, null))
        assertEquals("120" to "m", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Distance, 120.4))
        assertEquals("1.25" to "km", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Distance, 1250.0))
        assertEquals("120.4" to "m", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Metres, 120.4))
        assertEquals("0.0" to "m/s", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Speed, -0.04))
        assertEquals("—" to "m/s", ReadoutFormat.readoutParts(ReadoutFormat.Kind.Speed, null))
    }

    @Test
    fun `a missing or stale pilot reads not answering and drops its readiness and controller items`() {
        val readiness = PreflightResult(emptyList(), listOf(ReadinessItem("position", ok = false, severity = ReadinessSeverity.Block, msg = "No position estimate")))
        val fresh = items(emptyList(), readiness, facts = PillFacts(pilotRule = true, pilotAgeMs = 1_999, controllerLost = true))
        assertEquals(listOf("pilot:position", "controller_lost"), fresh.map { it.key })
        val stale = items(emptyList(), readiness, facts = PillFacts(pilotRule = true, pilotAgeMs = 2_000, controllerLost = true))
        assertEquals(listOf("pilot_not_answering"), stale.map { it.key })
        val never = items(emptyList(), facts = PillFacts(pilotRule = true, pilotAgeMs = null))
        assertEquals(listOf("pilot_not_answering"), never.map { it.key })
        // A vehicle without an agent has no pilot rule.
        assertEquals(emptyList<StatusItem>(), items(emptyList(), facts = PillFacts(pilotRule = false)))
    }

    @Test
    fun `an unreachable agent is critical and failing pre-flight items each warn`() {
        val got = items(
            emptyList(),
            facts = PillFacts(pilotRule = true, pilotAgeMs = 100, agentReachable = false, preflightFailing = listOf(PreflightItem.Gps3d, PreflightItem.Battery)),
        )
        assertEquals(listOf("agent_unreachable", "preflight:gps", "preflight:battery"), got.map { it.key })
        assertEquals(StatusSeverity.Critical, StatusModel.pill(got, emptySet()).severity)
        assertEquals(StatusSource.Preflight, got[1].source)
        assertEquals(PreflightItem.Gps3d, got[1].check)
        assertEquals(StatusModel.PREFLIGHT_FAILING_KEY, got[1].specKey)
    }

    @Test
    fun `the card steps through its three pages and wraps`() {
        assertEquals(CardPage.Attitude, CardPage.Map.step(1))
        assertEquals(CardPage.Map, CardPage.Obstacles.step(1))
        assertEquals(CardPage.Obstacles, CardPage.Map.step(-1))
    }
}
