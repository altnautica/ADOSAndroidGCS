package com.altnautica.gcs.feature.fleet.data

import android.util.Log
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Every node the app knows, from every link family (LAN agents, direct flight
 * controllers, cloud, phone radio), merged by [DeviceId] (see [NodeMerger]).
 *
 * [nodes] is null until each source has had a chance to emit, so screens can
 * tell "still looking" from "nothing found". Sources run only while observed.
 */
@Singleton
class NodeRepository internal constructor(
    sources: Set<NodeSource>,
    scope: CoroutineScope,
) {

    @Inject
    constructor(sources: Set<@JvmSuppressWildcards NodeSource>) :
        this(sources, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val merged: Flow<List<NodeSummary>> = if (sources.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(
            sources.map { source ->
                source.nodes
                    .onStart { emit(emptyList()) }
                    .catch { e ->
                        Log.w(TAG, "node source ${source::class.simpleName} failed", e)
                        emit(emptyList())
                    }
            },
        ) { lists -> NodeMerger.merge(lists.toList()) }
    }

    val nodes: StateFlow<List<NodeSummary>?> = merged
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MS), null)

    fun node(id: DeviceId): Flow<NodeSummary?> = nodes.map { list -> list?.firstOrNull { it.id == id } }.distinctUntilChanged()

    private companion object {
        const val TAG = "NodeRepository"
        const val STOP_GRACE_MS = 5_000L
    }
}
