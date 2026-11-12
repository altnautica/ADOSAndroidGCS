package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.http.AgentErrorParser
import com.altnautica.gcs.feature.configure.R
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

    @Test
    fun missingSnapshotReadsItsSpecTextNotAnAbsentFeature() {
        val problem = AgentErrorParser.parse(404, """{"ok":false,"err":"no_snapshot"}""").setupProblem()
        assertEquals("setup.err.no_snapshot", problem.key)
        assertEquals(spec.setupStrings.strings.getValue("setup.err.no_snapshot"), text.problemText(problem))
    }

    @Test
    fun unknownEngineCodeReadsAsARefusalNamingTheCode() {
        val problem = AgentErrorParser.parse(422, """{"ok":false,"err":"unknown_action"}""").setupProblem()
        assertNull(text.problemText(problem))
        assertEquals(SetupLocalProblem.Refused, problem.local)
        assertEquals(R.string.cfg_setup_err_refused_code, problem.localText())
        assertEquals("unknown_action", problem.engineCode)
    }

    @Test
    fun refusalWithoutACodeIsNotALinkFailure() {
        val problem = AgentErrorParser.parse(409, """{"ok":false}""").setupProblem()
        assertEquals(R.string.cfg_setup_err_refused, problem.localText())
    }

    @Test
    fun unavailableSetupEngineIsNotALinkFailure() {
        for (body in listOf("""{"ok":false,"err":"setup_unavailable"}""", """{"detail":"ADOS pilot not reachable"}""")) {
            val problem = AgentErrorParser.parse(503, body).setupProblem()
            assertNull(body, text.problemText(problem))
            assertEquals(body, R.string.cfg_setup_err_engine_down, problem.localText())
        }
    }

    @Test
    fun transportFailureStillReadsAsUnreachable() {
        val problem = AgentErrorParser.parse(500, "<html>").setupProblem()
        assertEquals(R.string.cfg_setup_err_unreachable, problem.localText())
    }
}
