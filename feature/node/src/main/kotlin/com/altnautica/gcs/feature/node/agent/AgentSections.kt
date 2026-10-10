package com.altnautica.gcs.feature.node.agent

import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.feature.node.R

/** Live readout block a section shows above its form. */
enum class SectionLive { SystemResources, Battery, WfbLink, CameraRoster, Services, None }

/**
 * One Agent page section, in the same order and grouping as the Agent page
 * sidebar on the desktop GCS: Node, Radio link, Networking, Fleet, Video &
 * vision, Cloud, System, Extensions.
 *
 * [fields] are the typed form (dotted config keys; their editor comes from
 * the agent's config schema, and a key the agent's schema lacks is skipped).
 * [subtrees] scope the section's "All settings" config tree.
 */
data class AgentSection(
    val id: String,
    val titleRes: Int,
    val live: List<SectionLive>,
    val fields: List<String>,
    val subtrees: List<String>,
    /** Keys shown only on a ground station. */
    val groundOnlyFields: List<String> = emptyList(),
    val groundOnlySubtrees: List<String> = emptyList(),
) {
    fun fieldsFor(profile: NodeProfile): List<String> =
        if (profile == NodeProfile.GroundStation) fields + groundOnlyFields else fields

    fun subtreesFor(profile: NodeProfile): List<String> =
        if (profile == NodeProfile.GroundStation) subtrees + groundOnlySubtrees else subtrees

    val isExtensions: Boolean get() = id == EXTENSIONS

    companion object {
        const val EXTENSIONS = "extensions"

        val ALL: List<AgentSection> = listOf(
            AgentSection(
                id = "node",
                titleRes = R.string.agent_section_node,
                live = listOf(SectionLive.SystemResources, SectionLive.Battery),
                fields = listOf(
                    "agent.name",
                    "battery.enabled",
                    "battery.reserve_percent",
                    "battery.low_cell_mv",
                    "battery.critical_cell_mv",
                ),
                subtrees = listOf("agent", "battery"),
            ),
            AgentSection(
                id = "radioLink",
                titleRes = R.string.agent_section_radio,
                live = listOf(SectionLive.WfbLink),
                fields = listOf(
                    "video.wfb.band",
                    "video.wfb.wfb_link_preset",
                    "video.wfb.mcs_index",
                    "video.wfb.tx_power_dbm",
                    "video.wfb.auto_channel_enabled",
                    "video.wfb.auto_hop_enabled",
                    "radio.crsf.enabled",
                    "radio.crsf.mode",
                ),
                subtrees = listOf("video.wfb", "radio"),
            ),
            AgentSection(
                id = "networking",
                titleRes = R.string.agent_section_networking,
                live = listOf(SectionLive.None),
                fields = listOf(
                    "network.hotspot.enabled",
                    "network.hotspot.ssid",
                    "network.hotspot.password",
                    "network.hotspot.channel",
                    "network.wifi_client.enabled",
                    "network.wifi_client.ssid",
                    "network.wifi_client.password",
                    "network.cellular.enabled",
                    "network.cellular.apn",
                    "network.mac_pin.enabled",
                    "discovery.mdns_enabled",
                    "mavlink.source",
                    "mavlink.baud_rate",
                ),
                subtrees = listOf("network", "discovery", "mavlink", "api"),
            ),
            AgentSection(
                id = "fleet",
                titleRes = R.string.agent_section_fleet,
                live = listOf(SectionLive.None),
                fields = listOf("swarm.enabled", "swarm.mode", "swarm.default_formation", "swarm.default_spacing"),
                subtrees = listOf("swarm"),
            ),
            AgentSection(
                id = "videoVision",
                titleRes = R.string.agent_section_video,
                live = listOf(SectionLive.CameraRoster),
                fields = listOf(
                    "video.camera.codec",
                    "video.camera.width",
                    "video.camera.height",
                    "video.camera.fps",
                    "video.camera.bitrate_kbps",
                    "video.recording.enabled",
                    "vision.enabled",
                    "vision.confidence_threshold",
                ),
                subtrees = listOf("video.camera", "video.recording", "video.usb_recovery", "vision"),
            ),
            AgentSection(
                id = "cloud",
                titleRes = R.string.agent_section_cloud,
                live = listOf(SectionLive.None),
                fields = listOf("server.mode", "pairing.beacon_enabled", "remote_access.provider"),
                subtrees = listOf("server", "pairing", "remote_access"),
                groundOnlyFields = listOf("ground_station.cloud_uplink"),
            ),
            AgentSection(
                id = "system",
                titleRes = R.string.agent_section_system,
                live = listOf(SectionLive.Services),
                fields = listOf(
                    "logging.level",
                    "logging.store.enabled",
                    "network.regulatory.mode",
                    "network.regulatory.region",
                    "network.wifi_selfheal.enabled",
                    "security.setup_token_required",
                ),
                subtrees = listOf("logging", "security", "ui"),
                groundOnlyFields = listOf("ground_station.display.type", "ground_station.kiosk.enabled"),
                groundOnlySubtrees = listOf("ground_station"),
            ),
            AgentSection(
                id = EXTENSIONS,
                titleRes = R.string.agent_section_extensions,
                live = emptyList(),
                fields = emptyList(),
                subtrees = emptyList(),
            ),
        )
    }
}
