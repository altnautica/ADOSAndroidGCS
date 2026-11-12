package com.altnautica.gcs.feature.home

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.link.node.NodeVitals
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.runningFold

/** The vitals a card shows; [keptHeardAtMs] is set when they are a reading kept after the link closed. */
internal data class ShownVitals(val vitals: NodeVitals, val keptHeardAtMs: Long? = null)

private data class Heard(val live: NodeVitals, val last: NodeVitals?, val lastHeardAtMs: Long?)

/**
 * A direct link's vitals as a card shows them. While the link reports, its reading shows. Once it
 * reports nothing, the last reading is kept only while the link is closed, stamped with when the link
 * last heard the vehicle ([NodeVitals.heardAtMs], else [clock] at the moment it stopped) and without
 * its arm state. An open link that has fallen silent shows unknown: a frozen reading must not pass for live.
 */
internal fun Flow<NodeVitals>.keptWhileClosed(linkOpen: Flow<Boolean>, clock: () -> Long): Flow<ShownVitals> =
    runningFold(Heard(NodeVitals.UNKNOWN, null, null)) { h, v ->
        when {
            v != NodeVitals.UNKNOWN -> Heard(v, v, null)
            h.last != null && h.lastHeardAtMs == null -> Heard(v, h.last, h.last.heardAtMs ?: clock())
            else -> h.copy(live = v)
        }
    }.combine(linkOpen) { h, open ->
        // The heard time changes with every heartbeat; a card shows the reading, not the stamp.
        val last = h.last?.copy(heardAtMs = null)
        if (h.live != NodeVitals.UNKNOWN || open || last == null) {
            ShownVitals(h.live.copy(heardAtMs = null))
        } else {
            ShownVitals(last.copy(armed = null), h.lastHeardAtMs)
        }
    }.distinctUntilChanged()

/** "Last heard 40 s ago" for a kept reading heard at [heardAtMs] (`elapsedRealtime`), ticking each second. */
@Composable
internal fun keptAgeLabel(heardAtMs: Long): String {
    val now by produceState(SystemClock.elapsedRealtime(), heardAtMs) {
        while (true) {
            delay(AGE_TICK_MS)
            value = SystemClock.elapsedRealtime()
        }
    }
    val ageS = ((now - heardAtMs) / 1_000).coerceAtLeast(0)
    return when {
        ageS < 60 -> stringResource(R.string.home_vitals_heard_s, ageS.toInt())
        ageS < 3_600 -> stringResource(R.string.home_vitals_heard_min, (ageS / 60).toInt())
        else -> stringResource(R.string.home_vitals_heard_h, (ageS / 3_600).toInt())
    }
}

private const val AGE_TICK_MS = 1_000L
