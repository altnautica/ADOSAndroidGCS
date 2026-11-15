package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.CommandResponse
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.pilot.PilotFrame
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MissionGap
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.RouteCheck
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the planner reads from the vehicle's onboard pilot: the waypoints it skipped this
 * flight ([gaps]), the place class it flies ([placeClass], null with no pilot) and its route
 * check of the loaded mission ([routeCheck], confined only). [tsMs] is the pilot frame's own
 * time and [ageMs] its age when the status was served (null when the status leaves it out).
 */
data class PilotPlanView(
    val gaps: List<MissionGap> = emptyList(),
    val placeClass: PlaceClass? = null,
    val routeCheck: RouteCheck? = null,
    val tsMs: Long? = null,
    val ageMs: Long? = null,
) {
    /** The pilot's frame was under 2 s old when the status was served (the agent's own rule for a place-class change). */
    val fresh: Boolean get() = ageMs != null && ageMs <= FRESH_MS

    companion object {
        val None = PilotPlanView()
        const val FRESH_MS = 2_000L
    }
}

/**
 * The vehicle's onboard pilot as the planner sees it: its state from the agent's navigation
 * status (`GET /api/v1/nav/status`) or an in-process node's, and the place class it is asked
 * to fly. [PilotPlanView.None] with no agent or no pilot.
 */
interface VehiclePilotSource {
    fun observe(id: DeviceId): Flow<PilotPlanView>

    /** One fresh read of [observe]'s view. */
    suspend fun read(id: DeviceId): PilotPlanView

    /**
     * Sets the pilot's place class: the agent's `POST /api/command` verb `placeclass_<class>`,
     * or the `place_class:<class>` action on an in-process node's pilot stream. The pilot
     * refuses it while armed.
     */
    suspend fun setPlaceClass(id: DeviceId, placeClass: PlaceClass): PilotActionResult
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AgentVehiclePilotSource @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: AgentClientFactory,
    private val local: Set<@JvmSuppressWildcards LocalNodeApis>,
) : VehiclePilotSource {
    override fun observe(id: DeviceId): Flow<PilotPlanView> {
        localApi(id)?.let { api -> return polled { api.navStatus(null) } }
        return nodes.node(id)
            .map { it?.reaches?.firstOrNull() }
            .distinctUntilChanged()
            .flatMapLatest { reach ->
                val client = client(id, reach) ?: return@flatMapLatest flowOf(PilotPlanView.None)
                polled { client.nav.status() }
            }
            .distinctUntilChanged()
    }

    override suspend fun read(id: DeviceId): PilotPlanView {
        localApi(id)?.let { return viewOf(it.navStatus(null)?.getOrNull()) }
        val client = client(id, nodes.node(id).first()?.reaches?.firstOrNull()) ?: return PilotPlanView.None
        return viewOf(client.nav.status().getOrNull())
    }

    override suspend fun setPlaceClass(id: DeviceId, placeClass: PlaceClass): PilotActionResult {
        localApi(id)?.let { return pilotAction(it, PilotAction.placeClass(placeClass)) }
        val client = client(id, nodes.node(id).first()?.reaches?.firstOrNull()) ?: return PilotActionResult.Unavailable
        return commandAnswer(client.command(placeClassVerb(placeClass)))
    }

    private fun localApi(id: DeviceId): AutonomyFeedApi? = local.firstNotNullOfOrNull { it.autonomy(id) }

    private suspend fun client(id: DeviceId, reach: Reach?): AgentClient? = when (reach) {
        Reach.Lan -> clients.forNode(id)
        is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
        else -> null
    }

    private fun polled(read: suspend () -> AgentResult<JsonObject>?): Flow<PilotPlanView> = flow {
        while (true) {
            emit(viewOf(read()?.getOrNull()))
            delay(POLL_MS)
        }
    }.distinctUntilChanged()

    /** One action on an in-process pilot's stream, answered by its `action_result`. */
    private suspend fun pilotAction(api: AutonomyFeedApi, action: PilotAction): PilotActionResult {
        val sent = PilotWire.action(action, PilotWire.SRC_ANDROID, ACTION_ID)
        val answer = withTimeoutOrNull(ANSWER_MS) {
            api.pilotStream(flowOf(sent))
                .mapNotNull { PilotWire.parseFrame(it, 0L) as? PilotFrame.ActionResult }
                .first { it.name == action.name }
        } ?: return PilotActionResult.NoAnswer
        return if (answer.ok) PilotActionResult.Accepted else PilotActionResult.Refused(answer.err)
    }

    companion object {
        const val POLL_MS = 2_000L
        private const val ANSWER_MS = 3_000L
        private const val ACTION_ID = "planner"

        /** The agent's `/api/command` verb that sets [c] (`placeclass_open`, `placeclass_confined`). */
        fun placeClassVerb(c: PlaceClass): String = "placeclass_${c.wire}"

        /** The pilot's refusal code in a `/api/command` refusal ("pilot refused place_class:confined: armed" → `armed`). */
        fun refusalCode(message: String?): String? = message?.substringAfterLast(": ")?.trim()?.ifEmpty { null }

        /**
         * The pilot's answer to a `/api/command` place-class verb. An error never reached the
         * pilot: no answer over the network, a pilot the agent cannot reach (503), an agent that
         * does not route the verb (`unknown_action`) or a caller it does not accept.
         */
        fun commandAnswer(r: AgentResult<CommandResponse>): PilotActionResult = when (r) {
            is AgentResult.Ok -> if (r.value.accepted) PilotActionResult.Accepted else PilotActionResult.Refused(refusalCode(r.value.message))
            is AgentResult.Err -> when (val e = r.error) {
                is AgentError.Network, is AgentError.PeerApi, is AgentError.RelayTicket -> PilotActionResult.NoAnswer
                AgentError.Unauthorized, is AgentError.PinRequired -> PilotActionResult.Refused("not_authorized")
                is AgentError.Armed -> PilotActionResult.Refused("armed")
                is AgentError.Server -> if (e.code == 503) PilotActionResult.Unavailable else PilotActionResult.Refused("unknown_action")
                AgentError.FeatureAbsent, AgentError.ProfileMismatch -> PilotActionResult.Refused("unknown_action")
            }
        }

        /** The planner's view of a navigation status body's `pilot` frame (`{pilot: {ts_ms, age_ms, data}}`). */
        fun viewOf(status: JsonObject?): PilotPlanView {
            val pilot = status?.get("pilot") as? JsonObject
            val data = (pilot?.get("data") as? JsonObject) ?: return PilotPlanView.None
            val p = PilotWire.parseState(data, 0L) ?: return PilotPlanView.None
            fun long(key: String) = (pilot[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            return PilotPlanView(p.mission?.gaps.orEmpty(), p.placeClass, p.routeCheck, long("ts_ms"), long("age_ms"))
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VehiclePilotModule {
    @Binds
    abstract fun bindVehiclePilotSource(impl: AgentVehiclePilotSource): VehiclePilotSource
}
