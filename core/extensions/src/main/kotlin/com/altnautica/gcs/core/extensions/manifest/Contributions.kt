package com.altnautica.gcs.core.extensions.manifest

import com.altnautica.gcs.core.extensions.parameters.ParameterParser
import com.altnautica.gcs.core.extensions.parameters.PluginParameter
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Slots an extension can mount into; each maps to a `ui.slot.<kebab>` capability. */
object ExtensionSlots {
    const val NODE_DETAIL_TAB = "node.detail.tab"
    const val COCKPIT_PANEL = "cockpit.panel"
    const val COCKPIT_WIDGET = "cockpit.widget"
    const val VIDEO_OVERLAY = "video.overlay"
    const val MAP_OVERLAY = "map.overlay"
    const val NOTIFICATION_CHANNEL = "notification.channel"
    const val FLIGHT_SKILL = "flight.skill"
    const val NODE_AGENT_PAGE = "node.agent.page"
    const val NODE_SURFACE = "node.surface"

    val ALL: Set<String> = setOf(
        "fc.tab", "hardware.tab", "mission.template", MAP_OVERLAY, VIDEO_OVERLAY, NOTIFICATION_CHANNEL,
        "settings.section", NODE_DETAIL_TAB, COCKPIT_PANEL, COCKPIT_WIDGET, FLIGHT_SKILL,
        NODE_AGENT_PAGE, NODE_SURFACE,
    )

    private val PROFILE_NARROWED = setOf(NODE_DETAIL_TAB, NODE_AGENT_PAGE, NODE_SURFACE)

    fun capability(slot: String): String = "ui.slot." + slot.replace('.', '-')

    /** Whether a contribution at [slot] with profile list [profile] mounts on [nodeProfile]. */
    fun offersOnProfile(slot: String, profile: List<String>?, nodeProfile: String?): Boolean {
        if (slot !in PROFILE_NARROWED) return true
        if (profile.isNullOrEmpty() || nodeProfile == null) return true
        return nodeProfile in profile
    }
}

enum class ExtensionSkillCategory { Behavior, Camera, Navigation, Utility }

data class SkillContribution(
    val id: String,
    val label: String,
    val icon: String,
    val category: ExtensionSkillCategory,
    val toggle: Boolean,
    val confirm: Boolean,
    /** `any` | `armed` | `disarmed`, or null when undeclared. */
    val armRequirement: String?,
    val configKey: String,
    val stateTopic: String,
    val defaultKey: String?,
    val defaultGamepadButton: Int?,
)

data class TargetActionContribution(
    val id: String,
    val label: String?,
    val icon: String?,
    val order: Double?,
    val appliesToClass: String?,
    val designate: Boolean?,
    val configKey: String?,
    val configValue: Boolean?,
    val defaultKey: String?,
)

/** One iframe slot row (panels, overlays, notifications, tabs, agent pages, node surfaces). */
data class SlotContribution(
    val slot: String,
    val panelId: String,
    val title: String? = null,
    val icon: String? = null,
    val order: Double? = null,
    val profile: List<String>? = null,
    val section: String? = null,
    val after: String? = null,
    val group: String? = null,
    val setupFor: String? = null,
    /** `cockpit.widget` zone; absent means `bottom-left`. */
    val zone: String? = null,
    val entrypoint: String? = null,
)

data class SettingsContribution(
    val id: String,
    val title: String?,
    val icon: String?,
    val order: Double?,
    val parameters: List<PluginParameter>,
)

data class ModelBoardVariant(
    val boardMatch: String?,
    val runtime: String?,
    val input: String?,
    val minTops: Double?,
    val source: String?,
    val sha256: String?,
)

data class ModelContribution(val id: String, val task: String?, val boardVariants: List<ModelBoardVariant>)

data class EntrypointContribution(val id: String, val title: String?, val icon: String?, val entrypoint: String?)

