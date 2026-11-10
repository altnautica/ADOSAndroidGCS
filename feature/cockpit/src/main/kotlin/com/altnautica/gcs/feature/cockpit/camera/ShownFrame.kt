package com.altnautica.gcs.feature.cockpit.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.Window
import com.altnautica.gcs.core.video.ui.videoBufferSize
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
        view is TextureView && view.isAvailable -> copyTexture(view, slotW, slotH, zoom, allocate)
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
 * A TextureView draws the whole zoomed view into a bitmap of the requested size. It is taken at
 * the buffer's own resolution ([bufferW]×[bufferH]), so cutting out the centred 1/[zoom] keeps
 * every buffer pixel the slot shows; the cut is then drawn at slot size.
 */
internal data class TextureCopy(val grabWidth: Int, val grabHeight: Int, val copy: ShownCopy)

internal fun textureCopy(bufferW: Int, bufferH: Int, slotW: Int, slotH: Int, zoom: Float): TextureCopy? {
    val copy = shownCopy(bufferW, bufferH, slotW, slotH, zoom) ?: return null
    return TextureCopy(bufferW, bufferH, copy)
}

private fun copyTexture(view: TextureView, slotW: Int, slotH: Int, zoom: Float, allocate: (Int, Int) -> Bitmap): Bitmap? {
    val buffer = videoBufferSize(view)
    val plan = textureCopy(buffer?.width ?: view.width, buffer?.height ?: view.height, slotW, slotH, zoom) ?: return null
    val whole = allocate(plan.grabWidth, plan.grabHeight)
    try {
        view.getBitmap(whole)
        val s = plan.copy.source
        val shown = allocate(plan.copy.outWidth, plan.copy.outHeight)
        Canvas(shown).drawBitmap(whole, s.toRect(), android.graphics.Rect(0, 0, shown.width, shown.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return shown
    } finally {
        whole.recycle()
    }
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
