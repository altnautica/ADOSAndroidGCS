package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/** The top bar's link cell and chip cap, read from the spec's `top_bar` zone. */
class LinkCellTest {
    private val topBar = testSpec.layout.zones.first { it.id == "top_bar" }

    @Test
    fun `the link icon colours by the newest frame's age at the spec bands`() {
        val link = topBar.link!!
        assertEquals(3_000L, link.iconColor.okBelowMs)
        assertEquals(LinkHealth.Ok, linkIconHealth(LinkHealth.Ok, 29, link))
        assertEquals(LinkHealth.Stale, linkIconHealth(LinkHealth.Ok, 30, link))
        assertEquals(LinkHealth.Stale, linkIconHealth(LinkHealth.Ok, 99, link))
        assertEquals(LinkHealth.Lost, linkIconHealth(LinkHealth.Ok, 100, link))
        assertEquals(LinkHealth.None, linkIconHealth(LinkHealth.None, 5, link))
        assertEquals(LinkHealth.Stale, linkIconHealth(LinkHealth.Stale, null, link))
    }

    @Test
    fun `the link age reproduces every spec age_format case`() {
        val link = topBar.link!!
        val cases = link.ageFormat.cases
        assertEquals(5, cases.size)
        for (case in cases) {
            val ageMs = case[0].jsonPrimitive.long
            val want = case[1].jsonPrimitive.content
            assertEquals("age $ageMs", want, testSpec.setupStrings.format(link.text, mapOf("s" to linkAgeSeconds(ageMs, link.ageFormat))))
        }
    }

    @Test
    fun `the chip never takes more than the spec share of the top bar`() {
        assertEquals(0.4f, topBar.chip!!.maxFrac)
        assertEquals(400, chipCapPx(1000, topBar.chip!!.maxFrac))
        assertEquals(0, chipCapPx(1000, -1f))
    }

    @Test
    fun `battery time to reserve reads from the spec keys`() {
        val keys = topBar.battery!!.timeToReserve!!
        assertEquals("4:10 to reserve", testSpec.setupStrings.format(keys.text, mapOf("t" to "4:10")))
        assertEquals("4:10 RES", testSpec.setupStrings.format(keys.short, mapOf("t" to "4:10")))
    }
}
