package com.altnautica.gcs.core.extensions.skills

import com.altnautica.gcs.core.extensions.data.ApiResult
import com.altnautica.gcs.core.extensions.data.ExtensionReach
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.ExtensionStateFeed
import com.altnautica.gcs.core.extensions.data.WriteOutcome
import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.extensions.manifest.asObject
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.SkillContributor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The node whose cockpit is on screen. Extension skills and their 750 ms state poll exist
 * only while a cockpit holds focus.
 */
@Singleton
class ExtensionCockpitFocus @Inject constructor(private val feed: ExtensionStateFeed) {
    private val focused = MutableStateFlow<DeviceId?>(null)
    val node: StateFlow<DeviceId?> = focused.asStateFlow()

    /** Focus [node] and start its state poll; the returned action releases it. */
    fun attach(node: DeviceId): () -> Unit {
        focused.value = node
        val release = feed.acquire(node)
        return {
            release()
            focused.compareAndSet(node, null)
        }
    }
}

/** Builds the skills and target actions of every enabled extension on the focused node. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ExtensionSkillContributor @Inject constructor(
    private val focus: ExtensionCockpitFocus,
    private val repository: ExtensionRepository,
    private val feed: ExtensionStateFeed,
) : SkillContributor {

    override val skills: Flow<List<ExtensionSkill>> = focus.node.flatMapLatest { node ->
        if (node == null) {
            flowOf(emptyList())
        } else {
            combine(
                repository.enabled(node),
                feed.state(node),
                repository.nodes.map { ExtensionReach.classify(it[node]?.reaches.orEmpty()) }.distinctUntilChanged(),
            ) { exts, state, reach -> buildSkills(node, exts, state, reach) }
        }
    }.distinctUntilChanged()

    /** Target actions on the focused node, gated like their skills. */
    val targetActions: Flow<List<ExtensionTargetActionSkill>> = focus.node.flatMapLatest { node ->
        if (node == null) {
            flowOf(emptyList())
        } else {
            combine(
                repository.enabled(node),
                repository.nodes.map { ExtensionReach.classify(it[node]?.reaches.orEmpty()) }.distinctUntilChanged(),
            ) { exts, reach -> buildTargetActions(node, exts, reach) }
        }
    }.distinctUntilChanged()

    internal fun buildSkills(
        node: DeviceId,
        exts: List<ExtensionDetail>,
        state: Map<String, Map<String, kotlinx.serialization.json.JsonElement>>,
        reach: ExtensionReach,
    ): List<ExtensionSkill> = exts.flatMap { ext ->
        ext.contributions.skills.map { c ->
            ExtensionSkill(
                node = node,
                pluginId = ext.pluginId,
                contribution = c,
                reach = reach,
                reported = ReportedState.from(state[ext.pluginId]?.get(c.stateTopic)),
                writer = writerFor(node, ext.pluginId),
            )
        }
    }

    internal fun buildTargetActions(node: DeviceId, exts: List<ExtensionDetail>, reach: ExtensionReach) =
        exts.flatMap { ext ->
            ext.contributions.targetActions.map { a ->
                ExtensionTargetActionSkill(
                    node = node,
                    pluginId = ext.pluginId,
                    action = a,
                    gatingSkill = ext.contributions.skills.firstOrNull { it.configKey == a.configKey },
                    reach = reach,
                    writer = writerFor(node, ext.pluginId),
                    designator = { args -> designate(node, args) },
                )
            }
        }.sortedBy { it.action.order ?: Double.MAX_VALUE }

    private fun writerFor(node: DeviceId, pluginId: String) =
        ExtensionConfigWriter { key, value -> repository.writeConfig(node, pluginId, key, value) }

    private suspend fun designate(node: DeviceId, args: Map<String, Any?>): CommandResult {
        val camera = args["camera_id"] as? String ?: return CommandResult.Rejected(-1, "no camera")
        @Suppress("UNCHECKED_CAST")
        val bbox = args["bbox"] as? Map<String, Any?> ?: return CommandResult.Rejected(-1, "no target box")
        val nums = listOf("x", "y", "width", "height").map { (bbox[it] as? Number)?.toDouble() }
        if (nums.any { it == null || !it.isFinite() }) return CommandResult.Rejected(-1, "invalid target box")
        val api = repository.api(node) ?: return CommandResult.Denied(
            ExtensionReach.reasonKey(repository.reach(node)) ?: ExtensionReach.REASON_NO_AGENT,
        )
        val body = buildJsonObject {
            put("camera_id", camera)
            put("bbox", buildJsonObject {
                put("x", nums[0]!!)
                put("y", nums[1]!!)
                put("width", nums[2]!!)
                put("height", nums[3]!!)
            })
            (args["class_label"] as? String)?.let { put("class_label", it) }
            (args["confidence"] as? Number)?.let { put("confidence", it.toDouble()) }
        }
        return when (val r = api.designate(body)) {
            is ApiResult.Ok -> {
                val ok = r.value.asObject()?.get("designated")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
                if (ok) CommandResult.Accepted else CommandResult.Rejected(-1, "the tracker refused the target")
            }
            is ApiResult.Err -> WriteOutcome.Failed(r.failure).toCommandResult()
        }
    }
}
