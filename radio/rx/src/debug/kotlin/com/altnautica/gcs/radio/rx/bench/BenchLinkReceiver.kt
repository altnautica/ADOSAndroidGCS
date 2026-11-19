package com.altnautica.gcs.radio.rx.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.link.fleet.FleetGroundStore
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.aux.TunnelResult
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Drives the phone radio's uplink from the fleet bench through the same
 * services the screens use: the parameter and mission services of the
 * vehicle (MAVLink over the aux uplink), the node-API client (relayed over
 * the Request lane when the drone is reached only over the phone radio), and
 * the config channel. Debug builds only.
 *
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PARAM_READ --es node <id> --es name <PARAM>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PARAM_SET --es node <id> --es name <PARAM> --es value <number>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.MISSION_UPLOAD --es node <id> [--ei count 20] [--es lat <deg> --es lon <deg>] [--es alt <m>]
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.RELAY_CALL --es node <id> [--es method GET] [--es path api/status/full] [--es body <json>]
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.CONFIG_GET --es node <id> [--es key <dot.path>]
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.CONFIG_PUT --es node <id> --es key <dot.path> --es value <text>
 *
 * Each logs one result line under the `AdosPhoneBench` tag, `<op> <field>=<value> …`.
 */
@AndroidEntryPoint
class BenchLinkReceiver : BroadcastReceiver() {

    @Inject lateinit var services: VehicleServicesProvider

    @Inject lateinit var agents: AgentClientFactory

    @Inject lateinit var session: PhoneRadioSession

    @Inject lateinit var fleetGround: FleetGroundStore

    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra("node") ?: return log("${intent.action} refused=no_node")
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    PARAM_READ -> paramRead(node, intent.getStringExtra("name").orEmpty())
                    PARAM_SET -> paramSet(node, intent.getStringExtra("name").orEmpty(), intent.getStringExtra("value")?.toDoubleOrNull())
                    MISSION_UPLOAD -> missionUpload(node, intent)
                    RELAY_CALL -> relayCall(node, intent)
                    CONFIG_GET -> config(node, intent.getStringExtra("key"), null)
                    CONFIG_PUT -> config(node, intent.getStringExtra("key"), intent.getStringExtra("value") ?: "")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun vehicle(node: String): VehicleServices? =
        withTimeoutOrNull(SERVICES_WAIT_MS) { services.services(DeviceId(node)).filterNotNull().first() }

    private suspend fun paramRead(node: String, name: String) {
        val v = vehicle(node) ?: return log("param_read node=$node result=no_vehicle")
        val value = v.params.read(name)
        log("param_read node=$node name=$name result=${if (value == null) "timeout" else "ok"} value=${value?.value}")
    }

    private suspend fun paramSet(node: String, name: String, value: Double?) {
        if (value == null) return log("param_set node=$node result=bad_value")
        val v = vehicle(node) ?: return log("param_set node=$node result=no_vehicle")
        log("param_set node=$node name=$name result=${v.params.set(name, value)}")
    }

    private suspend fun missionUpload(node: String, intent: Intent) {
        val v = vehicle(node) ?: return log("mission_upload node=$node result=no_vehicle")
        val count = intent.getIntExtra("count", DEFAULT_ITEMS)
        val lat = intent.getStringExtra("lat")?.toDoubleOrNull() ?: DEFAULT_LAT
        val lon = intent.getStringExtra("lon")?.toDoubleOrNull() ?: DEFAULT_LON
        val alt = intent.getStringExtra("alt")?.toFloatOrNull() ?: DEFAULT_ALT_M
        val items = (0 until count).map { i ->
            MissionItem(
                command = MavCmd.MAV_CMD_NAV_WAYPOINT,
                x = ((lat + i * STEP_DEG) * 1e7).toInt(),
                y = ((lon + i * STEP_DEG) * 1e7).toInt(),
                z = alt,
            )
        }
        val result = when (val r = v.mission.upload(MissionType.Mission, items)) {
            is MissionResult.Success -> "accepted opaque_id=${r.value.opaqueId}"
            is MissionResult.Failure -> "failed error=${r.error}"
        }
        log("mission_upload node=$node items=$count result=$result")
    }

    private suspend fun relayCall(node: String, intent: Intent) {
        val client = agents.forNode(DeviceId(node)) ?: return log("relay_call node=$node result=no_route")
        val method = intent.getStringExtra("method") ?: "GET"
        val path = intent.getStringExtra("path") ?: "api/status/full"
        val body = intent.getStringExtra("body")?.let { Json.parseToJsonElement(it) }
        val route = if (client.endpoint.overRadio) "phone_radio" else "lan"
        when (val r = client.requestJson(method, path, body)) {
            is AgentResult.Ok -> log("relay_call node=$node route=$route path=$path result=ok bytes=${r.value.toString().length}")
            is AgentResult.Err -> log("relay_call node=$node route=$route path=$path result=error error=${r.error}")
        }
    }

    private suspend fun config(node: String, key: String?, value: String?) {
        val secret = fleetGround.fleet.value?.slots?.firstOrNull { it.deviceId == node }?.relaySecret
            ?: return log("config node=$node result=no_secret")
        val tunnel = session.lanes.tunnel
        val r = if (value == null) tunnel.get(node, key, secret) else tunnel.put(node, key.orEmpty(), value, secret)
        val result = when (r) {
            is TunnelResult.Answered -> "${if (r.isError) "error" else "ok"} body=${r.body.decodeToString()}"
            is TunnelResult.Failed -> "failed reason=${r.reason}"
        }
        log("config node=$node op=${if (value == null) "get" else "put"} key=$key result=$result")
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        const val PARAM_READ = "com.altnautica.gcs.bench.PARAM_READ"
        const val PARAM_SET = "com.altnautica.gcs.bench.PARAM_SET"
        const val MISSION_UPLOAD = "com.altnautica.gcs.bench.MISSION_UPLOAD"
        const val RELAY_CALL = "com.altnautica.gcs.bench.RELAY_CALL"
        const val CONFIG_GET = "com.altnautica.gcs.bench.CONFIG_GET"
        const val CONFIG_PUT = "com.altnautica.gcs.bench.CONFIG_PUT"
        private const val TAG = "AdosPhoneBench"
        private const val SERVICES_WAIT_MS = 10_000L
        private const val DEFAULT_ITEMS = 20
        private const val DEFAULT_LAT = 12.9716
        private const val DEFAULT_LON = 77.5946
        private const val DEFAULT_ALT_M = 20f
        private const val STEP_DEG = 0.0001
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
