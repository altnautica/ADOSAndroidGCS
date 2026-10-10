package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `E_ARMED` force-confirm sheet flow. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentWriteRunnerTest {

    @Test
    fun armedRefusalAsksThenRetriesWithForce() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        val forces = mutableListOf<Boolean>()
        var reloaded = 0
        runner.run("channel", onDone = { reloaded++ }) { force ->
            forces += force
            if (force) AgentResult.Ok(JsonPrimitive("ok")) else AgentResult.Err(AgentError.Armed(canForce = true))
        }
        assertEquals(WriteState.ForceRequired("channel"), runner.state.value)
        assertEquals(0, reloaded)

        runner.confirmForce()
        assertEquals(listOf(false, true), forces)
        assertEquals(WriteState.Done("channel"), runner.state.value)
        assertEquals(1, reloaded)
    }

    @Test
    fun cancellingTheSheetSendsNothingMore() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        var calls = 0
        runner.run("role") {
            calls++
            AgentResult.Err(AgentError.Armed(canForce = true))
        }
        runner.cancelForce()
        runner.confirmForce()
        assertEquals(1, calls)
        assertEquals(WriteState.Idle, runner.state.value)
    }

    @Test
    fun armedWithoutOverrideFails() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        runner.run("x") { AgentResult.Err(AgentError.Armed(canForce = false)) }
        val s = runner.state.value
        assertTrue(s is WriteState.Failed && s.error == AgentError.Armed(false))
    }

    @Test
    fun refusalInA200BodyFails() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        runner.run("agent.name") { AgentResult.Ok(buildJsonObject { put("error", "Key not found: agent.nme") }) }
        assertEquals(WriteState.Failed("agent.name", null, "Key not found: agent.nme"), runner.state.value)

        runner.run("svc") { AgentResult.Ok(buildJsonObject { put("status", "error"); put("message", "Unknown service: x") }) }
        assertEquals(WriteState.Failed("svc", null, "Unknown service: x"), runner.state.value)
    }
}
