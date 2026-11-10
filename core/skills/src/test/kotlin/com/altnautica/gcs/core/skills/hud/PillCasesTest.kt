package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.skills.testSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
            val s = pill.summary(pill.alerts(alerts, readiness, prearm, storage, fc), emptySet())
            val e = c.expected
            assertEquals(c.name, e["severity"].str(), s.severity.wire)
            assertEquals(c.name, e["text"].str(), s.text)
            assertEquals(c.name, e["count"].long()!!.toInt(), s.count)
            assertEquals(c.name, e["ids"].strings(), s.alerts.map { it.id })
        }
    }
}
