package com.altnautica.gcs.core.extensions.host

import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.extensions.bridge.FrameShell
import com.altnautica.gcs.core.extensions.fixture
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetailParser
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.marks.CockpitMark
import com.altnautica.gcs.core.extensions.marks.CockpitMarksParser
import com.altnautica.gcs.core.extensions.marks.MarkFrame
import com.altnautica.gcs.core.extensions.marks.MarkSpace
import com.altnautica.gcs.core.extensions.theme.ExtensionTheme
import com.altnautica.gcs.core.model.NodeProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPiecesTest {

    private val siyi = ExtensionDetailParser.parse(fixture("siyi-pod.detail.json"))!!
    private val follow = ExtensionDetailParser.parse(fixture("follow-me.detail.json"))!!

    @Test
    fun slotsMountOnlyWithTheSlotCapability() {
        val overlay = SlotMounts.resolve(listOf(siyi, follow), ExtensionSlots.VIDEO_OVERLAY, NodeProfile.Drone)
        assertEquals(listOf("siyi-overlay"), overlay.map { it.contribution.panelId })

        val revoked = siyi.copy(grantedCapabilities = siyi.grantedCapabilities - "ui.slot.video-overlay")
        assertTrue(SlotMounts.resolve(listOf(revoked), ExtensionSlots.VIDEO_OVERLAY, NodeProfile.Drone).isEmpty())

        val disabled = siyi.copy(status = com.altnautica.gcs.core.extensions.manifest.InstallStatus.Disabled)
        assertTrue(SlotMounts.resolve(listOf(disabled), ExtensionSlots.COCKPIT_PANEL, NodeProfile.Drone).isEmpty())
    }

    @Test
    fun nodeTabsRespectProfile() {
        assertEquals(1, SlotMounts.resolve(listOf(follow), ExtensionSlots.NODE_DETAIL_TAB, NodeProfile.Drone).size)
        assertTrue(SlotMounts.resolve(listOf(follow), ExtensionSlots.NODE_DETAIL_TAB, NodeProfile.GroundStation).isEmpty())
        // An unresolved profile keeps the tab rather than hiding it on a transient gap.
        assertEquals(1, SlotMounts.resolve(listOf(follow), ExtensionSlots.NODE_DETAIL_TAB, NodeProfile.Unknown).size)
    }

    @Test
    fun widgetZoneDefaultsToBottomLeft() {
        val detail = ExtensionDetailParser.parse(
            Json.parseToJsonElement(
                """{"install":{"plugin_id":"w","status":"enabled"},"granted_capabilities":["ui.slot.cockpit-widget"],
                 "manifest":{"id":"w","name":"W","gcs":{"entrypoint":"gcs/a.js","contributes":{"panels":[
                   {"id":"a","slot":"cockpit.widget"},{"id":"b","slot":"cockpit.widget","zone":"top-right"}]}}}}""",
            ),
        )!!
        assertEquals(listOf("a"), SlotMounts.resolve(listOf(detail), ExtensionSlots.COCKPIT_WIDGET, NodeProfile.Drone, "bottom-left").map { it.contribution.panelId })
        assertEquals(listOf("b"), SlotMounts.resolve(listOf(detail), ExtensionSlots.COCKPIT_WIDGET, NodeProfile.Drone, "top-right").map { it.contribution.panelId })
    }

    @Test
    fun themeVariablesMatchTheExtensionContract() {
        val dark = ExtensionTheme.cssVariables(AdosColors.BrandDark)
        assertEquals(32, dark.size)
        assertEquals("#000000", dark["--bg-primary"])
        assertEquals("#141414", dark["--bg-tertiary"])
        assertEquals("#5B9AFF", dark["--hud-primary"])
        assertEquals("rgba(0, 0, 0, 0.58)", dark["--hud-glass"])
        assertEquals("rgba(255, 255, 255, 0.16)", dark["--hud-hair"])
        val nvg = ExtensionTheme.cssVariables(AdosColors.Nvg)
        assertEquals("#000000", nvg["--bg-primary"])
        assertEquals("rgba(0, 17, 0, 0.8)", nvg["--hud-glass"])
    }

    @Test
    fun frameShellPinsPolicyBeforeTheBundle() {
        val html = FrameShell.build("const s = '</script><img>';")
        val head = html.indexOf(FrameShell.CSP_META)
        val module = html.indexOf("<script type=\"module\">")
        assertTrue(head in 0 until module)
        assertTrue(html.contains("connect-src 'none'"))
        assertFalse(html.substring(module).contains("'</script>"))
        assertTrue(html.contains("<\\/script>"))
    }

    @Test
    fun marksAreValidatedAndCapped() {
        val marks = CockpitMarksParser.parse(
            Json.parseToJsonElement(
                """[{"id":"a","kind":"box","x":1,"y":2,"width":3,"height":4,"dashed":true},
                    {"id":"b","kind":"box","x":"1","y":2,"width":3,"height":4},
                    {"kind":"point","x":0,"y":0},
                    {"id":"c","kind":"label","x":0.5,"y":0.5,"space":"normalized","text":"${"x".repeat(100)}"},
                    {"id":"d","kind":"polyline","points":[[0,0],[1]]}]""",
            ),
        )
        assertEquals(listOf("a", "c"), marks.map { it.id })
        assertEquals(64, (marks[1] as CockpitMark.Label).text.length)
        assertNull(CockpitMarksParser.mapPoint(1.0, 1.0, MarkSpace.Frame, 100f, 100f, null))
        val frame = MarkFrame(10f, 0f, 200f, 100f, 640, 320)
        assertEquals(10f + 320 * (200f / 640) to 160 * (100f / 320), CockpitMarksParser.mapPoint(320.0, 160.0, MarkSpace.Frame, 0f, 0f, frame))
        assertEquals(50f to 25f, CockpitMarksParser.mapPoint(0.5, 0.25, MarkSpace.Normalized, 100f, 100f, null))
    }
}
