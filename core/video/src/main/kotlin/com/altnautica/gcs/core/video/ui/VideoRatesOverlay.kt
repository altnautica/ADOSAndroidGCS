package com.altnautica.gcs.core.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.video.DebugProperties
import com.altnautica.gcs.core.video.R
import com.altnautica.gcs.core.video.VideoRates
import com.altnautica.gcs.core.video.VideoSessionHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Whether a debuggable build was asked (`setprop debug.ados.video.overlay 1`) to show [VideoRatesOverlay]. */
@Composable
internal fun rememberRatesOverlayEnabled(): Boolean {
    val context = LocalContext.current.applicationContext
    val enabled by produceState(false, context) {
        value = withContext(Dispatchers.IO) { DebugProperties.read(context, DebugProperties.OVERLAY) == "1" }
    }
    return enabled
}

/**
 * A diagnostics line over the main video: the decoder, and frames per second received,
 * decoded, delivered to the app and shown, so a loss between any two hops is visible on
 * screen. Draws nothing until the session has measured a second.
 */
@Composable
internal fun VideoRatesOverlay(handle: VideoSessionHandle, modifier: Modifier = Modifier) {
    val rates by handle.rates.collectAsStateWithLifecycle()
    val r = rates ?: return
    BasicText(
        text = stringResource(R.string.video_debug_rates, *r.overlayArgs().toTypedArray()),
        style = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        modifier = modifier.background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun Double?.fps(): String = this?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"

/** The overlay's format arguments: decoder, then received, decoded, delivered and shown fps. */
internal fun VideoRates.overlayArgs(): List<String> =
    listOf(decoder ?: "-", receivedFps.fps(), decodedFps.fps(), deliveredFps.fps(), shownFps.fps())
