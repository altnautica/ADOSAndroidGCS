package com.altnautica.gcs.feature.plan.model

/**
 * One mission row as files and the vehicle number it: [seq] 1..N for the mission
 * (0 is the home slot), MAVLink params verbatim, x/y in degrees for location commands.
 */
data class FlatItem(
    val seq: Int,
    val command: Int,
    val frame: Int,
    val param1: Float,
    val param2: Float,
    val param3: Float,
    val param4: Float,
    val x: Double,
    val y: Double,
    val z: Float,
    val autoContinue: Boolean,
)

/** Planner items expanded from flat rows, plus notes about rows that could not be kept. */
data class CollapsedItems(val items: List<PlanItem>, val warnings: List<PlanWarning>)

/** Non-fatal import/download findings shown to the operator. */
sealed interface PlanWarning {
    /** A `DO_JUMP` whose target row is not in the mission was dropped. */
    data class DroppedJump(val seq: Int, val target: Int) : PlanWarning

    /** A complex or unknown item type that carried nothing to expand was skipped. */
    data class SkippedItem(val type: String) : PlanWarning
}

/**
 * Converts between planner items and flat mission rows. A planner item's
 * [PlanItem.speedMs] becomes a `DO_CHANGE_SPEED` row right before it, and
 * `DO_JUMP` targets are renumbered so they keep pointing at the same item.
 */
object MissionFlattener {

    /** Speed type written into `DO_CHANGE_SPEED` param1 (1 = ground speed). */
    private const val SPEED_TYPE_GROUND = 1f

    fun flatten(items: List<PlanItem>): List<FlatItem> {
        val startSeq = HashMap<Long, Int>()
        var seq = 1
        for (item in items) {
            startSeq[item.id] = seq
            seq += if (item.speedMs != null) 2 else 1
        }
        val out = ArrayList<FlatItem>(seq)
        seq = 1
        for (item in items) {
            val speed = item.speedMs
            if (speed != null) {
                out += FlatItem(
                    seq = seq++,
                    command = MavCmd.DO_CHANGE_SPEED,
                    frame = MavFrame.MISSION,
                    param1 = SPEED_TYPE_GROUND,
                    param2 = speed,
                    param3 = -1f,
                    param4 = 0f,
                    x = 0.0,
                    y = 0.0,
                    z = 0f,
                    autoContinue = true,
                )
            }
            val param1 = if (item.command == MavCmd.DO_JUMP) {
                jumpTargetOf(item)?.let { startSeq[it] }?.toFloat() ?: item.param1
            } else {
                item.param1
            }
            out += FlatItem(
                seq = seq++,
                command = item.command,
                frame = item.frame,
                param1 = param1,
                param2 = item.param2,
                param3 = item.param3,
                param4 = item.param4,
                x = item.x,
                y = item.y,
                z = item.z,
                autoContinue = item.autoContinue,
            )
        }
        return out
    }

    /**
     * Folds flat rows back into planner items. A `DO_CHANGE_SPEED` exactly as [flatten]
     * writes it (ground speed, throttle unchanged, absolute) directly followed by a
     * navigation item becomes that item's speed; other rows, including airspeed or
     * throttle changes, map one to one so a round trip never rewrites them.
     */
    fun collapse(rows: List<FlatItem>, nextId: () -> Long): CollapsedItems {
        val items = ArrayList<PlanItem>(rows.size)
        val rowOwner = HashMap<Int, Long>()
        var pendingSpeed: FlatItem? = null
        fun flushPending() {
            val speed = pendingSpeed ?: return
            val id = nextId()
            rowOwner[speed.seq] = id
            items += speed.toPlanItem(id, speedMs = null)
            pendingSpeed = null
        }
        for (row in rows) {
            if (isFlattenedSpeed(row)) {
                flushPending()
                pendingSpeed = row
                continue
            }
            val speed = pendingSpeed?.takeIf { MavCmd.isNav(row.command) }
            if (speed == null) flushPending()
            val id = nextId()
            if (speed != null) {
                rowOwner[speed.seq] = id
                pendingSpeed = null
            }
            rowOwner[row.seq] = id
            items += row.toPlanItem(id, speedMs = speed?.param2)
        }
        flushPending()

        val warnings = ArrayList<PlanWarning>()
        val resolved = items.mapNotNull { item ->
            if (item.command != MavCmd.DO_JUMP) return@mapNotNull item
            val target = item.param1.toInt()
            val owner = rowOwner[target]
            if (owner == null) {
                warnings += PlanWarning.DroppedJump(seq = rows.first { rowOwner[it.seq] == item.id }.seq, target = target)
                null
            } else {
                item.copy(jumpTarget = owner)
            }
        }
        return CollapsedItems(resolved, warnings)
    }

    private fun jumpTargetOf(item: PlanItem): Long? = item.jumpTarget

    /** A `DO_CHANGE_SPEED` row with the shape [flatten] emits, so folding it loses nothing. */
    private fun isFlattenedSpeed(row: FlatItem): Boolean =
        row.command == MavCmd.DO_CHANGE_SPEED &&
            row.param1 == SPEED_TYPE_GROUND &&
            row.param2 > 0f &&
            row.param3 == -1f &&
            row.param4 == 0f

    private fun FlatItem.toPlanItem(id: Long, speedMs: Float?) = PlanItem(
        id = id,
        command = command,
        frame = frame,
        param1 = param1,
        param2 = param2,
        param3 = param3,
        param4 = param4,
        x = x,
        y = y,
        z = z,
        autoContinue = autoContinue,
        speedMs = speedMs,
    )
}
