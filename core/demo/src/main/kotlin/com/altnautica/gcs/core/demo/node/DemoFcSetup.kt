package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.RevertReply
import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupReport
import com.altnautica.gcs.core.agent.fcsetup.SetupRunning
import com.altnautica.gcs.core.agent.fcsetup.SetupSnapshot
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The demo drone's flight-controller setup engine, served in-process in place of the
 * agent's `/api/v1/fc-setup` routes and step stream. It follows the agent engine's step
 * views for an ArduCopter quad-X: the same step lists, title/body/action keys, images,
 * live keys, actions with their confirm tiers, blockers and results. The `ground` wizard
 * starts completed (so the `air` wizard is available); `accel` walks the six poses,
 * `outputs` tests four motors with position and direction questions, and `autotune`
 * runs a timed roll/pitch tune to its saved gains. Every change goes out on [stream] at
 * once, and running steps tick at the engine's 5 Hz.
 */
class DemoFcSetup(private val scope: CoroutineScope) : SetupBackend {

    private class WizardRun(val id: String, steps: List<String>) {
        var state = SetupWizardState.NotStarted
        var completedAt: String? = null
        val steps = steps.map { StepRun(id, it) }
    }

    private val ground = WizardRun(WIZARD_GROUND, GROUND_STEPS)
    private val air = WizardRun(WIZARD_AIR, AIR_STEPS)
    private var running: SetupRunning? = null

    /** True while [running] is a single step run from the list rather than a whole wizard. */
    private var singleStep = false

    /** The demo FC's current values of the profile parameters: off the profile where the demo readiness says so. */
    private val fcValues = COPTER_PROFILE.toMap().toMutableMap().apply { putAll(FC_OFF_PROFILE) }
    private val events = MutableSharedFlow<FcSetupEvent>(extraBufferCapacity = 64)

    init {
        // The demo aircraft finished its ground setup earlier.
        ground.state = SetupWizardState.Done
        ground.completedAt = GROUND_DONE_AT
        for (s in ground.steps) s.done(DEMO_GROUND_RESULTS[s.id].orEmpty())
        // The air wizard was under way when the agent restarted: its pre-flight check is kept,
        // and the overview offers Continue setup, which resumes at the first hover.
        air.state = SetupWizardState.Interrupted
        air.steps.first().done(mapOf("checks" to JsonPrimitive(AIR_PREFLIGHT_CHECKS)))
    }

    private fun wizardOf(id: String) = when (id) {
        WIZARD_GROUND -> ground
        WIZARD_AIR -> air
        else -> null
    }

    private fun view(w: WizardRun) = SetupWizard(
        id = w.id,
        state = w.state,
        available = w === ground || ground.state == SetupWizardState.Done,
        blockers = if (w === air && ground.state != SetupWizardState.Done) listOf("setup.blocker.ground_first") else emptyList(),
        completedAt = w.completedAt,
        steps = w.steps.map { it.view() },
    )

    override suspend fun overview(): AgentResult<FcSetupOverview> = synchronized(this) {
        AgentResult.Ok(
            FcSetupOverview(
                uid = UID,
                firmware = "ardupilot",
                firmwareVersion = FIRMWARE_VERSION,
                vehicleClass = "multirotor",
                running = running,
                wizards = listOf(view(ground), view(air)),
                report = SetupReport(
                    date = ground.completedAt,
                    firmware = "ardupilot",
                    firmwareVersion = FIRMWARE_VERSION,
                    vehicleClass = "multirotor",
                    steps = (ground.steps + air.steps).mapNotNull { s -> s.result?.let { s.id to it } }.toMap(),
                    snapshots = listOf(SetupSnapshot(SNAPSHOT, GROUND_DONE_AT)),
                ),
            ),
        )
    }

    override fun stream(): Flow<FcSetupEvent> = events.asSharedFlow()

