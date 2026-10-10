package com.altnautica.gcs.core.cloud.grant

/** How FC frames for a node currently travel. Only the cloud relay goes through the broker. */
enum class ControlLane { Direct, CloudRelay, None }

/** One capability from the operator's point of view. */
enum class CapabilityState {
    /** Usable, and proven usable. */
    Available,

    /** A grant is held but no write under it has been accepted yet. */
    Unconfirmed,

    /** Being obtained; never reads as ready. */
    Provisioning,

    /** Usable now, renewal has failed, and the lapse is close. */
    Expiring,

    /** Not usable. */
    Unavailable,
}

enum class AuthorityReason { DirectLink, NoTransport, NoGrant, Provisioning, GrantExpired, GrantExpiring, GrantUnconfirmed, GrantActive }

data class MqttControlAuthority(
    val fcFrames: CapabilityState,
    val videoSignaling: CapabilityState,
    val reason: AuthorityReason,
    val expiresAtMs: Long?,
) {
    /** Flight-affecting publishes are allowed (hedged when unconfirmed or expiring). */
    val canPublish: Boolean
        get() = fcFrames == CapabilityState.Available ||
            fcFrames == CapabilityState.Unconfirmed ||
            fcFrames == CapabilityState.Expiring

    companion object {
        /** How long before expiry a failed renewal starts warning. */
        const val EXPIRY_WARNING_MS = 5 * 60 * 1000L

        /**
         * Whether this install may publish FC frames and video offers for
         * [deviceId] over the broker. Pure: every boundary is reproducible in a test.
         */
        fun resolve(lane: ControlLane, deviceId: String, state: GrantState, nowMs: Long): MqttControlAuthority {
            fun of(s: CapabilityState, r: AuthorityReason, exp: Long?) = MqttControlAuthority(s, s, r, exp)
            return when (lane) {
                ControlLane.None -> of(CapabilityState.Unavailable, AuthorityReason.NoTransport, null)
                ControlLane.Direct -> of(CapabilityState.Available, AuthorityReason.DirectLink, null)
                ControlLane.CloudRelay -> {
                    val held = state.grant?.takeIf { deviceId in it.deviceIds }
                    val minting = state.minting
                    when {
                        held == null -> if (minting) {
                            of(CapabilityState.Provisioning, AuthorityReason.Provisioning, null)
                        } else {
                            of(CapabilityState.Unavailable, AuthorityReason.NoGrant, null)
                        }
                        held.expiresAtMs <= nowMs -> if (minting) {
                            of(CapabilityState.Provisioning, AuthorityReason.Provisioning, held.expiresAtMs)
                        } else {
                            of(CapabilityState.Unavailable, AuthorityReason.GrantExpired, held.expiresAtMs)
                        }
                        state.renewalFailed && held.expiresAtMs - nowMs <= EXPIRY_WARNING_MS ->
                            of(CapabilityState.Expiring, AuthorityReason.GrantExpiring, held.expiresAtMs)
                        !held.writeConfirmed -> of(CapabilityState.Unconfirmed, AuthorityReason.GrantUnconfirmed, held.expiresAtMs)
                        else -> of(CapabilityState.Available, AuthorityReason.GrantActive, held.expiresAtMs)
                    }
                }
            }
        }
    }
}
