package com.altnautica.gcs.di

import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.video.VideoSourceFactory
import javax.inject.Inject

/**
 * Names every implementation bound into the link-family multibinding sets, so
 * a debug build can prove at startup that each module contributed its bindings.
 */
class LinkGraphReport @Inject constructor(
    private val nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    private val transports: Set<@JvmSuppressWildcards TransportProvider>,
    private val videoFactories: Set<@JvmSuppressWildcards VideoSourceFactory>,
    private val skillContributors: Set<@JvmSuppressWildcards SkillContributor>,
    private val queuedSinks: Set<@JvmSuppressWildcards QueuedCommandSink>,
) {
    fun lines(): List<String> = listOf(
        line("NodeSource", nodeSources),
        line("TransportProvider", transports),
        line("VideoSourceFactory", videoFactories),
        line("SkillContributor", skillContributors),
        line("QueuedCommandSink", queuedSinks),
    )

    internal companion object {
        fun line(name: String, items: Set<Any>): String =
            "$name[${items.size}]: " + items.map { it::class.java.simpleName }.sorted().joinToString(", ")
    }
}
