package com.altnautica.gcs.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.theme.AdosFonts
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.rememberReducedMotion

/**
 * The Home stage behind the hero when there is no live still: a night sky that brightens
 * to a horizon glow, and a perspective ground grid that drifts toward the viewer. The
 * drift is one slow loop drawn in the draw pass (no recomposition); reduced motion holds
 * it still.
 */
@Composable
internal fun StageBackdrop(modifier: Modifier = Modifier) {
    val accent = AdosTheme.colors.accentPrimary
    val reduced = rememberReducedMotion()
    val drift: State<Float> = if (reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "stage").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(STAGE_LOOP_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "stage-drift",
        )
    }
    // Brushes, colours and geometry are built once per size; only the rungs follow the drift.
    Spacer(
        modifier.fillMaxSize().graphicsLayer().testTag("home-stage").drawWithCache {
            val w = size.width
            val h = size.height
            val horizon = h * 0.58f
            // Sky: deep navy to a cool horizon.
            val sky = Brush.verticalGradient(0f to Color(0xFF05070D), 0.55f to Color(0xFF0B1424), 1f to Color(0xFF05070D))
            // Horizon glow in the accent, off-centre right where the hero text is not.
            val glow = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.08f), Color.Transparent),
                center = Offset(w * 0.72f, horizon),
                radius = w * 0.55f,
            )
            // Readability: the left side, where the hero text sits, darkens.
            val shade = Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.72f), 0.55f to Color.Black.copy(alpha = 0.25f), 1f to Color.Transparent)
            val horizonLine = accent.copy(alpha = 0.35f)
            val vanish = Offset(w * 0.72f, horizon)
            val line = Color.White.copy(alpha = 0.06f)
            val stroke = 1.dp.toPx()
            onDrawBehind {
                drawRect(sky)
                drawRect(glow)
                drawLine(horizonLine, Offset(0f, horizon), Offset(w, horizon), stroke)
                // Ground grid: lines converging on a vanishing point, rungs drifting closer.
                for (i in -14..14) {
                    val x = vanish.x + i * w * 0.09f
                    drawLine(line, vanish, Offset(vanish.x + (x - vanish.x) * 4f, h), stroke)
                }
                val t = drift.value
                for (k in 0 until RUNGS) {
                    // Rungs spaced on a perspective curve; t slides each toward the next.
                    val p = ((k + t) / RUNGS)
                    val y = horizon + (h - horizon) * p * p
                    val a = (0.02f + 0.08f * p).coerceAtMost(0.1f)
                    drawLine(Color.White.copy(alpha = a), Offset(0f, y), Offset(w, y), stroke)
                }
                drawRect(shade)
            }
        },
    )
}

private const val STAGE_LOOP_MS = 9_000
private const val RUNGS = 9

/**
 * The Fly card: the largest thing on Home. Accent-filled with the take-off mark, the
 * vehicle's readiness under the title and a forward arrow; a slow halo breathes around
 * it while flying is possible (held still under reduced motion).
 */
@Composable
internal fun FlyCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val reduced = rememberReducedMotion()
    val halo: State<Float> = if (reduced || !enabled) {
        remember { mutableFloatStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "fly").animateFloat(
            initialValue = 0.25f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(tween(FLY_BREATH_MS), RepeatMode.Reverse),
            label = "fly-halo",
        )
    }
    val fill = if (enabled) {
        Brush.linearGradient(listOf(colors.accentPrimary, colors.accentPrimary.copy(alpha = 0.72f)))
    } else {
        Brush.linearGradient(listOf(colors.bgRaised, colors.bgRaised))
    }
    Box(
        modifier
            .width(FLY_WIDTH)
            .height(FLY_HEIGHT)
            .drawBehind {
                if (!enabled) return@drawBehind
                val r = 20.dp.toPx()
                drawRoundRect(
                    colors.accentPrimary.copy(alpha = 0.22f * halo.value),
                    topLeft = Offset(-6.dp.toPx(), -6.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + 6.dp.toPx()),
                )
            }
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .consoleFocus(shape, scaleOnFocus = true)
            .clip(shape)
            .background(fill)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.22f else 0.08f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title. $subtitle" }
            .testTag("home-fly")
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                Icons.Outlined.FlightTakeoff,
                contentDescription = null,
                tint = if (enabled) colors.accentForeground else colors.fgTertiary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = AdosFonts.SpaceGrotesk, fontWeight = FontWeight.SemiBold),
                color = if (enabled) colors.accentForeground else colors.fgSecondary,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) colors.accentForeground.copy(alpha = 0.85f) else colors.fgTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(FLY_WIDTH - 90.dp),
            )
        }
        if (enabled) {
            Row(
                Modifier.align(Alignment.CenterEnd).size(40.dp).background(colors.accentForeground.copy(alpha = 0.18f), CircleShape),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = colors.accentForeground)
            }
        }
    }
}

private val FLY_WIDTH = 248.dp
private val FLY_HEIGHT = 128.dp
private const val FLY_BREATH_MS = 1_800

/**
 * Swaps the hero for a newly selected vehicle: the old one fades out fast, the new one
 * fades in with a short rise. Reduced motion swaps without animating.
 */
@Composable
internal fun <T> HeroSwap(target: T, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val reduced = rememberReducedMotion()
    AnimatedContent(
        targetState = target,
        contentKey = key,
        transitionSpec = {
            if (reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(tween(HERO_IN_MS, delayMillis = HERO_OUT_MS / 2)) + slideInVertically(tween(HERO_IN_MS)) { it / 14 }) togetherWith
                    fadeOut(tween(HERO_OUT_MS))
            }
        },
        label = "hero",
    ) { value -> content(value) }
}

private const val HERO_IN_MS = 260
private const val HERO_OUT_MS = 120
