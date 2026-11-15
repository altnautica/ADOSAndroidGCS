package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One step of a [DemoFcSetup] wizard as the demo setup engine runs it. */
internal class StepRun(val wizard: String, val id: String) {
    var state = SetupStepState.Pending
    var progress: Float? = null
    var image: String? = null
    var variant: String? = null

    /** Interpolation values for the step's strings (`{n}` in the motor questions). */
    val args = LinkedHashMap<String, JsonElement>()
    val live = LinkedHashMap<String, JsonElement>()
    var actions: List<SetupAction> = emptyList()
    var blockers: List<String> = emptyList()
    var result: JsonObject? = null

    // Step-specific progress.
    var pose = 0
    var motor = 0
    var asking: String? = null
    val wrongPosition = ArrayList<Int>()
    val swapWires = ArrayList<Int>()
    var ticker: Job? = null

    fun view() = SetupStep(
        wizard = wizard,
        step = id,
        titleKey = "setup.$id.title",
        bodyKey = variant?.let { "setup.$id.body.$it" } ?: "setup.$id.body",
        args = JsonObject(LinkedHashMap(args)),
        image = image,
        state = state,
        progress = progress,
        live = JsonObject(LinkedHashMap(live)),
        actions = actions,
        blockers = blockers,
        result = result,
    )

    fun reset() {
        ticker?.cancel()
        state = SetupStepState.Pending
        progress = null
        image = null
        variant = null
        args.clear()
        live.clear()
        actions = emptyList()
        blockers = emptyList()
        result = null
        pose = 0
        motor = 0
        asking = null
        wrongPosition.clear()
        swapWires.clear()
    }

    fun waitUser(vararg a: SetupAction) {
        state = SetupStepState.WaitingUser
        actions = a.toList()
    }

    fun running() {
        state = SetupStepState.Running
        actions = emptyList()
    }

    fun done(r: Map<String, JsonElement>) {
        state = SetupStepState.Done
        progress = 1f
        actions = emptyList()
        blockers = emptyList()
        result = JsonObject(r)
    }

    fun fail(blocker: String) {
        state = SetupStepState.Failed
        actions = listOf(primary("retry"))
        blockers = listOf(blocker)
    }
}

internal fun primary(id: String) = SetupAction(id, "setup.action.$id", SetupActionStyle.Primary, SetupConfirm.None)
internal fun secondary(id: String) = SetupAction(id, "setup.action.$id", SetupActionStyle.Secondary, SetupConfirm.None)
internal fun danger(id: String) = SetupAction(id, "setup.action.$id", SetupActionStyle.Danger, SetupConfirm.Hold)
