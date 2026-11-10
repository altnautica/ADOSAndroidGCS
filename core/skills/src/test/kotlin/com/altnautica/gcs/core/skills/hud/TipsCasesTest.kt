package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import com.altnautica.gcs.core.skills.tips.TipDecision
import com.altnautica.gcs.core.skills.tips.TipsContext
import com.altnautica.gcs.core.skills.tips.TipRect
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        alertBannerVisible = o["alertBannerVisible"].bool() ?: false,
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

    private fun rect(o: JsonObject) = TipRect(o.f("x"), o.f("y"), o.f("w"), o.f("h"))

    private fun JsonObject.f(k: String): Float = this[k]!!.jsonPrimitive.double.toFloat()

    /** A placement case: the placer's `{x, y, side}`, or null when nothing fits. */
    private fun place(p: JsonObject): Map<String, Any?>? {
        val tip = p["tip"]!!.jsonObject
        val at = TipsEngine.placeTip(
            anchor = rect(p["anchor"]!!.jsonObject),
            tipW = tip.f("w"),
            tipH = tip.f("h"),
            viewport = rect(p["viewport"]!!.jsonObject),
            avoid = p["avoid"]!!.jsonArray.map { rect(it.jsonObject) },
        ) ?: return null
        return mapOf("x" to at.x.toDouble(), "y" to at.y.toDouble(), "side" to at.side.wire)
    }

    @Test
    fun `every tips case decides as the shared engine`() {
        val cases = goldenCases("tips-cases.json")
        for (c in cases) {
            val p = c.input["place"]
            if (p != null) {
                val want = c.expected.takeUnless { it.isEmpty() }?.let {
                    mapOf("x" to it.f("x").toDouble(), "y" to it.f("y").toDouble(), "side" to it["side"].str())
                }
                assertEquals(c.name, want, place(p.jsonObject))
                continue
            }
            val s = c.input["state"]!!.jsonObject
            val state = TipsState(s["seen"].strings(), s["openCount"].long()!!.toInt())
            val got = engine.nextTip(state, ctx(c.input["ctx"]!!.jsonObject), c.input["now_ms"].long()!!)
            assertEquals(c.name, expected(c.expected), wire(got))
        }
    }

    @Test
    fun `the shared cases include placements`() {
        assertTrue(goldenCases("tips-cases.json").count { "place" in it.input } >= 4)
    }

    @Test
    fun `state helpers follow the web module`() {
        val s = TipsEngine.onFlightScreenOpen(TipsEngine.resetTips())
        assertEquals(1, s.openCount)
        val used = TipsEngine.dismissTip(TipsEngine.dismissTip(s, "stop"), "stop")
        assertEquals(listOf("stop"), used.seen)
        assertEquals(2, TipsEngine.onFlightScreenOpen(used).openCount)
        assertEquals(listOf("stop"), TipsEngine.onFlightScreenOpen(used).seen)
        // A reset forgets every tip seen and every open counted.
        val reset = TipsEngine.resetTips()
        assertEquals(emptyList<String>(), reset.seen)
        assertEquals(0, reset.openCount)
    }
}
