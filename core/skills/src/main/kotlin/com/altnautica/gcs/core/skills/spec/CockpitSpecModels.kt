package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The cockpit spec shared by every cockpit surface. Field names follow the JSON files
// in `assets/cockpit-spec/` exactly; every file is an object with a top-level "v": 1.

/** Confirm tier of a built-in or a Mode-sheet control. */
@Serializable
enum class SpecConfirmKind {
    @SerialName("tap") Tap,
    @SerialName("hold") Hold,
    @SerialName("hold_confirm") HoldConfirm,
}

@Serializable
data class SpecConfirm(val kind: SpecConfirmKind, val ms: Int = 0)

@Serializable
enum class SpecArmRequirement {
    @SerialName("any") Any,
    @SerialName("armed") Armed,
    @SerialName("disarmed") Disarmed,
    @SerialName("armed_landed") ArmedLanded,
    @SerialName("disarmed_or_landed") DisarmedOrLanded,
}

/** What a built-in does while the link is stale: refuse with a reason, or send anyway. */
@Serializable
enum class SpecStaleLink {
    @SerialName("disable") Disable,
    @SerialName("send_blind") SendBlind,
}
