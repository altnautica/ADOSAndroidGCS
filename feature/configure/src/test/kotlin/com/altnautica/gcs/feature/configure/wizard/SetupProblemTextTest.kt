package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.http.AgentErrorParser
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.DirectorySpecSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** The engine's bare error codes read as their `setup.err.<code>` spec text. */
class SetupProblemTextTest {
    private val spec = CockpitSpecRepository(DirectorySpecSource(File("../cockpit/src/main/assets/cockpit-spec")))
    private val text = SetupText { key, args -> spec.setupStrings.format(key, args) }

    private fun problemFor(body: String) = AgentErrorParser.parse(409, body).setupProblem()

    @Test
    fun bareCodeRendersItsSpecText() {
        val problem = problemFor("""{"ok":false,"err":"not_running"}""")
        assertEquals("setup.err.not_running", problem.key)
        assertEquals(spec.setupStrings.strings.getValue("setup.err.not_running"), text.problemText(problem))
    }

    @Test
    fun everySpecErrorKeyRenders() {
        val keys = spec.setupStrings.strings.keys.filter { it.startsWith("setup.err.") }
        for (key in keys) {
            val problem = problemFor("""{"ok":false,"err":"${key.removePrefix("setup.err.")}"}""")
            assertEquals(key, spec.setupStrings.strings.getValue(key), text.problemText(problem))
        }
    }

    @Test
    fun fullKeyIsKept() {
        assertEquals("setup.err.armed", problemFor("""{"ok":false,"err":"setup.err.armed"}""").key)
    }

    @Test
    fun unknownCodeFallsBackToLocalText() {
        assertNull(text.problemText(problemFor("""{"ok":false,"err":"no_such_code_here"}""")))
    }
}
