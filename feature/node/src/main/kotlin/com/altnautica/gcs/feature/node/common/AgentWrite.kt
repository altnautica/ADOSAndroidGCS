package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of one operator write to an agent. */
sealed interface WriteState {
    data object Idle : WriteState
    data class Running(val label: String) : WriteState

    /**
     * The agent refused because the vehicle is armed (`409 E_ARMED`) and
     * accepts an override: the force-confirm sheet is showing for [label].
     */
    data class ForceRequired(val label: String) : WriteState
    data class Done(val label: String) : WriteState
    data class Failed(val label: String, val error: AgentError?, val message: String?) : WriteState
}

/**
 * Runs agent writes one at a time and owns the armed-override flow: a write
 * refused with `E_ARMED` (override allowed) parks as [WriteState.ForceRequired]
 * until the operator confirms ([confirmForce]) or backs out ([cancelForce]);
 * the confirmed retry is the same write with `force = true`.
 */
class AgentWriteRunner(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<WriteState>(WriteState.Idle)
    val state: StateFlow<WriteState> = _state.asStateFlow()

    private var pending: Pending? = null

    private class Pending(val label: String, val write: suspend (force: Boolean) -> AgentResult<*>, val onDone: () -> Unit)

    /**
     * Starts [write] without force. [onDone] runs after a successful write
     * (first try or forced retry), typically to reload the page.
     */
    fun run(label: String, onDone: () -> Unit = {}, write: suspend (force: Boolean) -> AgentResult<*>) {
        if (_state.value is WriteState.Running) return
        val p = Pending(label, write, onDone)
        pending = p
        execute(p, force = false)
    }

    fun confirmForce() {
        val p = pending ?: return
        if (_state.value !is WriteState.ForceRequired) return
        execute(p, force = true)
    }

    fun cancelForce() {
        if (_state.value is WriteState.ForceRequired) {
            pending = null
            _state.value = WriteState.Idle
        }
    }

    fun dismiss() {
        if (_state.value is WriteState.Done || _state.value is WriteState.Failed) _state.value = WriteState.Idle
    }

    private fun execute(p: Pending, force: Boolean) {
        _state.value = WriteState.Running(p.label)
        scope.launch {
            _state.value = when (val r = p.write(force)) {
                is AgentResult.Ok -> {
                    val refusal = bodyError(r.value)
                    if (refusal != null) {
                        WriteState.Failed(p.label, null, refusal)
                    } else {
                        pending = null
                        p.onDone()
                        WriteState.Done(p.label)
                    }
                }
                is AgentResult.Err -> {
                    val e = r.error
                    if (e is AgentError.Armed && e.canForce && !force) {
                        WriteState.ForceRequired(p.label)
                    } else {
                        pending = null
                        WriteState.Failed(p.label, e, null)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Some agent writes answer 200 with a refusal in the body: config
         * writes (`{"error": "Key not found: …"}`) and service restarts
         * (`{"status": "error", "message": …}`).
         */
        fun bodyError(body: Any?): String? {
            val obj = body as? kotlinx.serialization.json.JsonObject ?: return null
            obj.str("error")?.let { return it }
            if (obj.str("status") == "error") return obj.str("message") ?: "error"
            return null
        }
    }
}
