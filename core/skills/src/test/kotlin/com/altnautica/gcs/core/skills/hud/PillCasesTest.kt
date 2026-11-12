package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.adosPilot
import com.altnautica.gcs.core.skills.testSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin status pill reads every shared pill case as the web module does. */
class PillCasesTest {
    private val pill = StatusPill(
        SpecText(testSpec.setupStrings),
        testSpec.layout.statusPill!!.storageWarnPct!!,
        testSpec.layout.statusPill!!.storageCritPct!!,
    )

    @Test
    fun `every pill case reads as the shared pill`() {
        for (c in goldenCases("pill-cases.json")) {
            val i = c.input
            val alerts = i["alerts"]!!.jsonArray.map {
                val o = it.jsonObject
                PillAlertInput(o["id"].str()!!, PillAlertLevel.valueOf(o["level"].str()!!.replaceFirstChar(Char::uppercase)), o["title"].str()!!)
            }
            val readiness = i["readiness"]!!.jsonArray.map {
                val o = it.jsonObject
                ReadinessItem(
                    o["id"].str()!!,
                    o["ok"].bool()!!,
                    if (o["severity"].str() == "block") ReadinessSeverity.Block else ReadinessSeverity.Warn,
                    o["msg"].str().orEmpty(),
                )
            }
            val prearm = (i["prearm"] as? JsonArray)?.strings()
            val fc = (i["fc_status"] as? JsonArray).orEmpty().map {
                val o = it.jsonObject
                PillFcStatus(o["severity"].long()!!.toInt(), o["text"].str()!!)
            }
            val storage = (i["storage_pct"]?.takeUnless { it is JsonNull } as? JsonPrimitive)?.doubleOrNull
            val preflight = (i["preflight_failing"] as? JsonArray).orEmpty().map {
                val o = it.jsonObject
                o["id"].str()!! to o["label"].str()!!
            }
            val nav = (i["nav_source"] as? kotlinx.serialization.json.JsonObject)?.let { o ->
                PillNavSource(
                    PillNavSource.Kind.entries.single { it.wire == o["kind"].str() },
                    PillNavSource.Tone.entries.single { it.wire == o["tone"].str() },
                )
            }
            val s = pill.summary(
                pill.alerts(
                    alerts, readiness, prearm, storage, fc,
                    pilotRule = true,
                    pilotAgeMs = i["pilot_age_ms"].long(),
                    agentReachable = i["agent_reachable"].bool() ?: true,
                    controllerLost = i["controller_lost"].bool() ?: false,
                    preflightFailing = preflight,
                    failsafe = PillFailsafe(i["fc_failsafe"].bool() ?: false, i["failsafe_cause"].str()),
                    // An explicit null is unknown; an absent key is not known to this surface.
                    armUnknown = "armed" in i && i["armed"] is JsonNull,
                    navSource = nav,
                ),
                emptySet(),
            )
            val e = c.expected
            assertEquals(c.name, e["severity"].str(), s.severity.wire)
            assertEquals(c.name, e["text"].str(), s.text)
            assertEquals(c.name, e["count"].long()!!.toInt(), s.count)
            assertEquals(c.name, e["ids"].strings(), s.alerts.map { it.id })
        }
    }

    @Test
    fun `the pill rule is off for a surface that does not read the pilot kind`() {
        val alerts = pill.alerts(emptyList(), emptyList(), null, null, pilotAgeMs = null)
        assertEquals(emptyList<PillAlert>(), alerts)
    }

    @Test
    fun `the failsafe cases are in the shared file and an unknown cause reads as itself`() {
        val names = goldenCases("pill-cases.json").map { it.name }
        assertTrue(names.toString(), names.count { it.startsWith("fc_failsafe_") } >= 5)
        assertEquals("Failsafe: battery", pill.failsafeText("battery"))
        assertEquals("Failsafe: flight controller", pill.failsafeText(null))
    }

    @Test
    fun `the failsafe item comes from a fresh pilot state only`() {
        val on = PillFailsafe.of(adosPilot().copy(fcFailsafe = true, failsafeCause = "rc_or_failsafe"))
        assertEquals(PillFailsafe.None, PillFailsafe.of(adosPilot()))
        assertEquals(PillFailsafe(true, "rc_or_failsafe"), on)
        val fresh = pill.alerts(emptyList(), emptyList(), null, null, pilotRule = true, pilotAgeMs = 100, failsafe = on)
        assertEquals(listOf("failsafe"), fresh.map { it.id })
        assertEquals(PillSeverity.Critical, fresh.single().severity)
        val stale = pill.alerts(emptyList(), emptyList(), null, null, pilotRule = true, pilotAgeMs = 5_000, failsafe = on)
        assertEquals(listOf("pilot_not_answering"), stale.map { it.id })
    }

    @Test
    fun `texts heard on the vehicle link stay while the pilot state is stale`() {
        val link = listOf(PillFcStatus(2, "GCS Failsafe"))
        val stale = pill.alerts(emptyList(), emptyList(), null, null, listOf(PillFcStatus(4, "EKF variance")), pilotRule = true, pilotAgeMs = 5_000, linkFcStatus = link)
        assertEquals(listOf("fcst:GCS Failsafe", "pilot_not_answering"), stale.map { it.id })
        assertEquals(PillSeverity.Critical, stale.first().severity)
    }

    @Test
    fun `without a critical percent the storage item only warns`() {
        val phone = StatusPill(SpecText(testSpec.setupStrings), testSpec.layout.statusPill!!.storageWarnPct!!, storageCritPct = null)
        assertEquals(PillSeverity.Warning, phone.alerts(emptyList(), emptyList(), null, 99.0).single().severity)
    }

    @Test
    fun `the shared file covers an unknown arm state and the nav source item`() {
        val names = goldenCases("pill-cases.json").map { it.name }
        assertTrue(names.toString(), "arm_unknown_hides_preflight_items" in names)
        assertTrue(names.toString(), names.count { it.startsWith("nav_source_") } >= 4)
    }

    @Test
    fun `a controller lost with the arm state unknown counts as possibly airborne`() {
        val held = adosPilot().copy(holdReason = "controller-lost")
        assertTrue(StatusPill.controllerLost(held.copy(armed = null, landed = null)))
        assertTrue(StatusPill.controllerLost(held.copy(armed = true, landed = false)))
        assertEquals(false, StatusPill.controllerLost(held.copy(armed = false, landed = true)))
        assertEquals(false, StatusPill.controllerLost(held.copy(armed = true, landed = true)))
    }
}
