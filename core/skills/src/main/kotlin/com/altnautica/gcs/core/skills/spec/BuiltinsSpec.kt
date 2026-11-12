package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BuiltinsSpec(
    val v: Int,
    @SerialName("arm_requirements") val armRequirements: Map<String, String> = emptyMap(),
    @SerialName("confirm_kinds") val confirmKinds: Map<String, String> = emptyMap(),
    val builtins: List<BuiltinSpec>,
    val removed: List<String> = emptyList(),
    @SerialName("profile_keys") val profileKeys: Map<String, String> = emptyMap(),
    @SerialName("fc_mode_presets") val fcModePresets: String? = null,
)

@Serializable
data class BuiltinSpec(
    val id: String,
    val label: String,
    val icon: String,
    @SerialName("arm_requirement") val armRequirement: SpecArmRequirement,
    val confirm: SpecConfirm,
    @SerialName("needs_fresh_link") val needsFreshLink: Boolean,
    @SerialName("stale_link") val staleLink: SpecStaleLink,
    val keyboard: String? = null,
    @SerialName("arms_first") val armsFirst: Boolean = false,
    @SerialName("pilot_action") val pilotAction: String,
    val notes: String? = null,
)
