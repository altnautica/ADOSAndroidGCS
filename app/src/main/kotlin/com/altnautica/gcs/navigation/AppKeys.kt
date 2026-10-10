package com.altnautica.gcs.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Destinations shown in the navigation suite (rail, bar or drawer). */
sealed interface TopLevelKey : NavKey

@Serializable
data object FleetKey : TopLevelKey

@Serializable
data object PlanKey : TopLevelKey

@Serializable
data object LogsKey : TopLevelKey

@Serializable
data object SettingsKey : TopLevelKey

/** Node detail; full route above the navigation suite. */
@Serializable
data class NodeKey(val id: String) : NavKey

/** Full-screen cockpit for a node (a live node, or a `replay:` node fed from a log). */
@Serializable
data class CockpitKey(val id: String) : NavKey

/** Connect; [code]/[host] come from an `adosgcs://pair` deep link. */
@Serializable
data class ConnectKey(val code: String? = null, val host: String? = null) : NavKey

@Serializable
data class FlightKey(val sessionId: Long) : NavKey

@Serializable
data object SkillBindingsKey : NavKey

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
