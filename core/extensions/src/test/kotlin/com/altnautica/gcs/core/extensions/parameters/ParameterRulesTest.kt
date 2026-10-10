package com.altnautica.gcs.core.extensions.parameters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterRulesTest {

    private fun parse(json: String) = ParameterParser.parse(Json.parseToJsonElement(json))

    @Test
    fun visibleIfFollowsTheReferencedValue() {
        val params = parse(
            """[
              {"key":"mode","schema":{"type":"string","enum":["auto","manual"],"default":"auto"}},
              {"key":"gain","schema":{"type":"number","default":1},"ui":{"visible_if":{"key":"mode","equals":"manual"}}}
            ]""",
        )
        val gain = params.single { it.key == "gain" }
        assertFalse(ParameterRules.isVisible(gain, mapOf("mode" to JsonPrimitive("auto"))))
        assertTrue(ParameterRules.isVisible(gain, mapOf("mode" to JsonPrimitive("manual"))))
        // Type matters: a number never equals the string "1".
        val strict = gain.copy(ui = gain.ui!!.copy(visibleIf = VisibleIf("mode", JsonPrimitive(1))))
        assertFalse(ParameterRules.isVisible(strict, mapOf("mode" to JsonPrimitive("1"))))
    }

    @Test
    fun cameraSelectorBecomesCameraWidget() {
        val p = parse("""[{"key":"cam","control":"camera-selector","label":"Camera","purpose":"detect","binding":"agent.config"}]""").single()
        assertEquals(ParameterWidget.Camera, ParameterRules.inferWidget(p.schema, p.ui))
        assertEquals(ParameterType.Str, p.schema.type)
        assertEquals(JsonPrimitive("auto"), ParameterRules.defaultFor(p.schema))
        assertEquals("detect", p.ui?.purpose)
        assertEquals("Camera", p.ui?.label)
        assertEquals(ParameterBinding.PluginConfig, p.binding)
    }

    @Test
    fun malformedEntriesAreDropped() {
        val params = parse(
            """[
              {"key":"a","schema":{"type":"number","minimum":5,"maximum":1}},
              {"key":"b","schema":{"type":"number","step":0}},
              {"key":"c","schema":{"type":"string","pattern":"("}},
              {"key":"d","schema":{"type":"integer","minimum":0,"maximum":10,"default":20}},
              {"key":"e","schema":{"type":"object"}},
              {"schema":{"type":"boolean"}},
              {"key":"ok","schema":{"type":"boolean"},"binding":"made.up"},
              {"key":"ok","schema":{"type":"number"}}
            ]""",
        )
        assertEquals(listOf("ok"), params.map { it.key })
        assertEquals(ParameterBinding.PluginConfig, params.single().binding)
        assertEquals(ParameterType.Boolean, params.single().schema.type)
    }

    @Test
    fun validateAndClamp() {
        val s = ParameterSchema(ParameterType.Number, minimum = 3.0, maximum = 30.0, step = 0.5)
        assertTrue(ParameterRules.validateValue(s, JsonPrimitive(8.25)).ok) // step never rejects
        assertFalse(ParameterRules.validateValue(s, JsonPrimitive(31)).ok)
        assertFalse(ParameterRules.validateValue(s, JsonPrimitive("8")).ok)
        assertEquals(JsonPrimitive(8.5), ParameterRules.clamp(s, JsonPrimitive(8.3)))
        assertEquals(JsonPrimitive(30.0), ParameterRules.clamp(s, JsonPrimitive(99)))

        val i = ParameterSchema(ParameterType.Integer, minimum = 0.0, maximum = 10.0)
        assertFalse(ParameterRules.validateValue(i, JsonPrimitive(1.5)).ok)
        assertEquals(JsonPrimitive(2L), ParameterRules.clamp(i, JsonPrimitive(1.6)))

        val e = ParameterSchema(ParameterType.Str, enum = listOf(JsonPrimitive("a"), JsonPrimitive(2)))
        assertTrue(ParameterRules.validateValue(e, JsonPrimitive(2)).ok)
        assertFalse(ParameterRules.validateValue(e, JsonPrimitive("2")).ok)
        assertEquals(ParameterWidget.Enum, ParameterRules.inferWidget(e, null))

        val p = ParameterSchema(ParameterType.Str, pattern = "^[a-z]+$")
        assertTrue(ParameterRules.validateValue(p, JsonPrimitive("abc")).ok)
        assertFalse(ParameterRules.validateValue(p, JsonPrimitive("ABC")).ok)
    }

    @Test
    fun bitmaskLabelsKeepFirstOfEachBit() {
        val p = parse(
            """[{"key":"m","schema":{"type":"integer"},"ui":{"widget":"bitmask",
              "bits":[{"bit":0,"label":"A"},{"bit":0,"label":"dup"},{"bit":-1,"label":"neg"},{"bit":3,"label":"D"}]}}]""",
        ).single()
        assertEquals(listOf(0 to "A", 3 to "D"), p.ui!!.bits!!.map { it.bit to it.label })
        assertEquals(ParameterWidget.Bitmask, ParameterRules.inferWidget(p.schema, p.ui))
    }

    @Test
    fun groupingKeepsFirstAppearanceAndOrder() {
        val params = parse(
            """[
              {"key":"b","schema":{"type":"boolean"},"ui":{"group":"g1","order":20}},
              {"key":"x","schema":{"type":"boolean"},"ui":{"group":"g2"}},
              {"key":"a","schema":{"type":"boolean"},"ui":{"group":"g1","order":10}}
            ]""",
        )
        val groups = ExtensionParametersViewModel.group(params)
        assertEquals(listOf("g1", "g2"), groups.map { it.title })
        assertEquals(listOf("a", "b"), groups[0].items.map { it.key })
    }
}