/** Everything an extension's `gcs.contributes` block declares, parsed and validated. */
data class ExtensionContributions(
    val skills: List<SkillContribution> = emptyList(),
    val targetActions: List<TargetActionContribution> = emptyList(),
    val parameters: List<PluginParameter> = emptyList(),
    val slots: List<SlotContribution> = emptyList(),
    val settings: List<SettingsContribution> = emptyList(),
    val models: List<ModelContribution> = emptyList(),
    val missionTemplates: List<EntrypointContribution> = emptyList(),
    val mapOverlays: List<EntrypointContribution> = emptyList(),
)

object ContributionParser {

    private val NODE_PROFILES = listOf("drone", "ground-station", "workstation", "compute")
    private val SURFACE_GROUPS = setOf("status", "vehicle", "link", "device", "compute")
    private val PAGE_ID = Regex("^[a-z0-9-]{1,48}$")

    fun parse(contributes: JsonElement?): ExtensionContributions {
        val c = contributes.asObject() ?: return ExtensionContributions()
        val slots = ArrayList<SlotContribution>()
        collectSlots(c["panels"], null, slots)
        collectSlots(c["overlays"], ExtensionSlots.VIDEO_OVERLAY, slots)
        collectSlots(c["notifications"], ExtensionSlots.NOTIFICATION_CHANNEL, slots)
        slots += parseTabs(c["tabs"])
        slots += parseAgentPages(c["agent_pages"])
        slots += parseNodeSurfaces(c["node_surfaces"])
        return ExtensionContributions(
            skills = parseSkills(c["skills"]),
            targetActions = parseTargetActions(c.either("target_actions", "targetActions")),
            parameters = ParameterParser.parse(c["parameters"]),
            slots = slots,
            settings = parseSettings(c["settings"]),
            models = parseModels(c["models"]),
            missionTemplates = parseEntrypoints(c.either("mission_templates", "missionTemplates")),
            mapOverlays = parseEntrypoints(c.either("map_overlays", "mapOverlays")),
        )
    }

    fun readProfileList(v: JsonElement?): List<String>? {
        val arr = v.asArray() ?: return null
        val out = ArrayList<String>()
        for (e in arr) {
            val s = e.str() ?: continue
            if (s in NODE_PROFILES && s !in out) out += s
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Only config-write activation with event-read state is supported; others drop. */
    fun parseSkills(v: JsonElement?): List<SkillContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e["id"].str() ?: return@mapNotNull null
        val activation = e["activation"].asObject()
        val state = e["state"].asObject()
        val configKey = activation?.either("config_key", "configKey").str()
        val topic = state?.get("topic").str()
        if (activation?.get("via").str() != "config" || state?.get("via").str() != "event" ||
            configKey.isNullOrEmpty() || topic.isNullOrEmpty()
        ) {
            return@mapNotNull null
        }
        val binding = e.either("default_binding", "defaultBinding").asObject()
        val arm = e.either("arm_requirement", "armRequirement").str()?.lowercase()
        SkillContribution(
            id = id,
            label = e["label"].str() ?: id,
            icon = e["icon"].str() ?: "Sparkles",
            category = when (e["category"].str()?.lowercase()) {
                "camera" -> ExtensionSkillCategory.Camera
                "navigation" -> ExtensionSkillCategory.Navigation
                "utility" -> ExtensionSkillCategory.Utility
                else -> ExtensionSkillCategory.Behavior
            },
            toggle = e["toggle"].bool() == true,
            confirm = e["confirm"].bool() == true,
            armRequirement = arm?.takeIf { it == "any" || it == "armed" || it == "disarmed" },
            configKey = configKey,
            stateTopic = topic,
            defaultKey = binding?.get("key").str(),
            defaultGamepadButton = binding?.either("gamepad_button", "gamepadButton").num()?.toInt(),
        )
    }

    fun parseTargetActions(v: JsonElement?): List<TargetActionContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e["id"].str() ?: return@mapNotNull null
        TargetActionContribution(
            id = id,
            label = e["label"].str(),
            icon = e["icon"].str(),
            order = e["order"].num(),
            appliesToClass = e.either("applies_to_class", "appliesToClass").str(),
            designate = e["designate"].bool(),
            configKey = e.either("config_key", "configKey").str(),
            configValue = e.either("config_value", "configValue").bool(),
            defaultKey = e.either("default_key", "defaultKey").str(),
        )
    }