    override suspend fun start(wizard: String): AgentResult<WizardReply> = synchronized(this) {
        val w = wizardOf(wizard) ?: return AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_wizard"))
        if (w === air && ground.state != SetupWizardState.Done) return AgentResult.Err(AgentError.Server(409, "setup.blocker.ground_first"))
        running?.let { r -> wizardOf(r.wizard)?.steps?.firstOrNull { it.id == r.step }?.ticker?.cancel() }
        w.steps.forEach { it.reset() }
        w.state = SetupWizardState.Running
        w.completedAt = null
        singleStep = false
        begin(w.steps.first())
        emitWizard(w)
        AgentResult.Ok(WizardReply(wizard = view(w)))
    }

    override suspend fun resume(wizard: String): AgentResult<WizardReply> = synchronized(this) {
        val w = wizardOf(wizard) ?: return AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_wizard"))
        if (w.state != SetupWizardState.Interrupted) return AgentResult.Err(AgentError.Server(409, "not_interrupted", "not_interrupted"))
        if (running != null) return AgentResult.Err(AgentError.Server(409, "setup.blocker.busy", "busy"))
        val next = w.steps.firstOrNull { it.state != SetupStepState.Done && it.state != SetupStepState.Skipped }
            ?: return AgentResult.Err(AgentError.Server(409, "not_interrupted", "not_interrupted"))
        w.state = SetupWizardState.Running
        singleStep = false
        // Steps the run had not reached start afresh; finished ones keep their results.
        w.steps.filter { it.state != SetupStepState.Done && it.state != SetupStepState.Skipped }.forEach { it.reset() }
        begin(next)
        emitWizard(w)
        AgentResult.Ok(WizardReply(wizard = view(w)))
    }

    override suspend fun runStep(wizard: String, step: String): AgentResult<StepReply> = synchronized(this) {
        val s = wizardOf(wizard)?.steps?.firstOrNull { it.id == step }
            ?: return AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_step"))
        // As the engine: one run at a time, a step left waiting included.
        if (running != null) return AgentResult.Err(AgentError.Server(409, "wizard_running", "wizard_running"))
        s.reset()
        singleStep = true
        begin(s)
        emitStep(s)
        AgentResult.Ok(StepReply(step = s.view()))
    }

