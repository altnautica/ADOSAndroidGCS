package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.sensors.BaseModeRequest
import com.altnautica.gcs.core.agent.sensors.GnssStatus
import com.altnautica.gcs.core.agent.sensors.LoraBeacon
import com.altnautica.gcs.core.agent.sensors.LoraRegionPlan
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.feature.node.common.PageProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Progress of a LoRa region change (admin write, refused while armed, never forced). */
sealed interface RegionWrite {
    data object Idle : RegionWrite
    data object Running : RegionWrite
    data class Done(val code: String?) : RegionWrite
    data class Failed(val failure: RegionFailure, val message: String?) : RegionWrite
}

enum class RegionFailure { Armed, OutOfPlan, NotAllowed, Other }

data class LanesUiState(
    /** `present: false` arrives as Ready with `present` false: the "not fitted" state. */
    val gnss: Load<GnssStatus> = Load.Loading,
    /** Ready(null) when no LoRa radio is fitted. */
    val lora: Load<LoraUi?> = Load.Loading,
    /** Wall time of the last LoRa read; the next-TX countdown ticks from here. */
    val loraReadAtMs: Long = 0,
    val beacons: List<LoraBeacon> = emptyList(),
    val regions: Load<List<LoraRegionPlan>>? = null,
    val regionWrite: RegionWrite = RegionWrite.Idle,
    /** A paired vehicle is armed: region changes are locked. */
    val armed: Boolean = false,
)

/**
 * The ground station's GNSS and LoRa lanes, owned by [GroundStationViewModel].
 * [api] is resolved per call so demo nodes and real agents share the path.
 */
class StationLanes(
    private val scope: CoroutineScope,
    private val api: suspend () -> SensorsApi?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(LanesUiState())
    val state: StateFlow<LanesUiState> = _state.asStateFlow()

    suspend fun loadGnss() {
        val a = api() ?: return failGnss(PageProblem.NoAgentLink)
        when (val r = a.gnss()) {
            is AgentResult.Ok -> _state.update { it.copy(gnss = Load.Ready(r.value)) }
            is AgentResult.Err -> failGnss(PageProblem.from(r.error))
        }
    }

    suspend fun loadLora(withBeacons: Boolean) {
        val a = api() ?: return failLora(PageProblem.NoAgentLink)
        when (val r = a.lora()) {
            is AgentResult.Ok -> {
                val ui = r.value.takeIf { it.present }?.let(LoraGovernor::map)
                _state.update { it.copy(lora = Load.Ready(ui), loraReadAtMs = now()) }
                if (withBeacons && ui != null) {
                    a.loraBeacons().getOrNull()?.let { b -> _state.update { it.copy(beacons = b.beacons) } }
                }
            }
            is AgentResult.Err -> failLora(PageProblem.from(r.error))
        }
    }

    fun loadRegions() {
        _state.update { it.copy(regions = Load.Loading) }
        scope.launch {
            val a = api() ?: return@launch _state.update { it.copy(regions = Load.Failed(PageProblem.NoAgentLink)) }
            _state.update {
                it.copy(
                    regions = when (val r = a.loraRegions()) {
                        is AgentResult.Ok -> Load.Ready(r.value.regions)
                        is AgentResult.Err -> Load.Failed(PageProblem.from(r.error))
                    },
                )
            }
        }
    }

    fun setArmed(armed: Boolean) = _state.update { it.copy(armed = armed) }

    /** Sets the region ([code] null follows the country setting). Refused locally while armed. */
    fun setRegion(code: String?) {
        if (_state.value.armed) {
            _state.update { it.copy(regionWrite = RegionWrite.Failed(RegionFailure.Armed, null)) }
            return
        }
        if (_state.value.regionWrite == RegionWrite.Running) return
        _state.update { it.copy(regionWrite = RegionWrite.Running) }
        scope.launch {
            val a = api()
            val r = a?.putLoraRegion(code)
            val next = when (r) {
                null -> RegionWrite.Failed(RegionFailure.Other, null)
                is AgentResult.Ok -> RegionWrite.Done(code)
                is AgentResult.Err -> regionFailure(r.error)
            }
            _state.update { s ->
                val ok = (r as? AgentResult.Ok)?.value
                s.copy(
                    regionWrite = next,
                    lora = if (ok != null) Load.Ready(ok.takeIf { it.present }?.let(LoraGovernor::map)) else s.lora,
                    loraReadAtMs = if (ok != null) now() else s.loraReadAtMs,
                )
            }
        }
    }

    fun dismissRegionWrite() = _state.update { it.copy(regionWrite = RegionWrite.Idle) }

    /** RTK base mode; the answer replaces the base block until the next poll. */
    suspend fun setBase(request: BaseModeRequest): AgentResult<*> {
        val a = api() ?: return AgentResult.Err(AgentError.Unauthorized)
        val r = a.putGnssBase(request)
        r.getOrNull()?.let { base ->
            _state.update { s ->
                val g = (s.gnss as? Load.Ready)?.value ?: return@update s
                s.copy(gnss = Load.Ready(g.copy(base = base)))
            }
        }
        return r
    }

    private fun failGnss(problem: PageProblem) = _state.update { s -> if (s.gnss is Load.Ready) s else s.copy(gnss = Load.Failed(problem)) }

    private fun failLora(problem: PageProblem) = _state.update { s -> if (s.lora is Load.Ready) s else s.copy(lora = Load.Failed(problem)) }

    companion object {
        fun regionFailure(error: AgentError): RegionWrite.Failed = when {
            error is AgentError.Armed -> RegionWrite.Failed(RegionFailure.Armed, null)
            error is AgentError.Server && (error.errorCode ?: error.message) == "E_LORA_OUT_OF_PLAN" ->
                RegionWrite.Failed(RegionFailure.OutOfPlan, null)
            error is AgentError.Unauthorized || error is AgentError.PinRequired -> RegionWrite.Failed(RegionFailure.NotAllowed, null)
            error is AgentError.Server -> RegionWrite.Failed(RegionFailure.Other, error.message)
            else -> RegionWrite.Failed(RegionFailure.Other, null)
        }
    }
}