    private fun collectSlots(source: JsonElement?, defaultSlot: String?, into: MutableList<SlotContribution>) {
        for (raw in source.asArray().orEmpty()) {
            val e = raw.asObject() ?: continue
            val panelId = e["id"].str() ?: continue
            val slot = e["slot"].str() ?: defaultSlot ?: continue
            if (slot !in ExtensionSlots.ALL) continue
            into += SlotContribution(
                slot = slot,
                panelId = panelId,
                title = e["title"].str(),
                icon = e["icon"].str(),
                order = e["order"].num(),
                zone = if (slot == ExtensionSlots.COCKPIT_WIDGET) e["zone"].str() else null,
                entrypoint = e["entrypoint"].str(),
            )
        }
    }

    fun parseTabs(v: JsonElement?): List<SlotContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e.readId() ?: return@mapNotNull null
        SlotContribution(
            slot = ExtensionSlots.NODE_DETAIL_TAB,
            panelId = id,
            title = e["title"].str(),
            icon = e["icon"].str(),
            order = e["order"].num(),
            profile = readProfileList(e["profile"]),
            entrypoint = e["entrypoint"].str(),
        )
    }

    private fun text(v: JsonElement?): String? = v.str()?.takeIf { it.isNotBlank() }
    private fun pageId(v: JsonElement?): String? = v.str()?.takeIf { PAGE_ID.matches(it) }

    fun parseAgentPages(v: JsonElement?): List<SlotContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = pageId(e["id"]) ?: return@mapNotNull null
        val title = text(e["title"]) ?: return@mapNotNull null
        SlotContribution(
            slot = ExtensionSlots.NODE_AGENT_PAGE,
            panelId = id,
            title = title,
            icon = text(e["icon"]),
            order = e["order"].num(),
            profile = readProfileList(e["profile"]),
            section = text(e["section"]),
            after = pageId(e["after"]),
            setupFor = pageId(e["setup_for"])?.takeIf { it != id },
        )
    }

    fun parseNodeSurfaces(v: JsonElement?): List<SlotContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = pageId(e["id"]) ?: return@mapNotNull null
        val title = text(e["title"]) ?: return@mapNotNull null
        val profile = readProfileList(e["profile"]) ?: return@mapNotNull null
        SlotContribution(
            slot = ExtensionSlots.NODE_SURFACE,
            panelId = id,
            title = title,
            profile = profile,
            group = e["group"].str()?.takeIf { it in SURFACE_GROUPS },
            order = e["order"].num(),
        )
    }

    fun parseSettings(v: JsonElement?): List<SettingsContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e.readId() ?: return@mapNotNull null
        SettingsContribution(id, e["title"].str(), e["icon"].str(), e["order"].num(), ParameterParser.parse(e["parameters"]))
    }

    fun parseModels(v: JsonElement?): List<ModelContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e.readId() ?: return@mapNotNull null
        val variants = e.either("board_variants", "boardVariants").asArray().orEmpty().mapNotNull { rv ->
            val b: JsonObject = rv.asObject() ?: return@mapNotNull null
            val variant = ModelBoardVariant(
                boardMatch = b.either("board_match", "boardMatch").str(),
                runtime = b["runtime"].str(),
                input = b["input"].str(),
                minTops = b.either("min_tops", "minTops").num(),
                source = b["source"].str(),
                sha256 = b["sha256"].str(),
            )
            variant.takeIf { it != ModelBoardVariant(null, null, null, null, null, null) }
        }
        ModelContribution(id, e["task"].str(), variants)
    }

    fun parseEntrypoints(v: JsonElement?): List<EntrypointContribution> = v.asArray().orEmpty().mapNotNull { raw ->
        val e = raw.asObject() ?: return@mapNotNull null
        val id = e.readId() ?: return@mapNotNull null
        EntrypointContribution(id, e["title"].str(), e["icon"].str(), e["entrypoint"].str())
    }
}
