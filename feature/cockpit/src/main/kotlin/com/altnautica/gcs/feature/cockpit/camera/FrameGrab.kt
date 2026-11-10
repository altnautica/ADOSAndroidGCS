package com.altnautica.gcs.feature.cockpit.camera

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.Window
import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.CockpitViewModel
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.flow.specText
import androidx.compose.ui.geometry.Rect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The main video as the screen shows it: its SurfaceView while it draws on one, and its bounds in the window. */
@Stable
class ShownVideo {
    var surfaceView: SurfaceView? = null
    var bounds: Rect = Rect.Zero
}

/** The flight screen's [ShownVideo]; the main video stage registers itself here. */
val LocalShownVideo = staticCompositionLocalOf<ShownVideo?> { null }

/**
 * Saves the shown frame when the vehicle's camera could not take a photo
 * ([CockpitViewModel.frameGrabs]) and says so through [onToast]: `camera.saved`, or
 * `camera.no_video` while no video plays.
 */
@Composable
internal fun FrameGrabEffect(vm: CockpitViewModel, video: ShownVideo, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val toast by rememberUpdatedState(onToast)
    val saved = specText("camera.saved")
    val noVideo = specText("camera.no_video")
    val failed = stringResource(R.string.cockpit_photo_not_saved)
    LaunchedEffect(vm, video) {
        vm.frameGrabs.collect {
            if (vm.videoState.value !is VideoState.Live) {
                toast(noVideo)
                return@collect
            }
            val window = context.findActivity()?.window
            val frame = window?.let { copyShownFrame(video, it) }
            val ok = frame != null && savePicture(context.contentResolver, frame, System.currentTimeMillis())
            toast(if (ok) saved else failed)
        }
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The shown video frame at full size: copied from the video's SurfaceView, or, when the
 * video draws on a texture (a software decoder), from the window inside the video's
 * bounds. Null when nothing could be copied.
 */
internal suspend fun copyShownFrame(video: ShownVideo, window: Window): Bitmap? {
    val view = video.surfaceView?.takeIf { it.width > 0 && it.height > 0 && it.holder.surface.isValid }
    if (view != null) {
        val target = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        return pixelCopy(target) { listener, handler -> PixelCopy.request(view, target, listener, handler) }
    }
    val b = video.bounds
    if (b.width < 1f || b.height < 1f) return null
    val src = android.graphics.Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt())
    val target = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
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

/** Saves [bitmap] as a PNG in the shared Pictures/ADOS folder; true once written. */
internal suspend fun savePicture(resolver: ContentResolver, bitmap: Bitmap, nowMs: Long): Boolean = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, frameFileName(nowMs))
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, PICTURES_DIR)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return@withContext false
    val written = runCatching { resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true }.getOrDefault(false)
    runCatching {
        if (written) resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        else resolver.delete(uri, null, null)
    }
    written
}

/** File name of a saved frame: `ADOS_<local date>_<time>.png`, millisecond precision so two quick shots never collide. */
internal fun frameFileName(nowMs: Long, locale: Locale = Locale.ROOT): String =
    "ADOS_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", locale).format(Date(nowMs)) + ".png"

/** Where saved frames go, under the shared Pictures folder. */
internal const val PICTURES_DIR = "Pictures/ADOS"
