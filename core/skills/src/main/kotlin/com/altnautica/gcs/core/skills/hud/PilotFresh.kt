package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PilotState

/**
 * True while a pilot frame received at [frameAtMs] is younger than the spec's stale age
 * (`flight-state.json` `stale_after_ms`) at [nowMs], on the same clock; null means no frame.
 * A port of the shared web module `hud/pilot-fresh.ts`. While it is false, flow countdowns,
 * the gate ring, the handover banner, the stick session, AGL and the pill's pilot items hide
 * or dim instead of reading as live.
 */
fun pilotFresh(frameAtMs: Long?, nowMs: Long, staleAfterMs: Long = PilotState.STALE_MS): Boolean =
    frameAtMs != null && nowMs - frameAtMs < staleAfterMs
