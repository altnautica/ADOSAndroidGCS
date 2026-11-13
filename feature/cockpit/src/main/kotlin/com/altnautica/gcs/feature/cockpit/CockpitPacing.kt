package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.feature.cockpit.band.BatteryReserve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The agent battery engine's return reserve, asked every [BatteryReserve.POLL_MS]; null without an engine. */
internal fun batteryReservePolls(api: suspend () -> AutonomyFeedApi?): Flow<BatteryReserve?> = flow {
    while (true) {
        val r = api()?.let { runCatching { it.battery() }.getOrNull() }
        emit(BatteryReserve.from((r as? AgentResult.Ok)?.value))
        delay(BatteryReserve.POLL_MS)
    }
}

/**
 * [source]'s newest value, published only when [tick] fires: a value that changes on its own
 * clock (a poll, say) reaches the screen on the 2 Hz tick, in the same frame as the rest.
 * [source] is collected for the scope's life.
 */
internal fun <T> onTick(scope: CoroutineScope, source: Flow<T>, tick: StateFlow<Long>, initial: T): StateFlow<T> {
    val latest = source.stateIn(scope, SharingStarted.Eagerly, initial)
    return tick.map { latest.value }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, initial)
}
