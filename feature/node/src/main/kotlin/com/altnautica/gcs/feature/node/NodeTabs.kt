package com.altnautica.gcs.feature.node

import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/** Built-in node detail tabs; extension tabs are appended after these. */
enum class NodeTab { Overview, Cockpit, Setup, Parameters, Capture, Sessions, CarriedDrones, Radio, Network, Gnss, Lora, Mesh, Logs, Agent }

/** Optional ground-station services the node reported as fitted (`present: true`). */
data class StationFeatures(val gnss: Boolean = false, val lora: Boolean = false) {
    companion object {
        val NONE = StationFeatures()
    }
}

object NodeTabs {

    /** The node has an agent the app can talk to directly or through its ground station. */
    fun hasAgentLink(node: NodeSummary): Boolean = node.reaches.any { it == Reach.Lan || it is Reach.ViaGround }

    /**
     * Tabs per node profile, mirroring the desktop node surfaces:
     * Drone = Overview, Cockpit, Setup, Parameters, Logs, Agent;
     * Visor = Overview, Capture, Sessions, Logs, Agent;
     * Ground station = Overview, Cockpit, Carried drones, Radio, Network, GNSS and
     * LoRa (when fitted), Mesh, Logs, Agent;
     * Workstation = Overview, Logs, Agent.
     * Agent-backed tabs need an agent link; a flight controller reached directly
     * has none and is a drone. An agent of unknown profile is not guessed at: it
     * gets the Agent tab only.
     */
    fun tabsFor(node: NodeSummary, station: StationFeatures = StationFeatures.NONE): List<NodeTab> {
        val agent = hasAgentLink(node)
        val profile = when {
            node.profile != NodeProfile.Unknown -> node.profile
            Reach.DirectFc in node.reaches -> NodeProfile.Drone
            else -> NodeProfile.Unknown
        }
        val tabs = when (profile) {
            NodeProfile.Drone -> listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.Setup, NodeTab.Parameters, NodeTab.Logs, NodeTab.Agent)
            NodeProfile.GroundStation -> buildList {
                addAll(listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.CarriedDrones, NodeTab.Radio, NodeTab.Network))
                if (station.gnss) add(NodeTab.Gnss)
                if (station.lora) add(NodeTab.Lora)
                addAll(listOf(NodeTab.Mesh, NodeTab.Logs, NodeTab.Agent))
            }
            NodeProfile.Visor -> listOf(NodeTab.Overview, NodeTab.Capture, NodeTab.Sessions, NodeTab.Logs, NodeTab.Agent)
            NodeProfile.Workstation -> listOf(NodeTab.Overview, NodeTab.Logs, NodeTab.Agent)
            NodeProfile.Unknown -> listOf(NodeTab.Agent)
        }
        val filtered = if (agent) tabs else tabs.filterNot { it in AGENT_ONLY }
        // A node with nothing reachable still shows who it is.
        return filtered.ifEmpty { listOf(NodeTab.Overview) }
    }

    private val AGENT_ONLY = setOf(
        NodeTab.Logs, NodeTab.Agent, NodeTab.Capture, NodeTab.Sessions, NodeTab.CarriedDrones,
        NodeTab.Radio, NodeTab.Network, NodeTab.Gnss, NodeTab.Lora, NodeTab.Mesh,
    )
}
