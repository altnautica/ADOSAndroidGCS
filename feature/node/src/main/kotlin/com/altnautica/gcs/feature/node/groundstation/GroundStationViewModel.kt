package com.altnautica.gcs.feature.node.groundstation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.groundstation.ApUpdate
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.common.AgentWriteRunner
import com.altnautica.gcs.core.agent.sensors.BaseModeRequest
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.feature.node.common.NodeApis
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.PageProblem
import com.altnautica.gcs.feature.node.common.NO_AGENT_LINK
import com.altnautica.gcs.feature.node.common.WriteAction
import com.altnautica.gcs.feature.node.common.WriteLabel
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

    /** [stale]: the latest read failed and [value] is the last good reading. */
    data class Ready<T>(val value: T, val stale: Boolean = false) : Load<T>
    data class Failed(val problem: PageProblem) : Load<Nothing>
}

/** A failed poll: the last good reading stays, marked stale; anything else becomes the problem. */
fun <T> Load<T>.afterFailure(problem: PageProblem): Load<T> = if (this is Load.Ready) copy(stale = true) else Load.Failed(problem)

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
 * The ground-station tabs (Carried drones, Radio, Network, GNSS, LoRa, Mesh).
 * Only the visible tab polls; writes go through one runner with the armed
 * override (a LoRa region change is never forced).
 */
