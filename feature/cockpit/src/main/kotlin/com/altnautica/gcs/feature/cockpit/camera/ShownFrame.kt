package com.altnautica.gcs.feature.cockpit.camera

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.Window
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** A pixel rectangle; [right] and [bottom] are exclusive. */
internal data class PixelBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toRect() = android.graphics.Rect(left, top, right, bottom)
}

/** What a frame grab copies: [source] of the video's buffer, into an [outWidth]×[outHeight] bitmap. */
internal data class ShownCopy(val source: PixelBox, val outWidth: Int, val outHeight: Int)

/**
 * The part of a [frameWidth]×[frameHeight] video buffer the screen shows at digital [zoom]: the
 * view is laid out [zoom] times its slot around the slot's centre, so the slot shows the
 * buffer's centred 1/[zoom]. It is copied at the size of the visible slot
 * ([slotWidth]×[slotHeight]), never at the size of the zoomed view. Null when a size is empty.
 */
internal fun shownCopy(frameWidth: Int, frameHeight: Int, slotWidth: Int, slotHeight: Int, zoom: Float): ShownCopy? {
    if (frameWidth < 1 || frameHeight < 1 || slotWidth < 1 || slotHeight < 1) return null
    val z = zoom.coerceAtLeast(1f)
    val w = (frameWidth / z).roundToInt().coerceIn(1, frameWidth)
    val h = (frameHeight / z).roundToInt().coerceIn(1, frameHeight)
    val left = (frameWidth - w) / 2
    val top = (frameHeight - h) / 2
    return ShownCopy(PixelBox(left, top, left + w, top + h), slotWidth, slotHeight)
}

private fun argbBitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

/**
 * The shown video frame at the size of its slot: the shown part of the video's SurfaceView or
 * TextureView, or, without a usable view, the window inside the slot's bounds. Null when
 * nothing could be copied, including when the bitmap cannot be allocated ([allocate] may run
 * out of memory).
 */
internal suspend fun copyShownFrame(video: ShownVideo, window: Window, allocate: (Int, Int) -> Bitmap = ::argbBitmap): Bitmap? = try {
    val b = video.bounds
    val view = video.view?.takeIf { it.width > 0 && it.height > 0 }
    val zoom = video.zoom.coerceAtLeast(1f)
    // The slot; before it is placed, the view's own size at the zoom it is shown at.
    val slotW = b.width.roundToInt().takeIf { it > 0 } ?: view?.let { (it.width / zoom).roundToInt() } ?: 0
    val slotH = b.height.roundToInt().takeIf { it > 0 } ?: view?.let { (it.height / zoom).roundToInt() } ?: 0
    when {
        view is SurfaceView && view.holder.surface.isValid -> copySurface(view, slotW, slotH, zoom, allocate)
        view is TextureView && view.isAvailable -> copyTexture(view, slotW, slotH, zoom)
        else -> copyWindow(window, PixelBox(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()), allocate)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    // An OutOfMemoryError from the allocation included: the press reads "not saved", the app lives.
    null
}

private suspend fun copySurface(view: SurfaceView, slotW: Int, slotH: Int, zoom: Float, allocate: (Int, Int) -> Bitmap): Bitmap? {
    val frame = view.holder.surfaceFrame
    val plan = shownCopy(frame.width(), frame.height(), slotW, slotH, zoom) ?: return null
    val target = allocate(plan.outWidth, plan.outHeight)
    val src = plan.source.toRect()
    return pixelCopy(target) { listener, handler -> PixelCopy.request(view, src, target, listener, handler) }
}

/**
 * A TextureView draws the whole zoomed view into a bitmap of the requested size, so the
 * whole view is taken at slot size, its centred 1/[zoom] cut out and scaled back to the slot.
 */
private fun copyTexture(view: TextureView, slotW: Int, slotH: Int, zoom: Float): Bitmap? {
    if (slotW < 1 || slotH < 1) return null
    val whole = view.getBitmap(slotW, slotH) ?: return null
    val plan = shownCopy(whole.width, whole.height, slotW, slotH, zoom) ?: return null
    val s = plan.source
    if (s.width == whole.width && s.height == whole.height) return whole
    val shown = Bitmap.createBitmap(whole, s.left, s.top, s.width, s.height)
    return Bitmap.createScaledBitmap(shown, plan.outWidth, plan.outHeight, true)
}

private suspend fun copyWindow(window: Window, slot: PixelBox, allocate: (Int, Int) -> Bitmap): Bitmap? {
    if (slot.width < 1 || slot.height < 1) return null
    val target = allocate(slot.width, slot.height)
    val src = slot.toRect()
    return pixelCopy(target) { listener, handler -> PixelCopy.request(window, src, target, listener, handler) }
}

private suspend fun pixelCopy(
    target: Bitmap,
    request: (PixelCopy.OnPixelCopyFinishedListener, Handler) -> Unit,
): Bitmap? = suspendCancellableCoroutine { cont ->
    runCatching {
        request({ result -> if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) target else null) }, Handler(Looper.getMainLooper()))
    }.onFailure { if (cont.isActive) cont.resume(null) }
}
