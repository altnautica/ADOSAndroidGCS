package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.map.GeoMath
import com.altnautica.gcs.core.map.GeoPoint

/** Pure edits on a [MissionPlan]; every function returns a new plan. */
object PlanEditor {

    /** Distance used to place an item inserted after the last located item. */
    private const val APPEND_OFFSET_M = 30.0

    fun newItem(
        id: Long,
        command: PlanCommand,
        position: GeoPoint?,
        altM: Float,
        ref: AltitudeRef,
    ): PlanItem {
        val located = position != null && MavCmd.carriesLocation(command.mavCmd)
        val base = PlanItem(
            id = id,
            command = command.mavCmd,
            frame = if (located || command == PlanCommand.Takeoff || command == PlanCommand.ReturnToLaunch) ref.mavFrame else MavFrame.MISSION,
            x = if (located) position!!.latDeg else 0.0,
            y = if (located) position!!.lonDeg else 0.0,
            z = if (command == PlanCommand.ReturnToLaunch) 0f else altM,
        )
        return when (command) {
            PlanCommand.LoiterTime -> base.copy(param1 = 30f)
            PlanCommand.LoiterTurns -> base.copy(param1 = 1f)
            // Ground speed, new speed, no throttle change.
            PlanCommand.ChangeSpeed -> base.copy(param1 = 1f, param2 = 5f, param3 = -1f, z = 0f)
            // Shutter: param5 (x) = 1 takes one picture.
            PlanCommand.DigicamControl -> base.copy(x = 1.0, z = 0f)
            else -> base
        }
    }

    fun append(plan: MissionPlan, item: PlanItem): MissionPlan = plan.copy(items = plan.items + item)

    /** Inserts [item] at [index] (clamped to the list). */
    fun insertAt(plan: MissionPlan, index: Int, item: PlanItem): MissionPlan {
        val items = plan.items.toMutableList()
        items.add(index.coerceIn(0, items.size), item)
        return plan.copy(items = items)
    }

    /** Position for an item inserted after [index]: the midpoint to the next located item, else a short step on. */
    fun insertionPoint(plan: MissionPlan, index: Int): GeoPoint? {
        val here = plan.items.getOrNull(index)?.position ?: plan.items.take(index + 1).lastNotNullOf { it.position }
        val next = plan.items.drop(index + 1).firstNotNullOfOrNull { it.position }
        return when {
            here != null && next != null -> GeoPoint((here.latDeg + next.latDeg) / 2, (here.lonDeg + next.lonDeg) / 2)
            here != null -> GeoMath.destination(here, 45.0, APPEND_OFFSET_M)
            next != null -> GeoMath.destination(next, 225.0, APPEND_OFFSET_M)
            else -> null
        }
    }

    fun move(plan: MissionPlan, id: Long, position: GeoPoint): MissionPlan = update(plan, id) {
        if (MavCmd.carriesLocation(it.command)) it.copy(x = position.latDeg, y = position.lonDeg) else it
    }

    /** Removes the item and every `DO_JUMP` that pointed at it. */
    fun delete(plan: MissionPlan, id: Long): MissionPlan =
        plan.copy(items = plan.items.filterNot { it.id == id || (it.command == MavCmd.DO_JUMP && it.jumpTarget == id) })

    fun update(plan: MissionPlan, id: Long, transform: (PlanItem) -> PlanItem): MissionPlan =
        plan.copy(items = plan.items.map { if (it.id == id) transform(it) else it })

    /**
     * Changes the altitude reference of an item. Between REL and MSL the altitude is
     * converted with [homeAltMslM] so the item stays at the same height; without a
     * home altitude (or to/from terrain) the number is kept.
     */
    fun setAltitudeRef(plan: MissionPlan, id: Long, ref: AltitudeRef, homeAltMslM: Float?): MissionPlan = update(plan, id) { item ->
        val from = item.altitudeRef ?: return@update item.copy(frame = ref.mavFrame)
        val z = when {
            homeAltMslM == null || from == ref -> item.z
            from == AltitudeRef.Relative && ref == AltitudeRef.Msl -> item.z + homeAltMslM
            from == AltitudeRef.Msl && ref == AltitudeRef.Relative -> item.z - homeAltMslM
            else -> item.z
        }
        item.copy(frame = ref.mavFrame, z = z)
    }

