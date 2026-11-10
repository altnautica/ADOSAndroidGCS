package com.altnautica.gcs.feature.cockpit.camera

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.CockpitViewModel
import com.altnautica.gcs.feature.cockpit.flow.specText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main video as the screen shows it: its view while it exists, the bounds of its slot in
 * the window, and the digital [zoom] it is shown at (the view is laid out [zoom] times the slot
 * around the slot's centre and clipped to it).
 */
@Stable
class ShownVideo(zoom: Float = 1f) {
    /** A SurfaceView for a decoder, a TextureView for a software-canvas producer; null while none exists. */
    var view: View? = null

    /** [view] as set from a SurfaceView-only callback. */
    var surfaceView: SurfaceView?
        get() = view as? SurfaceView
        set(value) {
            view = value
        }

    var bounds: Rect = Rect.Zero
    var zoom: Float = zoom
}

/** The flight screen's [ShownVideo]; the main video stage registers itself here. */
val LocalShownVideo = staticCompositionLocalOf<ShownVideo?> { null }

internal const val KEY_SAVED = "camera.saved"
internal const val KEY_NO_VIDEO = "camera.no_video"
internal const val KEY_NOT_SAVED = "camera.not_saved"

/**
 * Copies the shown frame at every shutter press ([CockpitViewModel.frameGrabs]) and saves it
 * once the vehicle's camera did not take the photo, saying so through [onToast]:
 * `camera.saved`, `camera.not_saved`, or `camera.no_video` while no video plays. A photo the
 * camera took says nothing here.
 */
@Composable
internal fun FrameGrabEffect(vm: CockpitViewModel, video: ShownVideo, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val toast by rememberUpdatedState(onToast)
    val texts by rememberUpdatedState(listOf(KEY_SAVED, KEY_NO_VIDEO, KEY_NOT_SAVED).associateWith { specText(it) })
    LaunchedEffect(vm, video) {
        vm.frameGrabs.collect { grab ->
            // Each press waits for its own camera answer, so the next press still copies at once.
            launch {
                val window = context.findActivity()?.window
                val key = frameGrabOutcome(
                    live = vm.videoState.value is VideoState.Live,
                    keep = grab.keep,
                    copy = { window?.let { copyShownFrame(video, it) } },
                    save = { savePicture(context.contentResolver, it, System.currentTimeMillis()) },
                )
                key?.let { toast(texts.getValue(it)) }
            }
        }
    }
}

/**
 * One press: copies the frame now while the video is [live], then waits for [keep] (true when
 * the camera did not take the photo; a failed command counts as not taken). Returns the spec
 * key to toast, or null when the camera took it and the copy is dropped.
 */
internal suspend fun <F : Any> frameGrabOutcome(
    live: Boolean,
    keep: Deferred<Boolean>,
    copy: suspend () -> F?,
    save: suspend (F) -> Boolean,
): String? {
    val frame = if (live) copy() else null
    val kept = try {
        keep.await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        true
    }
    return when {
        !kept -> null
        !live -> KEY_NO_VIDEO
        frame != null && save(frame) -> KEY_SAVED
        else -> KEY_NOT_SAVED
    }
}

private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