@HiltViewModel(assistedFactory = GroundStationViewModel.Factory::class)
class GroundStationViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    private val nodes: NodeRepository,
    private val clients: NodeClients,
    private val apis: NodeApis,
    private val vitals: NodeVitalsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): GroundStationViewModel
    }

    private val id = DeviceId(nodeId)
    private val _state = MutableStateFlow(GroundStationUiState())
    val state: StateFlow<GroundStationUiState> = _state.asStateFlow()

    /** GNSS (`/api/v1/gnss`) and LoRa (`/api/v1/lora`) lanes. */
    val lanes = StationLanes(viewModelScope, { apis.sensors(id) })

    val writes = AgentWriteRunner(viewModelScope)
    val writeState: StateFlow<WriteState> get() = writes.state

    private var pollJob: Job? = null
    private var activeTab: NodeTab? = null

    /** Starts polling [tab]'s data (stops the previous tab's poll); null stops everything. */
    fun show(tab: NodeTab?) {
        if (tab == activeTab && pollJob?.isActive == true) return
        activeTab = tab
        pollJob?.cancel()
        val lane = tab?.takeIf { it == NodeTab.Gnss || it == NodeTab.Lora }
        if (lane != null) return showLane(lane)
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

    /** Reads the visible tab again now, restarting its poll: the Retry button, and the reload after a write. */
    fun retry() {
        val tab = activeTab
        activeTab = null
        show(tab)
    }

    private fun showLane(tab: NodeTab) {
        pollJob = viewModelScope.launch {
            if (tab == NodeTab.Lora) {
                lanes.loadRegions()
                launch {
                    val node = nodes.node(id).filterNotNull().first()
                    vitals.vitals(node).collect { lanes.setArmed(it.armed == true) }
                }
            }
            var tick = 0
            while (isActive) {
                if (tab == NodeTab.Gnss) lanes.loadGnss() else lanes.loadLora(withLinks = tick % BEACON_EVERY == 0)
                tick++
                delay(if (tab == NodeTab.Lora) LORA_POLL_MS else POLL_MS)
            }
        }
    }

    // ---- gnss ----------------------------------------------------------------

    /** RTK base mode; a write refused while armed retries with the override after the hold-to-confirm. */
    fun setBase(request: BaseModeRequest) = writes.run(label = WriteLabel(WriteAction.GnssBase)) { force -> lanes.setBase(request, force) }

    // ---- carried drones ------------------------------------------------------

    private suspend fun loadCarried(client: AgentClient) {
        val pair = client.requestJson("GET", "api/wfb/pair")
        if (pair is AgentResult.Err) return fail(NodeTab.CarriedDrones, PageProblem.from(pair.error))
        val relayed = client.requestJson("GET", "api/v1/ground-station/relayed/status").getOrNull() as? JsonObject
        val status = client.requestJson("GET", "api/v1/ground-station/status").getOrNull() as? JsonObject
        _state.update { it.copy(carried = Load.Ready(CarriedDrones.parse(pair.getOrNull() as? JsonObject, relayed, status))) }
    }

    /** Frees the drone's fleet slot on this ground station; the armed override rides the query (bodiless DELETE). */
    fun releaseSlot(deviceId: String) = write(WriteLabel(WriteAction.ReleaseSlot, deviceId)) { c, force -> releaseSlotRequest(c, deviceId, force) }

    // ---- radio ---------------------------------------------------------------

    private suspend fun loadRadio(client: AgentClient) {
        val status = client.requestJson("GET", "api/v1/ground-station/status")
        if (status is AgentResult.Err) return fail(NodeTab.Radio, PageProblem.from(status.error))
        val wfb = client.requestJson("GET", "api/v1/ground-station/wfb").getOrNull() as? JsonObject
        val adapters = (client.requestJson("GET", "api/v1/radio/adapters").getOrNull() as? JsonObject)?.let(RadioAdapters::parse)
        val obj = status.getOrNull() as? JsonObject ?: JsonObject(emptyMap())
        _state.update { it.copy(radio = Load.Ready(RadioLink.parse(obj, wfb).copy(adapters = adapters))) }
    }

    fun setTxPower(dbm: Int) = write(WriteLabel(WriteAction.TxPower)) { c, force ->
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

    fun saveAccessPoint(enabled: Boolean?, ssid: String?, passphrase: String?, channel: Int?) = write(WriteLabel(WriteAction.AccessPoint)) { c, force ->
        c.groundStation.putNetworkAp(apUpdate(enabled, ssid, passphrase, channel, force))
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

    /**
     * Joins a Wi-Fi network as uplink; an active access point asks before it is stood down.
     * The agent reads one `force` flag both as the armed override and as "take wlan0 from the
     * access point", so a confirmed armed override with the access point (possibly) up asks the
     * access-point question first and sends nothing until that is confirmed too.
     */
    fun join(ssid: String, passphrase: String?, standDownAccessPoint: Boolean = false) {
        _state.update { it.copy(joinNeedsForce = null) }
        val askAccessPointFirst = {
            val ask = !standDownAccessPoint && accessPointMayBeUp()
            if (ask) _state.update { it.copy(joinNeedsForce = PendingJoin(ssid, passphrase)) }
            !ask
        }
        writes.run(label = WriteLabel(WriteAction.Join, ssid), onDone = ::retry, beforeForce = askAccessPointFirst) { force ->
            val c = client() ?: return@run AgentResult.Err(NO_AGENT_LINK)
            val r = c.groundStation.joinClientNetwork(joinBody(ssid, passphrase, force = standDownAccessPoint || force))
            val err = (r as? AgentResult.Err)?.error
            if (!standDownAccessPoint && err is AgentError.Server && err.code == 409) {
                _state.update { it.copy(joinNeedsForce = PendingJoin(ssid, passphrase)) }
            }
            r
        }
    }

    /** The station's access point is running, enabled, or not yet known. */
    private fun accessPointMayBeUp(): Boolean = when (val n = _state.value.network) {
        is Load.Ready -> n.value.ap?.let { it.running == true || it.enabled != false } ?: false
        else -> true
    }

    fun cancelJoin() = _state.update { it.copy(joinNeedsForce = null) }

    fun leaveNetwork(ssid: String) = write(WriteLabel(WriteAction.Leave, ssid)) { c, force -> c.groundStation.leaveClientNetwork(force) }

    fun saveModem(enabled: Boolean?, apn: String?, capMb: Int?) = write(WriteLabel(WriteAction.Modem)) { c, force ->
        c.groundStation.putModem(modemBody(enabled, apn, capMb, force))
    }

    fun setShareUplink(enabled: Boolean) = write(WriteLabel(WriteAction.ShareUplink)) { c, force ->
        c.requestJson("PUT", "api/v1/ground-station/network/share_uplink", shareUplinkBody(enabled, force))
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

    fun setRole(role: String) = write(WriteLabel(WriteAction.Role, role)) { c, force ->
        c.groundStation.putRole(
            buildJsonObject {
                put("role", role)
                if (force) put("force", true)
            },
        )
    }

    fun openPairWindow(durationS: Int = 60) = write(WriteLabel(WriteAction.PairOpen)) { c, _ ->
        c.groundStation.pairAccept(buildJsonObject { put("duration_s", durationS) })
    }

    fun closePairWindow() = write(WriteLabel(WriteAction.PairClose)) { c, _ -> c.groundStation.pairClose() }

    fun approve(deviceId: String) = write(WriteLabel(WriteAction.Approve, deviceId)) { c, _ -> c.groundStation.pairApprove(deviceId) }

    fun joinMesh(code: String) = write(WriteLabel(WriteAction.JoinMesh)) { c, _ ->
        c.requestJson("POST", "api/v1/ground-station/pair/join", buildJsonObject { put("code", code.trim()) })
    }

    // ---- plumbing ------------------------------------------------------------

    private fun write(label: WriteLabel, block: suspend (AgentClient, Boolean) -> AgentResult<*>) {
        writes.run(label = label, onDone = ::retry) { force ->
            val c = client() ?: return@run AgentResult.Err(NO_AGENT_LINK)
            block(c, force)
        }
    }

    private fun fail(tab: NodeTab, problem: PageProblem) {
        _state.update { s ->
            when (tab) {
                // Keep the last good reading while a poll blips; replace a loading or failed state.
                NodeTab.CarriedDrones -> s.copy(carried = s.carried.afterFailure(problem))
                NodeTab.Radio -> s.copy(radio = s.radio.afterFailure(problem))
                NodeTab.Network -> s.copy(network = s.network.afterFailure(problem))
                NodeTab.Mesh -> s.copy(mesh = s.mesh.afterFailure(problem))
                else -> s
            }
        }
    }

    private suspend fun client(): AgentClient? = clients.clientFor(nodes.node(id).filterNotNull().first())

    private companion object {
        const val POLL_MS = 3_000L
        const val LORA_POLL_MS = 1_000L

        /** Beacons are read on every fifth LoRa poll (5 s). */
        const val BEACON_EVERY = 5
    }
}

/**
 * `DELETE /api/v1/ground-station/wfb/pair/{id}`. The agent's armed guard takes
 * the override for bodiless methods only as `?force=1`.
 */
internal suspend fun releaseSlotRequest(client: AgentClient, deviceId: String, force: Boolean): AgentResult<*> =
    client.requestJson(
        "DELETE",
        "api/v1/ground-station/wfb/pair/${java.net.URLEncoder.encode(deviceId, "UTF-8").replace("+", "%20")}",
        query = if (force) mapOf("force" to "1") else emptyMap(),
    )

// Write bodies. Each carries the armed override (`force: true`) only on the confirmed retry.

internal fun apUpdate(enabled: Boolean?, ssid: String?, passphrase: String?, channel: Int?, force: Boolean) =
    ApUpdate(enabled, ssid?.takeIf { it.isNotBlank() }, passphrase?.takeIf { it.isNotEmpty() }, channel, force.takeIf { it })

internal fun joinBody(ssid: String, passphrase: String?, force: Boolean): JsonObject = buildJsonObject {
    put("ssid", ssid)
    if (!passphrase.isNullOrEmpty()) put("passphrase", passphrase)
    if (force) put("force", true)
}

internal fun modemBody(enabled: Boolean?, apn: String?, capMb: Int?, force: Boolean): JsonObject = buildJsonObject {
    enabled?.let { put("enabled", it) }
    apn?.let { put("apn", it) }
    capMb?.let { put("cap_mb", it) }
    if (force) put("force", true)
}

internal fun shareUplinkBody(enabled: Boolean, force: Boolean): JsonObject = buildJsonObject {
    put("enabled", enabled)
    if (force) put("force", true)
}
