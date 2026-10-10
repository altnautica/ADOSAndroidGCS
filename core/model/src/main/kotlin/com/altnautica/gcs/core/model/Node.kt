package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.Flow

/** Stable identity of an ADOS node (agent `device_id`, or a synthetic id for direct links). */
@JvmInline
value class DeviceId(val value: String)

enum class NodeProfile { Drone, GroundStation, Workstation, Unknown }

enum class Liveness { Live, Stale, Offline }

enum class FcVariant { MavlinkArdupilot, MavlinkPx4, Msp, Unknown }

/** One way the app can reach a node. A node lists its reaches best-first. */
sealed interface Reach {
    /** Flight controller attached to the phone (USB serial, UDP or TCP). */
    data object DirectFc : Reach

    /** Agent reachable on the local network. */
    data object Lan : Reach

    /** Drone reached through the ground station that carries it. */
    data class ViaGround(val groundId: DeviceId) : Reach

    /** Live cloud link (MQTT control grant covers the device). */
    data object CloudLive : Reach

    /** Cloud command queue only (no live grant). */
    data object CloudQueue : Reach

    /** Receive-only link through a radio dongle attached to the phone. */
    data object PhoneRadio : Reach

    /** The node is known but cannot be reached for the given reason. */
    data class Blocked(val reason: BlockReason) : Reach
}

enum class BlockReason { NotPaired, CloudDisabled, SignedOut, NoGrant, GrantExpired, Offline, ReceiveOnly }

data class NodeSummary(
    val id: DeviceId,
    val name: String,
    val profile: NodeProfile,
    /** Ordered best-first. */
    val reaches: List<Reach>,
    val liveness: Liveness,
    val agentVersion: String?,
    val fcVariant: FcVariant,
    val carriedDroneId: DeviceId?,
    val lastSeenMs: Long?,
)

/** Producer of nodes for one link family; bound into a Hilt set. */
interface NodeSource {
    val nodes: Flow<List<NodeSummary>>
}
