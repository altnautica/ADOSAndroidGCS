package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.hud.ReadoutFormat
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightCheck
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusModelTest {
    private val none = PreflightResult(emptyList())

    @Test
    fun `nothing active reads all systems OK and never flashes`() {
        val pill = StatusModel.pill(StatusModel.items(emptyList(), none, emptyList()), emptySet())
        assertEquals(StatusPillUi(StatusSeverity.Ok, 0, flashing = false, top = null), pill)
    }

    @Test
    fun `every source contributes, worst first`() {
        val alerts = listOf(
            CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 10),
            CockpitAlert(AlertId.LINK_LOST, AlertLevel.Critical, "linkLost", null, 20),
            CockpitAlert(AlertId.PREARM, AlertLevel.Warning, "prearm", "Compass not calibrated", 30),
            CockpitAlert("advisory", AlertLevel.Advisory, "x", null, 40),
        )
        val preflight = PreflightResult(
            checks = listOf(
                PreflightCheck(PreflightItem.NavSource, passed = false, known = true),
                PreflightCheck(PreflightItem.Storage, passed = false, known = true),
                PreflightCheck(PreflightItem.Gps3d, passed = false, known = true),
            ),
            readiness = listOf(ReadinessItem("agl", ok = false, severity = ReadinessSeverity.Warn, msg = "No AGL source")),
        )
        val items = StatusModel.items(alerts, preflight, listOf(ReceivedText("EKF variance", 5)))
        assertEquals(StatusSeverity.Critical, items.first().severity)
        assertEquals(StatusSource.Link, items.first().source)
        val sources = items.map { it.source }.toSet()
        assertEquals(
            setOf(StatusSource.Link, StatusSource.Battery, StatusSource.FcPrearm, StatusSource.NavSource, StatusSource.Storage, StatusSource.PilotReadiness, StatusSource.FcStatusText),
            sources,
        )
        // Advisories stay out; the GPS check is an alert's job, not a duplicate here.
        assertEquals(7, items.size)
    }

    @Test
    fun `FC pre-arm text raised as an advisory still shows in the pill as a warning`() {
        val items = StatusModel.items(
            listOf(CockpitAlert(AlertId.PREARM, AlertLevel.Advisory, "prearm", "Compass not calibrated", 1)),
            none,
            emptyList(),
        )
        assertEquals(1, items.size)
        assertEquals(StatusSource.FcPrearm, items.single().source)
        assertEquals(StatusSeverity.Warning, items.single().severity)
    }

    @Test
    fun `a new item flashes until the list is opened, and a later one flashes again`() {
        val first = StatusModel.items(listOf(CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 1)), none, emptyList())
        assertTrue(StatusModel.pill(first, emptySet()).flashing)
        val acked = first.mapTo(HashSet()) { it.key }
        assertFalse(StatusModel.pill(first, acked).flashing)
        val second = StatusModel.items(
            listOf(
                CockpitAlert(AlertId.BATT_LOW, AlertLevel.Warning, "battLow", null, 1),
                CockpitAlert(AlertId.LINK_STALE, AlertLevel.Warning, "linkStale", null, 2),
            ),
            none,
            emptyList(),
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
        val fresh = StatusModel.items(emptyList(), readiness, emptyList(), PillFacts(pilotRule = true, pilotAgeMs = 1_999, controllerLost = true))
        assertEquals(listOf("ready:position", "controller_lost"), fresh.map { it.key })
        val stale = StatusModel.items(emptyList(), readiness, emptyList(), PillFacts(pilotRule = true, pilotAgeMs = 2_000, controllerLost = true))
        assertEquals(listOf("pilot_not_answering"), stale.map { it.key })
        val never = StatusModel.items(emptyList(), none, emptyList(), PillFacts(pilotRule = true, pilotAgeMs = null))
        assertEquals(listOf("pilot_not_answering"), never.map { it.key })
        // A vehicle without an agent has no pilot rule.
        assertEquals(emptyList<StatusItem>(), StatusModel.items(emptyList(), none, emptyList(), PillFacts(pilotRule = false)))
    }

    @Test
    fun `an unreachable agent is critical and failing pre-flight items each warn`() {
        val items = StatusModel.items(
            emptyList(), none, emptyList(),
            PillFacts(pilotRule = true, pilotAgeMs = 100, agentReachable = false, preflightFailing = listOf(PreflightItem.Gps3d, PreflightItem.Battery)),
        )
        assertEquals(listOf("agent_unreachable", "preflight:Gps3d", "preflight:Battery"), items.map { it.key })
        assertEquals(StatusSeverity.Critical, StatusModel.pill(items, emptySet()).severity)
        assertEquals(StatusSource.Preflight, items[1].source)
        assertEquals(StatusModel.PREFLIGHT_FAILING_KEY, items[1].specKey)
    }

    @Test
    fun `the card steps through its three pages and wraps`() {
        assertEquals(CardPage.Attitude, CardPage.Map.step(1))
        assertEquals(CardPage.Map, CardPage.Obstacles.step(1))
        assertEquals(CardPage.Obstacles, CardPage.Map.step(-1))
    }
}