    /** Swaps the command of an item, keeping its position and altitude. */
    fun setCommand(plan: MissionPlan, id: Long, command: PlanCommand, defaultRef: AltitudeRef): MissionPlan = update(plan, id) { item ->
        val fresh = newItem(item.id, command, item.position, item.z, item.altitudeRef ?: defaultRef)
        fresh.copy(speedMs = item.speedMs)
    }

    // ── Fence and rally ──

    fun addFence(plan: MissionPlan, zone: FenceZone): MissionPlan = plan.copy(fence = plan.fence + zone)

    fun deleteFence(plan: MissionPlan, id: Long): MissionPlan = plan.copy(fence = plan.fence.filterNot { it.id == id })

    fun moveFenceVertex(plan: MissionPlan, zoneId: Long, vertex: Int, position: GeoPoint): MissionPlan =
        plan.copy(
            fence = plan.fence.map { zone ->
                when {
                    zone.id != zoneId -> zone
                    zone is FenceZone.Polygon && vertex in zone.points.indices ->
                        zone.copy(points = zone.points.toMutableList().also { it[vertex] = position })
                    zone is FenceZone.Circle -> zone.copy(center = position)
                    else -> zone
                }
            },
        )

    fun setFenceRadius(plan: MissionPlan, zoneId: Long, radiusM: Double): MissionPlan =
        plan.copy(fence = plan.fence.map { if (it.id == zoneId && it is FenceZone.Circle) it.copy(radiusM = radiusM) else it })

    fun toggleFenceInclusion(plan: MissionPlan, zoneId: Long): MissionPlan =
        plan.copy(
            fence = plan.fence.map { zone ->
                if (zone.id != zoneId) {
                    zone
                } else {
                    when (zone) {
                        is FenceZone.Polygon -> zone.copy(inclusion = !zone.inclusion)
                        is FenceZone.Circle -> zone.copy(inclusion = !zone.inclusion)
                    }
                }
            },
        )

    fun addRally(plan: MissionPlan, point: RallyPoint): MissionPlan = plan.copy(rally = plan.rally + point)

    fun moveRally(plan: MissionPlan, id: Long, position: GeoPoint): MissionPlan =
        plan.copy(rally = plan.rally.map { if (it.id == id) it.copy(position = position) else it })

    fun setRallyAltitude(plan: MissionPlan, id: Long, altM: Float): MissionPlan =
        plan.copy(rally = plan.rally.map { if (it.id == id) it.copy(altM = altM) else it })

    fun deleteRally(plan: MissionPlan, id: Long): MissionPlan = plan.copy(rally = plan.rally.filterNot { it.id == id })

    private inline fun <T, R : Any> List<T>.lastNotNullOf(transform: (T) -> R?): R? {
        for (i in indices.reversed()) transform(this[i])?.let { return it }
        return null
    }
}

/** Cumulative ground distance and altitude of each located item, for the altitude profile. */
data class ProfilePoint(val itemId: Long, val distanceM: Double, val altM: Float, val ref: AltitudeRef?)

fun MissionPlan.altitudeProfile(): List<ProfilePoint> {
    var distance = 0.0
    var previous: GeoPoint? = home?.position
    val out = ArrayList<ProfilePoint>()
    for (item in items) {
        val p = item.position ?: continue
        previous?.let { distance += GeoMath.distanceM(it, p) }
        previous = p
        out += ProfilePoint(item.id, distance, item.z, item.altitudeRef)
    }
    return out
}
