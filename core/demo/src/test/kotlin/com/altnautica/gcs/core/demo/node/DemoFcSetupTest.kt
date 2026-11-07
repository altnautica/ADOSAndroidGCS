package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemoFcSetupTest {

    private suspend fun DemoFcSetup.step(wizard: String, id: String): SetupStep {
        val o = (overview() as AgentResult.Ok).value
        return o.wizard(wizard)!!.steps.first { it.step == id }
    }

    private suspend fun DemoFcSetup.act(wizard: String, step: String, action: String) =
        assertTrue("$step/$action", action(wizard, step, action) is AgentResult.Ok)

    @Test
    fun overviewHasTheEngineStepListsWithGroundDoneAndAirAvailable() = runTest {
        val setup = DemoFcSetup(TestScope(testScheduler))
        val o = (setup.overview() as AgentResult.Ok).value
        assertEquals(DemoFcSetup.GROUND_STEPS, o.wizard("ground")!!.steps.map { it.step })
        assertEquals(DemoFcSetup.AIR_STEPS, o.wizard("air")!!.steps.map { it.step })
        assertEquals(SetupWizardState.Done, o.wizard("ground")!!.state)
        assertTrue(o.wizard("air")!!.available)
        assertEquals(false, o.needsGroundSetup)
        assertEquals("setup.accel.title", setup.step("ground", "accel").titleKey)
    }

    @Test
    fun accelWalksSixPosesToDone() = runTest {
        val setup = DemoFcSetup(this)
        setup.runStep("ground", "accel")
        assertEquals("accel_level", setup.step("ground", "accel").image)
        setup.act("ground", "accel", "start")
        advanceTimeBy(DemoFcSetup.POSE_PROMPT_MS + 1)
        for ((i, pose) in DemoFcSetup.POSES.withIndex()) {
            val s = setup.step("ground", "accel")
            assertEquals(SetupStepState.WaitingUser, s.state)
            assertEquals("accel_$pose", s.image)
            assertEquals(JsonPrimitive(pose), s.live["pose"])
            assertEquals(i / 6f, s.progress)
            setup.act("ground", "accel", "next_pose")
            advanceTimeBy(DemoFcSetup.POSE_PROMPT_MS + 1)
        }
        val done = setup.step("ground", "accel")
        assertEquals(SetupStepState.Done, done.state)
        assertEquals(JsonPrimitive(6), done.result!!["poses"])
    }

    @Test
    fun outputsAsksPositionAndDirectionPerMotorBehindThePropsOffHold() = runTest {
        val setup = DemoFcSetup(this)
        setup.runStep("ground", "outputs")
        repeat(4) {
            val s = setup.step("ground", "outputs")
            assertEquals("motor_order_quad_x", s.image)
            assertEquals(SetupConfirm.HoldPropsOff, s.actions.first { it.id == "test" }.confirm)
            setup.act("ground", "outputs", "test")
            advanceTimeBy(DemoFcSetup.MOTOR_SPIN_MS + 1)
            assertEquals(JsonPrimitive(DemoFcSetup.Q_POSITION), setup.step("ground", "outputs").live["question_key"])
            setup.act("ground", "outputs", "answer_yes")
            assertEquals(JsonPrimitive(DemoFcSetup.Q_DIRECTION), setup.step("ground", "outputs").live["question_key"])
            setup.act("ground", "outputs", if (it == 2) "answer_no" else "answer_yes")
        }
        val s = setup.step("ground", "outputs")
        assertEquals(SetupStepState.Failed, s.state)
        assertEquals(listOf("setup.blocker.swap_motor_wires"), s.blockers)
        assertNotNull(s.result)
    }

    @Test
    fun autotuneRunsToSavedGainsAndTheAirWizardMovesOn() = runTest {
        val setup = DemoFcSetup(this)
        setup.start("air")
        // preflight, first_hover and notch_air run short timed steps.
        repeat(3) { i ->
            setup.act("air", DemoFcSetup.AIR_STEPS[i], "start")
            advanceTimeBy(DemoFcSetup.GENERIC_STEP_MS + 1)
        }
        val waiting = setup.step("air", "autotune")
        assertEquals(SetupStepState.WaitingUser, waiting.state)
        assertEquals(SetupConfirm.Hold, waiting.actions.first { it.id == "abort" }.confirm)
        setup.act("air", "autotune", "start")
        advanceTimeBy(DemoFcSetup.AUTOTUNE_MS / 2)
        runCurrent()
        val mid = setup.step("air", "autotune")
        assertEquals(SetupStepState.Running, mid.state)
        assertEquals("setup.autotune.body.roll_pitch", mid.bodyKey)
        assertTrue(mid.progress!! in 0.1f..0.9f)
        advanceUntilIdle()
        val done = setup.step("air", "autotune")
        assertEquals(SetupStepState.Done, done.state)
        assertNotNull(done.result!!["gains_after"])
        assertEquals(SetupStepState.WaitingUser, setup.step("air", "verify").state)
    }
}
