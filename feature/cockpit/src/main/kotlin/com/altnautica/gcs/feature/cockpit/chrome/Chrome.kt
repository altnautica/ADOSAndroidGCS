package com.altnautica.gcs.feature.cockpit.chrome

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.skills.spec.ChromeTokensSpec
import com.altnautica.gcs.core.skills.spec.SpecColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * The flight-screen panel look from the cockpit spec's `chrome` tokens: every panel, chip
 * and button on the flight screen reads these instead of hard-coding colours. One CSS px
 * is one dp.
 */
@Immutable
data class Chrome(
    val panel: Color,
    val panelNoBlur: Color,
    val blur: Dp,
    val border: Color,
    val borderWidth: Dp,
    val radius: Dp,
    val radiusSmall: Dp,
    val text: Color,
    val textDim: Color,
    val ok: Color,
    val warning: Color,
    val critical: Color,
    val stopBg: Color,
    val stopText: Color,
    val topBarText: TextStyle,
    val valueText: TextStyle,
    val labelText: TextStyle,
    val chipText: TextStyle,
    val icon: Dp,
    val gap: Dp,
    val pad: Dp,
    val edge: Dp,
) {
    companion object {
        fun from(spec: ChromeTokensSpec): Chrome {
            fun color(hex: String, fallback: Long) = Color(SpecColor.argb(hex) ?: fallback)
            val text = color(spec.text, 0xFFF5F7FA)
            val dim = color(spec.textDim, 0xA3FFFFFF)
            val type = spec.type
            return Chrome(
                panel = color(spec.panelBg, 0x8C080A0E),
                panelNoBlur = color(spec.panelBgNoBlur, 0xD1080A0E),
                blur = spec.panelBlurPx.dp,
                border = color(spec.border, 0x1FFFFFFF),
                borderWidth = spec.borderPx.dp,
                radius = spec.radiusPx.dp,
                radiusSmall = spec.radiusSmallPx.dp,
                text = text,
                textDim = dim,
                ok = color(spec.severityOk, 0xFF00C853),
                warning = color(spec.severityWarning, 0xFFF59E0B),
                critical = color(spec.severityCritical, 0xFFEF4444),
                stopBg = color(spec.stopBg, 0xFFEF4444),
                stopText = color(spec.stopText, 0xFFFFFFFF),
                topBarText = AdosTypography.numeric.medium.copy(
                    color = text,
                    fontSize = type.topBarPx.sp,
                    lineHeight = type.topBarLinePx.sp,
                    fontWeight = FontWeight.Medium,
                ),
                valueText = AdosTypography.numeric.medium.copy(
                    color = text,
                    fontSize = type.valuePx.sp,
                    lineHeight = (type.valuePx + 4).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                labelText = AdosTypography.numeric.small.copy(
                    color = dim,
                    fontSize = type.labelPx.sp,
                    lineHeight = (type.labelPx + 3).sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                ),
                chipText = AdosTypography.numeric.medium.copy(
                    color = text,
                    fontSize = type.chipPx.sp,
                    lineHeight = (type.chipPx + 3).sp,
                    fontWeight = FontWeight(type.chipWeight),
                ),
                icon = spec.iconPx.dp,
                gap = spec.gapPx.dp,
                pad = spec.padPx.dp,
                edge = spec.edgeInsetPx.dp,
            )
        }

        val Default: Chrome = from(ChromeTokensSpec())
    }
}

val LocalChrome = staticCompositionLocalOf { Chrome.Default }

/** True where the panels blur what is behind them (RenderEffect, API 31 and later). */
val BackdropBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * A low-resolution copy of the main video, sampled a few times a second, that the panels
 * draw blurred behind themselves. Compose cannot read a SurfaceView's pixels in its draw
 * pass, so [BackdropSampler] copies them with PixelCopy instead.
 */
@Stable
class ChromeBackdrop {
    /** The latest sampled frame; null until the first copy (panels then use the opaque tint). */
    var frame: ImageBitmap? by mutableStateOf(null)
        internal set

    /** Where the video draws, in root coordinates. */
    var videoBounds: Rect by mutableStateOf(Rect.Zero)

    /** The main video's SurfaceView while it exists. */
    var surfaceView: SurfaceView? = null
}

val LocalChromeBackdrop = staticCompositionLocalOf<ChromeBackdrop?> { null }

/** Copies the main video into [backdrop] every [SAMPLE_MS] at 1/[DOWNSCALE] size; nothing below API 31. */
@Composable
fun BackdropSampler(backdrop: ChromeBackdrop) {
    if (!BackdropBlurSupported) return
    LaunchedEffect(backdrop) {
        val handler = Handler(Looper.getMainLooper())
        // Two bitmaps: one on screen, one being written.
        var spare: Bitmap? = null
        var shown: Bitmap? = null
        while (isActive) {
            delay(SAMPLE_MS)
            val view = backdrop.surfaceView ?: continue
            if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) continue
            val w = (view.width / DOWNSCALE).coerceAtLeast(1)
            val h = (view.height / DOWNSCALE).coerceAtLeast(1)
            val target = spare?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val ok = suspendCancellableCoroutine { cont ->
                runCatching {
                    PixelCopy.request(view, target, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, handler)
                }.onFailure { if (cont.isActive) cont.resume(false) }
            }
            if (ok) {
                spare = shown
                shown = target
                backdrop.frame = target.asImageBitmap()
            } else {
                spare = target
            }
        }
    }
}

private const val SAMPLE_MS = 250L
private const val DOWNSCALE = 12

/**
 * A flight-screen panel: the blurred video behind it (API 31 and later, once a frame is
 * sampled), the translucent tint, a hairline border, then [content]. Below API 31 the tint
 * is the stronger no-blur colour.
 */
@Composable
fun ChromePanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalChrome.current.radius),
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalChrome.current
    val backdrop = LocalChromeBackdrop.current
    Box(modifier.clip(shape).border(c.borderWidth, c.border, shape), propagateMinConstraints = false) {
        if (backdrop != null && BackdropBlurSupported) BackdropFill(backdrop, c, Modifier.matchParentSize())
        Spacer(
            Modifier.matchParentSize().drawBehind {
                val blurred = backdrop?.frame != null && BackdropBlurSupported
                drawRect(tint ?: if (blurred) c.panel else c.panelNoBlur)
            },
        )
        content()
    }
}

@Composable
private fun BackdropFill(backdrop: ChromeBackdrop, c: Chrome, modifier: Modifier) {
    val origin = remember { FloatArray(2) }
    Spacer(
        modifier
            .onGloballyPositioned { val p: Offset = it.positionInRoot(); origin[0] = p.x; origin[1] = p.y }
            .graphicsLayer {
                val r = c.blur.toPx()
                renderEffect = BlurEffect(r, r, TileMode.Clamp)
                clip = true
            }
            .drawBehind {
                val frame = backdrop.frame ?: return@drawBehind
                val vb = backdrop.videoBounds
                if (vb.width <= 0f || vb.height <= 0f) return@drawBehind
                drawImage(
                    frame,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(frame.width, frame.height),
                    dstOffset = IntOffset((vb.left - origin[0]).roundToInt(), (vb.top - origin[1]).roundToInt()),
                    dstSize = IntSize(vb.width.roundToInt(), vb.height.roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            },
    )
}
