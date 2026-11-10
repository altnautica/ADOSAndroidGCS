package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import com.altnautica.gcs.core.skills.tips.TipDecision
import com.altnautica.gcs.core.skills.tips.TipsContext
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin tip queue decides every shared tips case as the web module does. */
class TipsCasesTest {
    private val engine = TipsEngine(testSpec.tips)

    private fun ctx(o: JsonObject) = TipsContext(
        armed = o["armed"].bool()!!,
        airborne = o["airborne"].bool()!!,
        flightScreen = o["flightScreen"].bool()!!,
        sheetOrMenuOpen = o["sheetOrMenuOpen"].bool()!!,
        tourVisible = o["tourVisible"].bool()!!,
        skillsAvailable = o["skillsAvailable"].bool()!!,
        gotoOffered = o["gotoOffered"].bool()!!,
        gimbalAvailable = o["gimbalAvailable"].bool()!!,
        anchorsVisible = o["anchorsVisible"]!!.jsonObject.mapValues { it.value.bool() == true },
        screenOpenedAtMs = o["screenOpenedAtMs"].long()!!,
        lastDismissedAtMs = o["lastDismissedAtMs"].long(),
        shownTipId = o["shownTipId"].str(),
        shownAtMs = o["shownAtMs"].long(),
        hiddenThisOpen = o["hiddenThisOpen"].strings().toSet(),
    )

    private fun wire(d: TipDecision): Map<String, Any?> = when (d) {
        TipDecision.None -> mapOf("kind" to "none")
        is TipDecision.Wait -> mapOf("kind" to "wait", "tipId" to d.tipId, "atMs" to d.atMs)
        is TipDecision.Show -> mapOf("kind" to "show", "tipId" to d.tipId, "textKey" to d.textKey, "anchor" to d.anchor)
        is TipDecision.Hide -> mapOf("kind" to "hide", "tipId" to d.tipId, "markSeen" to d.markSeen, "reason" to d.reason.wire)
    }

    private fun expected(o: JsonObject): Map<String, Any?> = o.mapValues { (k, v) ->
        when (k) {
            "atMs" -> v.long()
            "markSeen" -> v.bool()
            else -> v.str()
        }
    }

    @Test
    fun `every tips case decides as the shared engine`() {
        val cases = goldenCases("tips-cases.json")
        for (c in cases) {
            val s = c.input["state"]!!.jsonObject
            val state = TipsState(s["seen"].strings(), s["openCount"].long()!!.toInt())
            val got = engine.nextTip(state, ctx(c.input["ctx"]!!.jsonObject), c.input["now_ms"].long()!!)
            assertEquals(c.name, expected(c.expected), wire(got))
        }
    }

    @Test
    fun `state helpers follow the web module`() {
        val s = TipsEngine.onFlightScreenOpen(TipsEngine.resetTips())
        assertEquals(1, s.openCount)
        assertEquals(listOf("stop"), TipsEngine.dismissTip(TipsEngine.dismissTip(s, "stop"), "stop").seen)
        assertEquals(TipsState(), TipsEngine.resetTips())
    }
}
