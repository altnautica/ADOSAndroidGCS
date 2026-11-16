package com.altnautica.gcs.feature.cockpit.autonomy

/**
 * While following, the vision engine detects most frames only inside a window around the subject
 * and sets `crop_window` on those batches. Boxes outside the window are not re-detected then, so
 * they would blink at the whole-frame rate. A crop-only batch therefore keeps the camera's latest
 * whole-frame boxes whose centre lies outside the window, minus the track ids the new batch carries.
 * Held boxes end at the next whole-frame batch, or [HOLD_MS] of frame time after their batch. A
 * batch without the window is shown as it is.
 */
object CropHold {
    const val HOLD_MS = 1_000L

    /** [next] with the held boxes added, given the camera's [previous] batch. */
    fun apply(previous: DetectionBatch?, next: DetectionBatch): DetectionBatch {
        val window = next.cropWindow ?: return next.copy(wholeFrame = null)
        val whole = previous?.let { it.wholeFrame ?: it.takeIf { p -> p.cropWindow == null } }
            ?: return next
        val age = next.tsMs - whole.tsMs
        if (age < 0 || age > HOLD_MS) return next.copy(wholeFrame = whole)
        val ids = next.items.mapNotNullTo(HashSet()) { it.trackId }
        val held = whole.items.filter { d ->
            !inside(d.box, window) && (d.trackId == null || d.trackId !in ids)
        }.map { it.copy(held = true) }
        return next.copy(items = next.items + held, wholeFrame = whole)
    }

    private fun inside(b: Box, w: Box): Boolean =
        b.cx >= w.x && b.cx <= w.right && b.cy >= w.y && b.cy <= w.bottom
}
