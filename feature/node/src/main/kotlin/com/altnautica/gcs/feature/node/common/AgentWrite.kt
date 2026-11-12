package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import androidx.annotation.StringRes
import com.altnautica.gcs.feature.node.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a write does, in operator words: the action picks the verb ("Restarting …", "Releasing …")
 * and the noun for errors; [subject] names the thing when the action has one (a service, an SSID).
 */
data class WriteLabel(val action: WriteAction, val subject: String? = null)

enum class WriteAction(@StringRes val running: Int, @StringRes val done: Int, @StringRes val noun: Int) {
    Setting(R.string.node_write_running, R.string.node_write_done, R.string.node_noun_setting),
    RestartService(R.string.node_restart_running, R.string.node_restart_done, R.string.node_noun_service),
    GnssBase(R.string.gs_write_gnss_base_running, R.string.gs_write_gnss_base_done, R.string.gs_noun_gnss_base),
    ReleaseSlot(R.string.gs_write_release_running, R.string.gs_write_release_done, R.string.gs_noun_slot),
    TxPower(R.string.gs_write_tx_power_running, R.string.gs_write_tx_power_done, R.string.gs_noun_tx_power),
    AccessPoint(R.string.gs_write_ap_running, R.string.gs_write_ap_done, R.string.gs_noun_ap),
    Join(R.string.gs_write_join_running, R.string.gs_write_join_done, R.string.gs_noun_wifi),
    Leave(R.string.gs_write_leave_running, R.string.gs_write_leave_done, R.string.gs_noun_wifi),
    Modem(R.string.gs_write_modem_running, R.string.gs_write_modem_done, R.string.gs_noun_modem),
    ShareUplink(R.string.gs_write_share_running, R.string.gs_write_share_done, R.string.gs_noun_share),
    Role(R.string.gs_write_role_running, R.string.gs_write_role_done, R.string.gs_noun_role),
    PairOpen(R.string.gs_write_pair_open_running, R.string.gs_write_pair_open_done, R.string.gs_noun_pair),
    PairClose(R.string.gs_write_pair_close_running, R.string.gs_write_pair_close_done, R.string.gs_noun_pair),
    Approve(R.string.gs_write_approve_running, R.string.gs_write_approve_done, R.string.gs_noun_approve),
    JoinMesh(R.string.gs_write_join_mesh_running, R.string.gs_write_join_mesh_done, R.string.gs_noun_join_mesh),
}

/** Progress of one operator write to an agent. */
sealed interface WriteState {
    data object Idle : WriteState
    data class Running(val label: WriteLabel) : WriteState

    /**
     * The agent refused because the vehicle is armed (`409 E_ARMED`) and
     * accepts an override: the force-confirm sheet is showing for [label].
     */
    data class ForceRequired(val label: WriteLabel) : WriteState
    data class Done(val label: WriteLabel) : WriteState
    data class Failed(val label: WriteLabel, val error: AgentError?, val message: String?) : WriteState
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

    private class Pending(
        val label: WriteLabel,
        val write: suspend (force: Boolean) -> AgentResult<*>,
        val onDone: () -> Unit,
        val beforeForce: () -> Boolean,
    )

    /**
     * Starts [write] without force. [onDone] runs after a successful write
     * (first try or forced retry), typically to reload the page. [beforeForce]
     * runs when the operator confirms the override; returning false sends
     * nothing and ends this write (the caller has taken it over, e.g. to ask
     * a further question first).
     */
    fun run(
        label: WriteLabel,
        onDone: () -> Unit = {},
        beforeForce: () -> Boolean = { true },
        write: suspend (force: Boolean) -> AgentResult<*>,
    ) {
        if (_state.value is WriteState.Running) return
        val p = Pending(label, write, onDone, beforeForce)
        pending = p
        execute(p, force = false)
    }

    fun confirmForce() {
        val p = pending ?: return
        if (_state.value !is WriteState.ForceRequired) return
        if (!p.beforeForce()) {
            pending = null
            _state.value = WriteState.Idle
            return
        }
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
