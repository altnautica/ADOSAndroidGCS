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
    private val L_CH = WriteLabel(WriteAction.TxPower)
    private val L_ROLE = WriteLabel(WriteAction.Role, "relay")
    private val L_X = WriteLabel(WriteAction.Setting, "x")
    private val L_NAME = WriteLabel(WriteAction.Setting, "agent.name")
    private val L_SVC = WriteLabel(WriteAction.RestartService, "svc")

    @Test
    fun armedRefusalAsksThenRetriesWithForce() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        val forces = mutableListOf<Boolean>()
        var reloaded = 0
        runner.run(L_CH, onDone = { reloaded++ }) { force ->
            forces += force
            if (force) AgentResult.Ok(JsonPrimitive("ok")) else AgentResult.Err(AgentError.Armed(canForce = true))
        }
        assertEquals(WriteState.ForceRequired(L_CH), runner.state.value)
        assertEquals(0, reloaded)

        runner.confirmForce()
        assertEquals(listOf(false, true), forces)
        assertEquals(WriteState.Done(L_CH), runner.state.value)
        assertEquals(1, reloaded)
    }

    @Test
    fun cancellingTheSheetSendsNothingMore() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        var calls = 0
        runner.run(L_ROLE) {
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
        runner.run(L_X) { AgentResult.Err(AgentError.Armed(canForce = false)) }
        val s = runner.state.value
        assertTrue(s is WriteState.Failed && s.error == AgentError.Armed(false))
    }

    @Test
    fun refusalInA200BodyFails() = runTest(UnconfinedTestDispatcher()) {
        val runner = AgentWriteRunner(this)
        runner.run(L_NAME) { AgentResult.Ok(buildJsonObject { put("error", "Key not found: agent.nme") }) }
        assertEquals(WriteState.Failed(L_NAME, null, "Key not found: agent.nme"), runner.state.value)

        runner.run(L_SVC) { AgentResult.Ok(buildJsonObject { put("status", "error"); put("message", "Unknown service: x") }) }
        assertEquals(WriteState.Failed(L_SVC, null, "Unknown service: x"), runner.state.value)
    }
}
