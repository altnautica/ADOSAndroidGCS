package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.MissionStatus
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState

/** A pilot state with the fields the cockpit rules read; everything else empty. */
fun pilotState(
    owner: PilotOwner,
    armed: Boolean = owner != PilotOwner.Idle,
    landed: Boolean = !armed,
    fcModeName: String? = "GUIDED",
    fcMode: Int? = null,
    controlMode: ControlMode = ControlMode.Ados,
    mission: MissionState? = null,
    receivedAtMs: Long = 0L,
): PilotState = PilotState(
    owner = owner,
    ownerLabel = null,
    skill = null,
    controlMode = controlMode,
    profile = PilotProfile.Normal,
    avoid = null,
    fcMode = fcMode,
    fcModeName = fcModeName,
    armed = armed,
    landed = landed,
    inputSrc = null,
    inputAgeMs = null,
    holdReason = null,
    avoidState = null,
    minClearanceM = null,
    goalNed = null,
    planNed = emptyList(),
    setpointNed = null,
    readiness = emptyList(),
    linkLossInS = null,
    mission = mission?.let { MissionStatus(it, 2, 6, null, null, true, 0L, false, null) },
    vehicleClass = null,
    inputSession = null,
    gate = null,
    sequence = null,
    banner = null,
    rthCountdownS = null,
    receivedAtMs = receivedAtMs,
)
