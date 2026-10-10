package com.altnautica.gcs.feature.node.groundstation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.groundstation.ApUpdate
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.fleet.data.NodeRepository
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.common.AgentWriteRunner
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.common.WriteState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val problem: PageProblem) : Load<Nothing>
}

/** A Wi-Fi join that needs the access point stood down first (`409 E_WLAN0_BUSY_AP_ACTIVE`). */
data class PendingJoin(val ssid: String, val passphrase: String?)

data class GroundStationUiState(
    val carried: Load<CarriedDrones> = Load.Loading,
    val radio: Load<RadioLink> = Load.Loading,
    val network: Load<StationNetwork> = Load.Loading,
    val scan: Load<List<ScannedNetwork>>? = null,
    val joinNeedsForce: PendingJoin? = null,
    val mesh: Load<MeshState> = Load.Loading,
    /** Receiver-only pairing window; null on other roles. */
    val pairWindow: PairWindow? = null,
)

/**
 * The ground-station tabs (Carried drones, Radio, Network, Mesh). Only the
 * visible tab polls; writes go through one runner with the armed override.
 */
@HiltViewModel(assistedFactory = GroundStationViewModel.Factory::class)
class GroundStationViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    private val nodes: NodeRepository,
    private val clients: NodeClients,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): GroundStationViewModel
    }

    private val id = DeviceId(nodeId)
    private val _state = MutableStateFlow(GroundStationUiState())
    val state: StateFlow<GroundStationUiState> = _state.asStateFlow()

    val writes = AgentWriteRunner(viewModelScope)
    val writeState: StateFlow<WriteState> get() = writes.state

    private var pollJob: Job? = null
    private var activeTab: NodeTab? = null

    /** Starts polling [tab]'s data (stops the previous tab's poll); null stops everything. */
    fun show(tab: NodeTab?) {
        if (tab == activeTab && pollJob?.isActive == true) return
        activeTab = tab
        pollJob?.cancel()
        val loader: (suspend (AgentClient) -> Unit) = when (tab) {
            NodeTab.CarriedDrones -> ::loadCarried
            NodeTab.Radio -> ::loadRadio
            NodeTab.Network -> ::loadNetwork
            NodeTab.Mesh -> ::loadMesh
            else -> return
        }
        pollJob = viewModelScope.launch {
            while (isActive) {
                val client = client()
                if (client == null) {
                    fail(tab, PageProblem.NoAgentLink)
                } else {
                    loader(client)
                }
                delay(POLL_MS)
            }
        }
    }

    private fun refresh() {
        val tab = activeTab
        activeTab = null
        show(tab)
    }

    // ---- carried drones ------------------------------------------------------

    private suspend fun loadCarried(client: AgentClient) {
        val pair = client.requestJson("GET", "api/wfb/pair")
        if (pair is AgentResult.Err) return fail(NodeTab.CarriedDrones, PageProblem.from(pair.error))
        val relayed = client.requestJson("GET", "api/v1/ground-station/relayed/status").getOrNull() as? JsonObject
        val status = client.requestJson("GET", "api/v1/ground-station/status").getOrNull() as? JsonObject
        _state.update { it.copy(carried = Load.Ready(CarriedDrones.parse(pair.getOrNull() as? JsonObject, relayed, status))) }
    }

    /** Frees the drone's fleet slot on this ground station. */
    fun releaseSlot(deviceId: String) = write(deviceId) { c, _ ->
        c.requestJson("DELETE", "api/v1/ground-station/wfb/pair/${java.net.URLEncoder.encode(deviceId, "UTF-8").replace("+", "%20")}")
    }

    // ---- radio ---------------------------------------------------------------

    private suspend fun loadRadio(client: AgentClient) {
        val status = client.requestJson("GET", "api/v1/ground-station/status")
        if (status is AgentResult.Err) return fail(NodeTab.Radio, PageProblem.from(status.error))
        val wfb = client.requestJson("GET", "api/v1/ground-station/wfb").getOrNull() as? JsonObject
        val obj = status.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
        _state.update { it.copy(radio = Load.Ready(RadioLink.parse(obj, wfb))) }
    }

    fun setChannel(channel: Int) = write("channel") { c, force -> c.groundStation.putWfb(channel, force) }

    fun setTxPower(dbm: Int) = write("tx_power") { c, force ->
        c.requestJson(
            "PUT",
            "api/wfb/tx-power",
            buildJsonObject {
                put("tx_power_dbm", dbm)
                if (force) put("force", true)
            },
        )
    }

    // ---- network -------------------------------------------------------------

    private suspend fun loadNetwork(client: AgentClient) {
        when (val r = client.requestJson("GET", "api/v1/ground-station/network")) {
            is AgentResult.Ok -> _state.update { it.copy(network = Load.Ready(StationNetwork.parse(r.value as? JsonObject ?: JsonObject(emptyMap())))) }
            is AgentResult.Err -> fail(NodeTab.Network, PageProblem.from(r.error))
        }
    }

    fun saveAccessPoint(enabled: Boolean?, ssid: String?, passphrase: String?, channel: Int?) = write("ap") { c, _ ->
        c.groundStation.putNetworkAp(ApUpdate(enabled, ssid?.takeIf { it.isNotBlank() }, passphrase?.takeIf { it.isNotEmpty() }, channel))
    }

    fun scanNetworks() {
        _state.update { it.copy(scan = Load.Loading) }
        viewModelScope.launch {
            val client = client() ?: return@launch _state.update { it.copy(scan = Load.Failed(PageProblem.NoAgentLink)) }
            _state.update {
                it.copy(
                    scan = when (val r = client.groundStation.scanClientNetworks()) {
                        is AgentResult.Ok -> Load.Ready(ScannedNetwork.parse(r.value as? JsonObject ?: JsonObject(emptyMap())))
                        is AgentResult.Err -> Load.Failed(PageProblem.from(r.error))
                    },
                )
            }
        }
    }

    /** Joins a Wi-Fi network as uplink; an active access point asks before it is stood down. */
    fun join(ssid: String, passphrase: String?, standDownAccessPoint: Boolean = false) {
        _state.update { it.copy(joinNeedsForce = null) }
        writes.run(label = ssid, onDone = ::refresh) { _ ->
            val c = client() ?: return@run AgentResult.Err(AgentError.Unauthorized)
            val r = c.groundStation.joinClientNetwork(
                buildJsonObject {
                    put("ssid", ssid)
                    if (!passphrase.isNullOrEmpty()) put("passphrase", passphrase)
                    if (standDownAccessPoint) put("force", true)
                },
            )
            val err = (r as? AgentResult.Err)?.error
            if (!standDownAccessPoint && err is AgentError.Server && err.code == 409) {
                _state.update { it.copy(joinNeedsForce = PendingJoin(ssid, passphrase)) }
            }
            r
        }
    }

    fun cancelJoin() = _state.update { it.copy(joinNeedsForce = null) }

    fun leaveNetwork() = write("leave") { c, _ -> c.groundStation.leaveClientNetwork() }

    fun saveModem(enabled: Boolean?, apn: String?, capMb: Int?) = write("modem") { c, _ ->
        c.groundStation.putModem(
            buildJsonObject {
                enabled?.let { put("enabled", it) }
                apn?.let { put("apn", it) }
                capMb?.let { put("cap_mb", it) }
            },
        )
    }

    fun setShareUplink(enabled: Boolean) = write("share_uplink") { c, _ ->
        c.requestJson("PUT", "api/v1/ground-station/network/share_uplink", buildJsonObject { put("enabled", enabled) })
    }

    // ---- mesh ----------------------------------------------------------------

    private suspend fun loadMesh(client: AgentClient) {
        val role = client.groundStation.role()
        if (role is AgentResult.Err) return fail(NodeTab.Mesh, PageProblem.from(role.error))
        val roleObj = role.getOrNull() ?: JsonObject(emptyMap())
        // A direct node answers 404 E_NOT_IN_MESH: no mesh, not an error.
        val mesh = client.groundStation.mesh().getOrNull()
        val parsed = MeshState.parse(roleObj, mesh)
        val window = if (parsed.role == "receiver") {
            (client.groundStation.pairPending().getOrNull() as? JsonObject)?.let(PairWindow::parse)
        } else {
            null
        }
        _state.update { it.copy(mesh = Load.Ready(parsed), pairWindow = window) }
    }

    fun setRole(role: String) = write(role) { c, force ->
        c.groundStation.putRole(
            buildJsonObject {
                put("role", role)
                if (force) put("force", true)
            },
        )
    }

    fun openPairWindow(durationS: Int = 60) = write("pair") { c, _ ->
        c.groundStation.pairAccept(buildJsonObject { put("duration_s", durationS) })
    }

    fun closePairWindow() = write("pair") { c, _ -> c.groundStation.pairClose() }

    fun approve(deviceId: String) = write(deviceId) { c, _ -> c.groundStation.pairApprove(deviceId) }

    fun joinMesh(code: String) = write("join") { c, _ ->
        c.requestJson("POST", "api/v1/ground-station/pair/join", buildJsonObject { put("code", code.trim()) })
    }

    // ---- plumbing ------------------------------------------------------------

    private fun write(label: String, block: suspend (AgentClient, Boolean) -> AgentResult<*>) {
        writes.run(label = label, onDone = ::refresh) { force ->
            val c = client() ?: return@run AgentResult.Err(AgentError.Unauthorized)
            block(c, force)
        }
    }

    private fun fail(tab: NodeTab, problem: PageProblem) {
        _state.update { s ->
            when (tab) {
                // Keep the last good reading while a poll blips; replace a loading or failed state.
                NodeTab.CarriedDrones -> if (s.carried is Load.Ready) s else s.copy(carried = Load.Failed(problem))
                NodeTab.Radio -> if (s.radio is Load.Ready) s else s.copy(radio = Load.Failed(problem))
                NodeTab.Network -> if (s.network is Load.Ready) s else s.copy(network = Load.Failed(problem))
                NodeTab.Mesh -> if (s.mesh is Load.Ready) s else s.copy(mesh = Load.Failed(problem))
                else -> s
            }
        }
    }

    private suspend fun client(): AgentClient? = clients.clientFor(nodes.node(id).filterNotNull().first())

    private companion object {
        const val POLL_MS = 3_000L
    }
}
