package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.hud.PillFailsafe
import com.altnautica.gcs.core.skills.hud.PillNavSource
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.StatusPill
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared pill cases (`pill-cases.json`, the web module's golden file) read through the
 * pill the flight screen shows: cockpit alerts, pre-flight result, FC texts and pill facts
 * into [StatusModel.items], the way the view model feeds it. The cases grade the vehicle's
 * disk, so they run with the shared storage rule; the phone's own storage only warns
 * (see [StatusModelTest]).
 */
class StatusPillCasesTest {
    private val rules = StatusPill(
        SpecText(testSpec.setupStrings),
        testSpec.layout.statusPill!!.storageWarnPct!!,
        testSpec.layout.statusPill!!.storageCritPct!!,
    )
    private val strings = testSpec.setupStrings.strings

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.array(): List<JsonObject> = (this as? JsonArray)?.map { it.jsonObject }.orEmpty()

    /** The cockpit alert id of a shared alert id (the view model's ids). */
    private fun cockpitId(specId: String): String =
        listOf(AlertId.LINK_LOST, AlertId.LINK_STALE, AlertId.BATT_CRIT, AlertId.BATT_LOW, AlertId.GPS_NO_3D, AlertId.VIDEO_FROZEN)
            .firstOrNull { StatusModel.specAlertId(it) == specId } ?: specId

    @Test
    fun `every shared pill case reads the same through the flight screen's pill`() {
        val cases = Json.parseToJsonElement(File(FIXTURE).readText()).jsonArray.map { it.jsonObject }
        val skipped = ArrayList<String>()
        var preflightRan = 0
        for (c in cases) {
            val name = c["name"].str()!!
            val i = c["input"]!!.jsonObject
            val e = c["expected"]!!.jsonObject
            // Android's checklist names its own items; a case naming another surface's item cannot be fed.
            val preflight = i["preflight_failing"].array().map { p -> PreflightItem.entries.firstOrNull { it.id == p["id"].str() } }
            if (null in preflight) {
                skipped += name
                continue
            }
            val alerts = ArrayList<CockpitAlert>()
            for (a in i["alerts"].array()) {
                val level = AlertLevel.valueOf(a["level"].str()!!.replaceFirstChar(Char::uppercase))
                alerts += CockpitAlert(cockpitId(a["id"].str()!!), level, "alert.${a["id"].str()}", null, 0)
            }
            // The view model holds the newest PreArm reason as the pre-arm alert.
            (i["prearm"] as? JsonArray)?.lastOrNull()?.str()?.let {
                alerts += CockpitAlert(AlertId.PREARM, AlertLevel.Advisory, "alert.prearm", AlertEngine.prearmBody(it), 0)
            }
            // The cases' FC texts are the ones the pilot relays.
            val texts = i["fc_status"].array().map { ReceivedText(it["text"].str()!!, 0, it["severity"].long()!!.toInt(), relayed = true) }
            val readiness = i["readiness"].array().map {
                ReadinessItem(it["id"].str()!!, it["ok"].bool()!!, if (it["severity"].str() == "block") ReadinessSeverity.Block else ReadinessSeverity.Warn, it["msg"].str().orEmpty())
            }
            val facts = PillFacts(
                pilotRule = true,
                pilotAgeMs = i["pilot_age_ms"].long(),
                agentReachable = i["agent_reachable"].bool() ?: true,
                controllerLost = i["controller_lost"].bool() ?: false,
                preflightFailing = preflight.filterNotNull(),
                // An explicit null arm state is unknown; an absent one is not part of the case.
                armUnknown = i.containsKey("armed") && i["armed"] is JsonNull,
                navSource = (i["nav_source"] as? JsonObject)?.let { n ->
                    PillNavSource(
                        PillNavSource.Kind.entries.first { it.wire == n["kind"].str() },
                        PillNavSource.Tone.entries.first { it.wire == n["tone"].str() },
                    )
                },
            )
            val failsafe = PillFailsafe(i["fc_failsafe"].bool() ?: false, i["failsafe_cause"].str())
            val storage = (i["storage_pct"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull
            val items = StatusModel.items(alerts, PreflightResult(emptyList(), readiness, storage), texts, facts, rules, failsafe)
            val pill = StatusModel.pill(items, emptySet())

            assertEquals(name, e["severity"].str(), pill.severity.name.lowercase())
            assertEquals(name, e["count"].long()!!.toInt(), pill.count)
            assertEquals(name, (e["ids"] as JsonArray).map { it.str() }, items.map { it.key })
            assertEquals(name, e["text"].str(), face(items, i))
            if (preflight.isNotEmpty()) preflightRan++
        }
        assertEquals("cases Android cannot feed", listOf("preflight_failing_each_item"), skipped)
        assertEquals("the failsafe cases ran", 5, cases.count { it["name"].str()!!.startsWith("fc_failsafe_") && it["name"].str() !in skipped })
        assertTrue("a pre-flight case ran", preflightRan >= 1)
    }

    /** The pill's face as the top bar words it: alert titles and check labels in the case's own words. */
    private fun face(items: List<StatusItem>, input: JsonObject): String = when (items.size) {
        0 -> strings.getValue("cockpit.status.ok")
        1 -> {
            val top = items.single()
            when {
                top.alert != null -> input["alerts"].array().first { cockpitId(it["id"].str()!!) == top.alert!!.id }["title"].str()!!
                top.check != null -> strings.getValue(top.specKey!!).replace("{item}", input["preflight_failing"].array().single()["label"].str()!!)
                else -> top.text!!
            }
        }
        else -> strings.getValue("cockpit.status.many").replace("{n}", items.size.toString())
    }

    private companion object {
        const val FIXTURE = "../../core/skills/src/test/resources/fixtures/pill-cases.json"
    }
}
