package com.altnautica.gcs.feature.node

import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/** Built-in node detail tabs; extension tabs are appended after these. */
enum class NodeTab { Overview, Cockpit, Setup, Parameters, CarriedDrones, Radio, Network, Mesh, Logs, Agent }

object NodeTabs {

    /** The node has an agent the app can talk to directly or through its ground station. */
    fun hasAgentLink(node: NodeSummary): Boolean = node.reaches.any { it == Reach.Lan || it is Reach.ViaGround }

    /**
     * Tabs per node profile, mirroring the desktop node surfaces:
     * Drone = Overview, Cockpit, Setup, Parameters, Logs, Agent;
     * Ground station = Overview, Cockpit, Carried drones, Radio, Network, Mesh, Logs, Agent;
     * Workstation = Overview, Logs, Agent.
     * Logs and Agent need an agent link; a flight controller reached directly
     * has neither. A node of unknown profile on a direct link is a drone.
     */
    fun tabsFor(node: NodeSummary): List<NodeTab> {
        val agent = hasAgentLink(node)
        val profile = when {
            node.profile != NodeProfile.Unknown -> node.profile
            Reach.DirectFc in node.reaches -> NodeProfile.Drone
            else -> NodeProfile.Unknown
        }
        val tabs = when (profile) {
            NodeProfile.Drone -> listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.Setup, NodeTab.Parameters, NodeTab.Logs, NodeTab.Agent)
            NodeProfile.GroundStation -> listOf(
                NodeTab.Overview, NodeTab.Cockpit, NodeTab.CarriedDrones, NodeTab.Radio, NodeTab.Network, NodeTab.Mesh, NodeTab.Logs, NodeTab.Agent,
            )
            NodeProfile.Workstation -> listOf(NodeTab.Overview, NodeTab.Logs, NodeTab.Agent)
            NodeProfile.Unknown -> listOf(NodeTab.Overview, NodeTab.Logs, NodeTab.Agent)
        }
        val agentOnly = setOf(NodeTab.Logs, NodeTab.Agent, NodeTab.CarriedDrones, NodeTab.Radio, NodeTab.Network, NodeTab.Mesh)
        return if (agent) tabs else tabs.filterNot { it in agentOnly }
    }
}
