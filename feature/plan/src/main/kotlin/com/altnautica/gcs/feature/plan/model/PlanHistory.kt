package com.altnautica.gcs.feature.plan.model

/**
 * Undo and redo stacks of whole plans. Immutable: every operation returns a new history.
 * [past] ends with the most recent plan; [future] ends with the next plan to redo.
 */
data class PlanHistory(
    val past: List<MissionPlan> = emptyList(),
    val future: List<MissionPlan> = emptyList(),
    val maxDepth: Int = DEFAULT_DEPTH,
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [previous] as the state before a committed edit; clears redo. */
    fun record(previous: MissionPlan): PlanHistory = copy(past = (past + previous).takeLast(maxDepth), future = emptyList())

    /** Steps back from [current]; null when there is nothing to undo. */
    fun undo(current: MissionPlan): Pair<PlanHistory, MissionPlan>? {
        val target = past.lastOrNull() ?: return null
        return copy(past = past.dropLast(1), future = future + current) to target
    }

    /** Steps forward from [current]; null when there is nothing to redo. */
    fun redo(current: MissionPlan): Pair<PlanHistory, MissionPlan>? {
        val target = future.lastOrNull() ?: return null
        return copy(past = (past + current).takeLast(maxDepth), future = future.dropLast(1)) to target
    }

    fun cleared(): PlanHistory = PlanHistory(maxDepth = maxDepth)

    companion object {
        const val DEFAULT_DEPTH = 50
    }
}
