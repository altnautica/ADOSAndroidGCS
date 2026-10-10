package com.altnautica.gcs.feature.node

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeTabsTest {

    private fun node(profile: NodeProfile, vararg reaches: Reach) = NodeSummary(
        DeviceId("n"), "n", profile, reaches.toList(), Liveness.Live, null, FcVariant.Unknown, null, null,
    )

    @Test
    fun droneOnLanHasFullSet() {
        assertEquals(
            listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.Setup, NodeTab.Parameters, NodeTab.Logs, NodeTab.Agent),
            NodeTabs.tabsFor(node(NodeProfile.Drone, Reach.Lan)),
        )
    }

    @Test
    fun groundStationHasStationTabs() {
        assertEquals(
            listOf(
                NodeTab.Overview, NodeTab.Cockpit, NodeTab.CarriedDrones, NodeTab.Radio,
                NodeTab.Network, NodeTab.Mesh, NodeTab.Logs, NodeTab.Agent,
            ),
            NodeTabs.tabsFor(node(NodeProfile.GroundStation, Reach.Lan)),
        )
    }

    @Test
    fun workstationHasOverviewLogsAgent() {
        assertEquals(listOf(NodeTab.Overview, NodeTab.Logs, NodeTab.Agent), NodeTabs.tabsFor(node(NodeProfile.Workstation, Reach.Lan)))
    }

    @Test
    fun directFlightControllerDropsAgentTabsAndIsADrone() {
        assertEquals(
            listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.Setup, NodeTab.Parameters),
            NodeTabs.tabsFor(node(NodeProfile.Unknown, Reach.DirectFc)),
        )
    }

    @Test
    fun carriedDroneViaGroundKeepsAgentTabs() {
        val tabs = NodeTabs.tabsFor(node(NodeProfile.Drone, Reach.ViaGround(DeviceId("gs"))))
        assertEquals(NodeTab.Agent, tabs.last())
    }

    @Test
    fun cloudOnlyDroneHasNoAgentTabs() {
        assertEquals(
            listOf(NodeTab.Overview, NodeTab.Cockpit, NodeTab.Setup, NodeTab.Parameters),
            NodeTabs.tabsFor(node(NodeProfile.Drone, Reach.CloudLive)),
        )
    }
}