    override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?): AgentResult<StepReply> = synchronized(this) {
        val s = wizardOf(wizard)?.steps?.firstOrNull { it.id == step }
            ?: return AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_step"))
        if (s.actions.none { it.id == action }) return AgentResult.Err(AgentError.Server(409, "setup.blocker.action_unavailable"))
        when (action) {
            "skip" -> {
                s.state = SetupStepState.Skipped
                s.actions = emptyList()
                s.result = JsonObject(mapOf("reason" to JsonPrimitive("user")))
                finished(s)
            }
            "retry" -> {
                s.reset()
                begin(s)
            }
            "cancel" -> {
                s.ticker?.cancel()
                s.fail("setup.blocker.cancelled")
            }
            else -> when (s.id) {
                "accel" -> accel(s, action)
                "outputs" -> outputs(s, action)
                "autotune" -> autotune(s, action)
                "frame" -> if (action == APPLY) {
                    s.done(args.orEmpty().filterKeys { it in FRAME_INPUTS })
                    finished(s)
                }
                "ados_profile" -> if (action == APPLY) {
                    fcValues.putAll(COPTER_PROFILE)
                    s.done(mapOf("applied" to profileRows(fcValues), "absent" to JsonArray(emptyList())))
                    finished(s)
                }
                "ados_intro" -> INTRO_MODES[action]?.let { mode ->
                    s.done(mapOf("control_mode" to JsonPrimitive(mode)))
                    finished(s)
                }
                else -> if (action == "start") timed(s)
            }
        }
        emitStep(s)
        AgentResult.Ok(StepReply(step = s.view()))
    }

    override suspend fun abort(wizard: String): AgentResult<WizardReply> = synchronized(this) {
        val w = wizardOf(wizard) ?: return AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_wizard"))
        w.steps.forEach { it.ticker?.cancel() }
        w.steps.firstOrNull { it.state == SetupStepState.Running || it.state == SetupStepState.WaitingUser }?.fail("setup.blocker.cancelled")
        if (w.state == SetupWizardState.Running) w.state = SetupWizardState.Aborted
        if (running?.wizard == wizard) running = null
        emitWizard(w)
        AgentResult.Ok(WizardReply(wizard = view(w)))
    }

    override suspend fun revert(snapshot: String): AgentResult<RevertReply> =
        if (snapshot == SNAPSHOT) AgentResult.Ok(RevertReply(restored = SNAPSHOT_PARAMS)) else AgentResult.Err(AgentError.Server(404, "setup.blocker.unknown_snapshot"))

    // ---- Steps ------------------------------------------------------------------------

    private fun begin(s: StepRun) {
        running = SetupRunning(s.wizard, s.id)
        when (s.id) {
            // The engine's copter frame inputs: a picture picker per class and arm layout, and the propulsion numbers.
            "frame" -> {
                s.args["inputs"] = frameInputs()
                s.waitUser(primary(APPLY), secondary("skip"))
            }
            "accel" -> {
                s.image = "accel_level"
                s.waitUser(primary("start"), secondary("skip"))
            }
            "outputs" -> {
                s.image = MOTOR_IMAGE
                askTest(s)
            }
            "autotune" -> s.waitUser(primary("start"), danger("abort"))
            // Lists the failsafe profile against the FC's values; nothing is written until the
            // operator applies it, and a changed reboot parameter restarts the FC.
            "ados_profile" -> {
                s.args["params"] = profileRows(fcValues)
                s.args["reboots"] = JsonPrimitive(profileReboots(fcValues))
                s.waitUser(primary(APPLY))
            }
            // The pilot choice as the engine offers it; the demo vehicle has an RC receiver.
            "ados_intro" -> {
                s.args["fallback_mode"] = JsonPrimitive("elrs")
                s.args["rc_present"] = JsonPrimitive(true)
                s.waitUser(primary("use_ados"), secondary("use_ados_no_rc"), secondary("keep_fc"))
            }
            else -> s.waitUser(primary("start"), secondary("skip"))
        }
    }

    /** Accel: start, then one "Next pose" per pose as the flight controller asks for it. */
    private fun accel(s: StepRun, action: String) {
        when (action) {
            "start", "next_pose" -> {
                if (action == "next_pose") s.pose += 1
                if (s.pose >= POSES.size) {
                    s.live.clear()
                    s.done(mapOf("poses" to JsonPrimitive(POSES.size)))
                    finished(s)
                    return
                }
                s.running()
                after(s, POSE_PROMPT_MS) {
                    val key = POSES[s.pose]
                    s.image = "accel_$key"
                    s.live["pose"] = JsonPrimitive(key)
                    s.progress = s.pose / POSES.size.toFloat()
                    s.waitUser(primary("next_pose"), secondary("cancel"))
                }
            }
        }
    }

    private fun askTest(s: StepRun) {
        val m = QUAD_X[s.motor]
        s.asking = null
        s.live.clear()
        s.live["output_kind"] = JsonPrimitive("motor")
        s.live["output_index"] = JsonPrimitive(m.seq)
        s.live["motor_number"] = JsonPrimitive(m.number)
        s.args["n"] = JsonPrimitive(m.number)
        s.live["expected_dir"] = JsonPrimitive(if (m.cw) "cw" else "ccw")
        s.progress = s.motor / QUAD_X.size.toFloat()
        s.waitUser(SetupAction("test", "setup.action.test", SetupActionStyle.Primary, SetupConfirm.HoldPropsOff), secondary("cancel"))
    }

    private fun ask(s: StepRun, question: String) {
        s.asking = question
        s.live["question_key"] = JsonPrimitive(question)
        s.waitUser(primary("answer_yes"), secondary("answer_no"))
    }

    /** Outputs: per motor in test order, a 2 s spin, then "position correct?" and "direction correct?". */
    private fun outputs(s: StepRun, action: String) {
        val m = QUAD_X[s.motor]
        when (action) {
            "test" -> {
                s.running()
                after(s, MOTOR_SPIN_MS) { ask(s, Q_POSITION) }
            }
            "answer_yes", "answer_no" -> {
                val yes = action == "answer_yes"
                when (s.asking) {
                    Q_POSITION -> {
                        if (!yes) s.wrongPosition += m.number
                        ask(s, Q_DIRECTION)
                    }
                    Q_DIRECTION -> {
                        if (!yes) s.swapWires += m.number
                        s.motor += 1
                        if (s.motor < QUAD_X.size) {
                            askTest(s)
                        } else {
                            completeOutputs(s)
                        }
                    }
                }
            }
        }
    }

    private fun completeOutputs(s: StepRun) {
        s.live.clear()
        s.args.clear()
        val result = mapOf(
            "tested" to JsonPrimitive(QUAD_X.size),
            "reversed_dshot" to JsonArray(emptyList()),
            "reversed_servo" to JsonArray(emptyList()),
            "swap_wires" to JsonArray(s.swapWires.map { JsonPrimitive(it) }),
            "wrong_position" to JsonArray(s.wrongPosition.map { JsonPrimitive(it) }),
        )
        when {
            s.wrongPosition.isNotEmpty() -> {
                s.result = JsonObject(result)
                s.fail("setup.blocker.motor_position")
            }
            s.swapWires.isNotEmpty() -> {
                s.result = JsonObject(result)
                s.fail("setup.blocker.swap_motor_wires")
            }
            else -> {
                s.done(result)
                finished(s)
            }
        }
    }

    /** Autotune: a roll/pitch round with progress and the tune's status lines, then land to save. */
    private fun autotune(s: StepRun, action: String) {
        when (action) {
            "start" -> {
                s.running()
                s.actions = listOf(danger("abort"))
                s.variant = "roll_pitch"
                s.progress = 0.1f
                s.ticker = scope.launch {
                    var t = 0L
                    while (t < AUTOTUNE_MS) {
                        delay(TICK_MS)
                        t += TICK_MS
                        synchronized(this@DemoFcSetup) {
                            val f = t / AUTOTUNE_MS.toFloat()
                            s.progress = 0.1f + 0.8f * f
                            s.live["phase"] = JsonPrimitive(if (f < 0.5f) "roll" else "pitch")
                            s.live["last_text"] = JsonPrimitive(if (f < 0.5f) "AutoTune: Twitch" else "AutoTune: Pitch Success")
                            if (f >= LANDING_AT) s.variant = "landing_to_save"
                            emitStep(s)
                        }
                    }
                    synchronized(this@DemoFcSetup) {
                        s.variant = null
                        s.live.clear()
                        s.done(autotuneResult())
                        finished(s)
                        emitStep(s)
                    }
                }
            }
            "abort" -> {
                s.ticker?.cancel()
                s.variant = null
                s.fail("setup.blocker.cancelled")
            }
        }
    }

    /** Any other step: a short run to done. */
    private fun timed(s: StepRun) {
        s.running()
        s.progress = 0f
        s.ticker = scope.launch {
            var t = 0L
            while (t < GENERIC_STEP_MS) {
                delay(TICK_MS)
                t += TICK_MS
                synchronized(this@DemoFcSetup) {
                    s.progress = t / GENERIC_STEP_MS.toFloat()
                    emitStep(s)
                }
            }
            synchronized(this@DemoFcSetup) {
                s.done(emptyMap())
                finished(s)
                emitStep(s)
            }
        }
    }

    /** Runs [block] on [s] after [ms] and publishes the step. */
    private fun after(s: StepRun, ms: Long, block: () -> Unit) {
        s.ticker = scope.launch {
            delay(ms)
            synchronized(this@DemoFcSetup) {
                block()
                emitStep(s)
            }
        }
    }

    /** A step reached a terminal state: a whole-wizard run moves on to the next step or completes. */
    private fun finished(s: StepRun) {
        val w = wizardOf(s.wizard) ?: return
        if (singleStep || w.state != SetupWizardState.Running) {
            running = null
            return
        }
        val next = w.steps.firstOrNull { it.state == SetupStepState.Pending }
        if (next != null) {
            begin(next)
            emitStep(next)
        } else {
            w.state = SetupWizardState.Done
            w.completedAt = if (w === ground) GROUND_DONE_AT else AIR_DONE_AT
            running = null
            emitWizard(w)
        }
    }

    private fun emitStep(s: StepRun) {
        events.tryEmit(FcSetupEvent.Step(System.currentTimeMillis(), s.view()))
    }

    private fun emitWizard(w: WizardRun) {
        events.tryEmit(FcSetupEvent.Wizard(System.currentTimeMillis(), view(w)))
    }

    internal companion object {
        const val WIZARD_GROUND = "ground"
        const val WIZARD_AIR = "air"
        const val APPLY = "apply"

        /** The control mode each `ados_intro` choice sets (the demo vehicle has an RC receiver). */
        val INTRO_MODES = mapOf("use_ados" to "ados", "use_ados_no_rc" to "ados", "keep_fc" to "elrs")

        /**
         * The ArduCopter profile the `ados_profile` step writes on apply: failsafes, then the
         * flight controller's proximity-stop backstop inside a 2 m pilot margin, path planning off.
         */
        val COPTER_PROFILE = listOf(
            "MAV_GCS_SYSID" to 1.0, "FS_GCS_ENABLE" to 1.0, "FS_GCS_TIMEOUT" to 3.0, "GUID_TIMEOUT" to 1.0,
            "RC_OVERRIDE_TIME" to 1.0, "BATT_FS_LOW_ACT" to 2.0, "BATT_FS_CRT_ACT" to 1.0,
            "AVOID_ENABLE" to 2.0, "AVOID_MARGIN" to 1.3, "AVOID_BEHAVE" to 1.0, "PRX1_TYPE" to 2.0, "OA_TYPE" to 0.0,
        )

        /** Where the demo FC starts off the profile (`DemoPilotWire.FC_PROFILE_DIFFERS`). */
        val FC_OFF_PROFILE = mapOf("MAV_GCS_SYSID" to 255.0, "FS_GCS_ENABLE" to 0.0, "PRX1_TYPE" to 0.0)

        /** The engine's ArduCopter step lists. */
        val GROUND_STEPS = listOf(
            "detect", "frame", "accel", "level", "compass", "radio", "rc_ados_switch", "outputs", "battery",
            "initial_params", "notch_static", "ados_profile", "ados_intro",
        )
        val AIR_STEPS = listOf("preflight", "first_hover", "notch_air", "autotune", "verify")

        /** The pre-flight checks the interrupted air wizard had passed. */
        private const val AIR_PREFLIGHT_CHECKS = 6

        /** ArduPilot's ACCELCAL_VEHICLE_POS order, as image keys. */
        val POSES = listOf("level", "left", "right", "nose_down", "nose_up", "back")

        const val MOTOR_IMAGE = "motor_order_quad_x"

        const val Q_POSITION = "setup.question.motor_position"
        const val Q_DIRECTION = "setup.question.motor_direction"

        const val UID = "demo-fc-0001"
        const val FIRMWARE_VERSION = "4.6.2"
        const val GROUND_DONE_AT = "2026-01-01T09:00:00Z"
        const val AIR_DONE_AT = "2026-01-01T10:00:00Z"
        const val SNAPSHOT = "demo-fc-0001/2026-01-01T08:55:00Z.param"
        const val SNAPSHOT_PARAMS = 912

        const val TICK_MS = 200L
        const val POSE_PROMPT_MS = 600L
        const val MOTOR_SPIN_MS = 2_000L
        const val GENERIC_STEP_MS = 1_600L
        const val AUTOTUNE_MS = 20_000L
        const val LANDING_AT = 0.9f
    }
}
