package com.altnautica.gcs.feature.cockpit.pilot

import androidx.compose.runtime.Immutable
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem

/**
 * The status pill's spec inputs beyond alerts and readiness, for `StatusPill.alerts`:
 * [pilotRule] while the vehicle has an agent (the pilot rule is on), the newest pilot state's
 * age ([pilotAgeMs], null before the first), whether the agent answers, whether the pilot
 * holds for a lost controller in the air, and the failing pre-flight items (on the ground only).
 */
@Immutable
data class PillFacts(
    val pilotRule: Boolean = false,
    val pilotAgeMs: Long? = null,
    val agentReachable: Boolean = true,
    val controllerLost: Boolean = false,
    val preflightFailing: List<PreflightItem> = emptyList(),
)
