package com.altnautica.gcs.core.extensions.manifest

import com.altnautica.gcs.core.extensions.fixture
import com.altnautica.gcs.core.extensions.parameters.ParameterBinding
import com.altnautica.gcs.core.extensions.parameters.ParameterWidget
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributionParserTest {

    private fun detail(name: String) = requireNotNull(ExtensionDetailParser.parse(fixture("$name.detail.json")))

    @Test
    fun followMeSkillTargetActionsTabAndParameters() {
        val d = detail("follow-me")
        assertEquals("com.altnautica.follow-me", d.pluginId)
        assertEquals(InstallStatus.Enabled, d.status)
        assertEquals("gcs/plugin.bundle.js", d.entrypoint)
        assertTrue("ui.slot.flight-skill" in d.grantedCapabilities)

        val skill = d.contributions.skills.single()
        assertEquals("follow-me", skill.id)
        assertEquals("active", skill.configKey)
        assertEquals("follow.state", skill.stateTopic)
        assertTrue(skill.toggle)
        assertTrue(skill.confirm)
        assertEquals("armed", skill.armRequirement)
        assertEquals("shift+f", skill.defaultKey)

        val actions = d.contributions.targetActions
        assertEquals(listOf("follow", "stop-follow"), actions.map { it.id })
        assertEquals(true, actions[0].designate)
        assertEquals(false, actions[1].configValue)
        assertEquals("person", actions[0].appliesToClass)

        val tab = d.contributions.slots.single { it.slot == ExtensionSlots.NODE_DETAIL_TAB }
        assertEquals(listOf("drone"), tab.profile)

        val camera = d.contributions.parameters.single { it.key == "designate_camera" }
        assertEquals(ParameterWidget.Camera, camera.ui?.widget)
        assertEquals("detect", camera.ui?.purpose)
        assertEquals(JsonPrimitive("auto"), camera.schema.default)
        // A camera selector always writes the extension's own config.
        assertEquals(ParameterBinding.PluginConfig, camera.binding)

        val distance = d.contributions.parameters.single { it.key == "follow_distance_m" }
        assertEquals(3.0, distance.schema.minimum)
        assertEquals(ParameterWidget.Range, distance.ui?.widget)
        assertEquals(ParameterBinding.EngineDetector, d.contributions.parameters.single { it.key == "detector" }.binding)

        val model = d.contributions.models.single()
        assertEquals("coco-person", model.id)
        assertEquals("rk3588", model.boardVariants.first().boardMatch)
    }

    @Test
    fun siyiPanelsDeclareTheirSlots() {
        val slots = detail("siyi-pod").contributions.slots.map { it.slot }.toSet()
        assertTrue(ExtensionSlots.COCKPIT_PANEL in slots)
        assertTrue(ExtensionSlots.VIDEO_OVERLAY in slots)
    }

    @Test
    fun worldEngineAgentPagesAndSurfaces() {
        val slots = detail("world-engine").contributions.slots
        val setup = slots.single { it.panelId == "world-model-setup" }
        assertEquals(ExtensionSlots.NODE_AGENT_PAGE, setup.slot)
        assertEquals("world-model", setup.setupFor)
        val relay = slots.single { it.panelId == "atlas-relay" }
        assertEquals(listOf("ground-station"), relay.profile)
        val surface = slots.single { it.panelId == "overview" && it.slot == ExtensionSlots.NODE_SURFACE }
        assertEquals("status", surface.group)
        // `compute` is a recognised profile; unknown profile names are dropped.
        assertEquals(listOf("workstation", "compute"), surface.profile)
    }

    @Test
    fun everyFirstPartyFixtureParses() {
        for (n in listOf("follow-me", "mavlink-gimbal-v2", "siyi-pod", "thermal-camera-flir-lepton-usb", "vision-nav", "world-engine")) {
            val d = detail(n)
            assertTrue(n, d.name.isNotBlank())
        }
    }

    @Test
    fun unsupportedSkillsAndUnknownSlotsAreDropped() {
        val c = ContributionParser.parse(
            Json.parseToJsonElement(
                """
                {"skills":[
                   {"id":"a","activation":{"via":"rpc","config_key":"x"},"state":{"via":"event","topic":"t"}},
                   {"id":"b","activation":{"via":"config","config_key":"on"},"state":{"via":"event","topic":"b.state"}},
                   {"label":"no id","activation":{"via":"config","config_key":"on"},"state":{"via":"event","topic":"t"}}],
                 "panels":[{"id":"p1","slot":"made.up"},{"id":"w","slot":"cockpit.widget","zone":"top-right"},{"slot":"cockpit.panel"}],
                 "node_surfaces":[{"id":"s","title":"S"}],
                 "agent_pages":[{"id":"Bad Id","title":"x"}]}
                """,
            ),
        )
        assertEquals(listOf("b"), c.skills.map { it.id })
        assertEquals("Sparkles", c.skills.single().icon)
        assertEquals(listOf("w"), c.slots.map { it.panelId })
        assertEquals("top-right", c.slots.single().zone)
    }

    @Test
    fun missingGcsBlockYieldsEmptyContributions() {
        val d = ExtensionDetailParser.parse(
            Json.parseToJsonElement("""{"install":{"plugin_id":"x","status":"disabled"},"manifest":{"id":"x","gcs":null}}"""),
        )!!
        assertEquals(InstallStatus.Disabled, d.status)
        assertNull(d.entrypoint)
        assertTrue(d.contributions.skills.isEmpty())
    }
}
