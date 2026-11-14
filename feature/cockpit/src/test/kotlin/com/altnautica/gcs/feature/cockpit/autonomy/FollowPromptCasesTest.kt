package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The waiting-for-instructions prompt against the shared table (`follow-prompt-cases.json`,
 * the byte copy every surface tests against), and its actions' transports.
 */
class FollowPromptCasesTest {
    private val cases: List<JsonObject> =
        Json.parseToJsonElement(File("../../core/skills/src/test/resources/fixtures/follow-prompt-cases.json").readText()).jsonArray.map { it.jsonObject }

    private val strings = testSpec.setupStrings.strings

    @Test
    fun `every shared case yields the expected prompt`() {
        assertEquals(6, cases.size)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val state = requireNotNull(AutonomyParse.follow(c.getValue("state").jsonObject)) { name }
            val prompt = FollowPrompts.of(state)
            val expect = c.getValue("expect")
            if (expect is JsonNull) {
                assertNull(name, prompt)
                continue
            }
            val e = expect.jsonObject
            assertEquals(name, e.getValue("titleKey").jsonPrimitive.content, prompt?.titleKey)
            assertEquals(name, e.getValue("bodyKey").jsonPrimitive.content, prompt?.bodyKey)
            assertEquals(name, e.getValue("actions").jsonArray.map { it.jsonPrimitive.content }, prompt?.actions?.map { it.wire })
        }
    }

    @Test
    fun `the spec words every title, body, action and phase the prompt and chrome show`() {
        for (c in cases) {
            val e = c.getValue("expect") as? JsonObject ?: continue
            assertTrue(strings[e.getValue("titleKey").jsonPrimitive.content].orEmpty().isNotBlank())
            assertTrue(strings[e.getValue("bodyKey").jsonPrimitive.content].orEmpty().isNotBlank())
        }
        for (a in FollowPromptAction.entries) assertTrue(a.labelKey, strings[a.labelKey].orEmpty().isNotBlank())
        for (p in FollowPhase.entries) assertTrue(p.wordKey, strings[p.wordKey].orEmpty().isNotBlank())
        for (v in listOf("enroute", "arrived", "no-vantage")) assertTrue(v, strings["follow.vantage.$v"].orEmpty().isNotBlank())
        for (k in listOf("narrow", "wide")) assertTrue(k, strings["follow.occluder.$k"].orEmpty().isNotBlank())
        assertEquals("Target lost: holding, waiting for instructions", strings[FollowPrompts.TITLE_KEY])
    }

    private class Recorder : FollowActionTransport {
        val writes = mutableListOf<Pair<String, JsonPrimitive>>()
        val pilot = mutableListOf<String>()
        var picks = 0
        var accept = true

        override suspend fun writeFollowConfig(key: String, value: JsonPrimitive): Boolean {
            writes += key to value
            return accept
        }

        override fun pilot(action: String) {
            pilot += action
        }

        override fun startPick() {
            picks++
        }
    }

    @Test
    fun `each action goes through its own transport`() = runTest {
        val t = Recorder()
        assertTrue(runFollowAction(FollowPromptAction.Resume, 3, t))
        assertTrue(runFollowAction(FollowPromptAction.Stop, 3, t))
        assertEquals(listOf("operator_resume_seq" to JsonPrimitive(4), "active" to JsonPrimitive(false)), t.writes)
        assertTrue(runFollowAction(FollowPromptAction.Rth, 3, t))
        assertTrue(runFollowAction(FollowPromptAction.Land, 3, t))
        assertEquals(listOf("rth", "land"), t.pilot)
        assertTrue(runFollowAction(FollowPromptAction.Redesignate, 3, t))
        assertEquals(1, t.picks)
        t.accept = false
        assertEquals(false, runFollowAction(FollowPromptAction.Resume, 3, t))
    }

    @Test
    fun `a bare key runs only an offered action`() {
        val all = FollowPromptAction.entries
        val noResume = all - FollowPromptAction.Resume
        assertEquals(FollowPromptAction.Resume, FollowPrompts.actionForKey('r', false, all))
        assertEquals(FollowPromptAction.Redesignate, FollowPrompts.actionForKey('P', false, all))
        assertEquals(FollowPromptAction.Rth, FollowPrompts.actionForKey('h', false, all))
        assertEquals(FollowPromptAction.Land, FollowPrompts.actionForKey('l', false, all))
        assertEquals(FollowPromptAction.Stop, FollowPrompts.actionForKey('x', false, all))
        assertNull(FollowPrompts.actionForKey('r', false, noResume))
        assertNull(FollowPrompts.actionForKey('h', true, all))
        assertNull(FollowPrompts.actionForKey('q', false, all))
        assertNotEquals(FollowPrompts.actionForKey('x', false, all), FollowPrompts.actionForKey('l', false, all))
    }
}
