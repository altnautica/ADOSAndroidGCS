package com.altnautica.gcs.core.link.node

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.ReadinessItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The onboard pilot's latest readiness per vehicle, as the cockpit holds it: the cockpit
 * publishes each pilot frame's items, and Aircraft setup reads them to flag the cards the
 * flight controller reports as needing a fix.
 */
@Singleton
class PilotReadinessStore @Inject constructor() {
    private val items = MutableStateFlow<Map<DeviceId, List<ReadinessItem>>>(emptyMap())

    fun publish(id: DeviceId, readiness: List<ReadinessItem>) = items.update { it + (id to readiness) }

    fun observe(id: DeviceId): Flow<List<ReadinessItem>> = items.map { it[id].orEmpty() }.distinctUntilChanged()
}
