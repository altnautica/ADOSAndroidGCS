package com.altnautica.gcs.core.demo.skills

import android.os.SystemClock
import com.altnautica.gcs.core.demo.DemoIds
import com.altnautica.gcs.core.demo.DemoSettings
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetailParser
import com.altnautica.gcs.core.extensions.skills.ExtensionCockpitFocus
import com.altnautica.gcs.core.extensions.skills.ExtensionConfigWriter
import com.altnautica.gcs.core.extensions.skills.ExtensionSkill
import com.altnautica.gcs.core.extensions.skills.ReportedState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.SkillContributor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The demo drone's optical pod extension, run in-process: the eight camera skills of the
 * pod extension, declared by the same manifest shape an agent reports (`GET /api/plugins/{id}`)
 * and built into [ExtensionSkill]s exactly like an installed extension's. A config write is
 * echoed on the skill's state topic after [ECHO_MS], so the pending ring and the echo rules
 * apply. With [DemoSettings.staleSkill] on, the pod stops reporting [STALE_SKILL], whose slot
 * then dims and shows when it was last seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DemoPodSkills internal constructor(
    focus: ExtensionCockpitFocus,
    settings: DemoSettings,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) : SkillContributor {

    @Inject
    constructor(focus: ExtensionCockpitFocus, settings: DemoSettings) :
        this(focus, settings, CoroutineScope(SupervisorJob() + Dispatchers.Default), { SystemClock.elapsedRealtime() })

    val detail: ExtensionDetail = requireNotNull(ExtensionDetailParser.parse(Json.parseToJsonElement(DETAIL_JSON)))

    /** Payloads published per state topic. */
    private val published = MutableStateFlow<Map<String, JsonElement>>(emptyMap())

    /** When the stale skill's state was last reported; frozen while the stale setting is on. */
    private var staleSince: Long? = null

    override val skills: Flow<List<ExtensionSkill>> = focus.node.flatMapLatest { node ->
        if (node != DemoIds.DRONE) {
            flowOf(emptyList())
        } else {
            combine(published, settings.staleSkill) { state, stale -> build(node, state, stale) }
        }
    }.distinctUntilChanged()

    internal fun build(node: DeviceId, state: Map<String, JsonElement>, stale: Boolean): List<ExtensionSkill> {
        val frozenAt = if (stale) (staleSince ?: nowMs().also { staleSince = it }) else null.also { staleSince = null }
        return detail.contributions.skills.map { c ->
            ExtensionSkill(
                node = node,
                pluginId = detail.pluginId,
                contribution = c,
                reach = ExtensionReach.Lan,
                reported = ReportedState.from(state[c.stateTopic]),
                writer = writer,
                pluginName = detail.name,
                lastSeenMs = if (frozenAt != null && c.id == STALE_SKILL) ({ frozenAt }) else nowMs,
                nowMs = nowMs,
            )
        }
    }

    /** Applies a config write and echoes the matching skill's state, as the pod extension does. */
    private val writer = ExtensionConfigWriter { key, value ->
        val skill = detail.contributions.skills.firstOrNull { it.configKey == key }
        if (skill != null) {
            val on = (value as? JsonPrimitive)?.booleanOrNull == true
            scope.launch {
                delay(ECHO_MS)
                // Toggles report their new state; one-shots fire and report idle again.
                val active = skill.toggle && on
                published.update { it + (skill.stateTopic to buildJsonObject { put("state", if (active) "active" else "idle") }) }
            }
        }
        WriteOutcome.Done
    }

    internal companion object {
        const val ECHO_MS = 300L

        /** The skill whose state the stale setting withholds. */
        const val STALE_SKILL = "siyi-record"

        /**
         * The pod extension's manifest as an agent reports an installed, running extension.
         * Skills carry every required manifest skill field; labels are literal (the manifest's English
         * locale strings), which the manifest schema allows in place of i18n keys.
         */
        val DETAIL_JSON = """
            {
              "install": {"plugin_id": "com.altnautica.siyi-pod", "status": "running", "version": "0.5.3"},
              "manifest": {
                "id": "com.altnautica.siyi-pod",
                "name": "ADOS SIYI Optical Pod",
                "version": "0.5.3",
                "gcs": {
                  "isolation": "iframe",
                  "contributes": {
                    "skills": [
                      ${skill("siyi-record", "Record", "video", toggle = true, confirm = false, key = "r", config = "recording", topic = "siyi.pod.record")},
                      ${skill("cycle-palette", "Cycle palette", "palette", toggle = false, confirm = false, key = "b", config = "palette_cycle", topic = "siyi.pod.palette")},
                      ${skill("fire-lrf", "Fire rangefinder", "scan-line", toggle = false, confirm = true, key = "l", config = "laser_fire", topic = "siyi.pod.laser")},
                      ${skill("center", "Center gimbal", "locate-fixed", toggle = false, confirm = false, key = "c", config = "center", topic = "siyi.pod.gimbal")},
                      ${skill("nadir", "Nadir", "arrow-down-to-line", toggle = false, confirm = false, key = "n", config = "nadir", topic = "siyi.pod.gimbal")},
                      ${skill("zoom-in", "Zoom in", "zoom-in", toggle = false, confirm = false, key = "]", config = "zoom_in", topic = "siyi.pod.zoom")},
                      ${skill("zoom-out", "Zoom out", "zoom-out", toggle = false, confirm = false, key = "[", config = "zoom_out", topic = "siyi.pod.zoom")},
                      ${skill("photo", "Photo", "camera", toggle = false, confirm = false, key = "k", config = "photo", topic = "siyi.pod.photo")}
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        private fun skill(
            id: String,
            label: String,
            icon: String,
            toggle: Boolean,
            confirm: Boolean,
            key: String,
            config: String,
            topic: String,
        ) = """
            {"id": "$id", "label": "$label", "icon": "$icon", "category": "camera",
             "toggle": $toggle, "confirm": $confirm, "arm_requirement": "any",
             "default_binding": {"key": "$key"},
             "activation": {"via": "config", "config_key": "$config"},
             "state": {"via": "event", "topic": "$topic"}}
        """.trimIndent()
    }
}
