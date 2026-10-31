package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The console Home; the root of the back stack, never popped. */
@Serializable
data object HomeKey : NavKey

/** Full-screen cockpit for a node (a live node, or a `replay:` node fed from a log). */
@Serializable
data class CockpitKey(val id: String) : NavKey

/** The mission planner, optionally for one vehicle. */
@Serializable
data class PlanKey(val nodeId: String? = null) : NavKey

/** Flight-controller setup for a vehicle. */
@Serializable
data class SetupKey(val id: String) : NavKey

/** Flight-controller parameters for a vehicle. */
@Serializable
data class ParamsKey(val id: String) : NavKey

/** The Vehicle surface: node detail. */
@Serializable
data class NodeKey(val id: String) : NavKey

@Serializable
data object LogsKey : NavKey

@Serializable
data class FlightKey(val sessionId: Long) : NavKey

/** Onboard flight-controller logs of a vehicle: list and download. */
@Serializable
data class DataflashKey(val id: String) : NavKey

@Serializable
data object SettingsKey : NavKey

/** Connect; [code]/[host] come from an `adosgcs://pair` deep link. */
@Serializable
data class ConnectKey(val code: String? = null, val host: String? = null) : NavKey

/** The controller surface: status, button maps with bindings, and the live input test. */
@Serializable
data object ControllerKey : NavKey

@Serializable
data object OfflineMapsKey : NavKey

@Serializable
data object LicencesKey : NavKey

/** The pair deep link `adosgcs://pair?code=&host=`. */
data class PairLink(val code: String?, val host: String?)

/** Settings → Cloud: sign-in, claim by code, cloud nodes. */
@Serializable
data object CloudKey : NavKey

/** Settings / Connect → Phone radio: adapter, receive keys, link stats. */
@Serializable
data object PhoneRadioKey : NavKey
