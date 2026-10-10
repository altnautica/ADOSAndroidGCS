package com.altnautica.gcs.core.map

/** A camera move: centre on [target], and set [zoom] when it is not null. */
internal data class CameraMove(val target: GeoPoint, val zoom: Double?)

/**
 * Camera policy of a map that follows a point (the minimap follows the vehicle, or
 * home before the vehicle reports a position). The latest request is kept until the
 * map is ready, so a target that arrives before the map, and then never moves (a
 * vehicle parked on the ground), is still applied. The first move sets the requested
 * zoom; later moves only pan, leaving the zoom to the operator.
 */
internal class CameraFollow {
    private var target: GeoPoint? = null
    private var zoom: Double = 0.0
    private var zoomed = false
    private var applied: GeoPoint? = null

    fun request(target: GeoPoint, zoom: Double) {
        this.target = target
        this.zoom = zoom
    }

    /** The move a ready map should make now, or null when it already shows the target. */
    fun next(): CameraMove? {
        val t = target ?: return null
        if (!zoomed) {
            zoomed = true
            applied = t
            return CameraMove(t, zoom)
        }
        if (t == applied) return null
        applied = t
        return CameraMove(t, null)
    }

    /** A new map instance starts from its default camera: the next move zooms again. */
    fun reset() {
        zoomed = false
        applied = null
    }
}
