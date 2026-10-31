package com.altnautica.gcs.core.designsystem.component.console

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.R
import com.altnautica.gcs.core.designsystem.component.ThemePreviews
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** A controller button by its role, independent of the pad's printed legend. */
enum class ConsoleButton { Confirm, Back, Details, More, PrevTab, NextTab, PrevPage, NextPage, Menu, Capture, Home, StickL, StickR }

private enum class FaceShape { Cross, Circle, Triangle, Square }

/** How one button draws in one glyph set: a face shape or a printed legend, plus its spoken name. */
private class GlyphSpec(val shape: FaceShape?, @StringRes val legend: Int, @StringRes val spoken: Int)

private fun ConsoleButton.spec(glyphs: GlyphSet): GlyphSpec = when (glyphs) {
    GlyphSet.PlayStation -> when (this) {
        ConsoleButton.Confirm -> GlyphSpec(FaceShape.Cross, R.string.console_glyph_a, R.string.console_button_cross)
        ConsoleButton.Back -> GlyphSpec(FaceShape.Circle, R.string.console_glyph_b, R.string.console_button_circle)
        ConsoleButton.Details -> GlyphSpec(FaceShape.Triangle, R.string.console_glyph_y, R.string.console_button_triangle)
        ConsoleButton.More -> GlyphSpec(FaceShape.Square, R.string.console_glyph_x, R.string.console_button_square)
        ConsoleButton.PrevTab -> GlyphSpec(null, R.string.console_glyph_l1, R.string.console_button_l1)
        ConsoleButton.NextTab -> GlyphSpec(null, R.string.console_glyph_r1, R.string.console_button_r1)
        ConsoleButton.PrevPage -> GlyphSpec(null, R.string.console_glyph_l2, R.string.console_button_l2)
        ConsoleButton.NextPage -> GlyphSpec(null, R.string.console_glyph_r2, R.string.console_button_r2)
        ConsoleButton.Menu -> GlyphSpec(null, R.string.console_glyph_options, R.string.console_button_options)
        ConsoleButton.Capture -> GlyphSpec(null, R.string.console_glyph_share, R.string.console_button_share)
        ConsoleButton.Home -> GlyphSpec(null, R.string.console_glyph_ps, R.string.console_button_ps)
        ConsoleButton.StickL -> GlyphSpec(null, R.string.console_glyph_l3, R.string.console_button_l3)
        ConsoleButton.StickR -> GlyphSpec(null, R.string.console_glyph_r3, R.string.console_button_r3)
    }
    GlyphSet.Generic -> when (this) {
        ConsoleButton.Confirm -> GlyphSpec(null, R.string.console_glyph_a, R.string.console_button_a)
        ConsoleButton.Back -> GlyphSpec(null, R.string.console_glyph_b, R.string.console_button_b)
        ConsoleButton.Details -> GlyphSpec(null, R.string.console_glyph_y, R.string.console_button_y)
        ConsoleButton.More -> GlyphSpec(null, R.string.console_glyph_x, R.string.console_button_x)
        ConsoleButton.PrevTab -> GlyphSpec(null, R.string.console_glyph_lb, R.string.console_button_lb)
        ConsoleButton.NextTab -> GlyphSpec(null, R.string.console_glyph_rb, R.string.console_button_rb)
        ConsoleButton.PrevPage -> GlyphSpec(null, R.string.console_glyph_lt, R.string.console_button_lt)
        ConsoleButton.NextPage -> GlyphSpec(null, R.string.console_glyph_rt, R.string.console_button_rt)
        ConsoleButton.Menu -> GlyphSpec(null, R.string.console_glyph_menu, R.string.console_button_menu)
        ConsoleButton.Capture -> GlyphSpec(null, R.string.console_glyph_view, R.string.console_button_view)
        ConsoleButton.Home -> GlyphSpec(null, R.string.console_glyph_home, R.string.console_button_home)
        ConsoleButton.StickL -> GlyphSpec(null, R.string.console_glyph_ls, R.string.console_button_ls)
        ConsoleButton.StickR -> GlyphSpec(null, R.string.console_glyph_rs, R.string.console_button_rs)
    }
}

/**
 * The glyph of [button] on the active pad: PlayStation face buttons are drawn as
 * shapes, everything else is its printed legend in a pill. Single-letter legends sit
 * in a circle like the face shapes.
 */
@Composable
fun ControllerGlyph(
    button: ConsoleButton,
    modifier: Modifier = Modifier,
    glyphs: GlyphSet = LocalControllerUi.current.glyphs,
    size: Dp = 20.dp,
) {
    val spec = button.spec(glyphs)
    val colors = AdosTheme.colors
    val spoken = stringResource(spec.spoken)
    val described = modifier.semantics { contentDescription = spoken }
    val shape = spec.shape
    if (shape != null) {
        Canvas(described.size(size).border(1.dp, colors.borderStrong, CircleShape)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val r = this.size.minDimension * 0.24f
            val c = center
            val ink = colors.fgPrimary
            when (shape) {
                FaceShape.Cross -> {
                    drawLine(ink, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), stroke.width, StrokeCap.Round)
                    drawLine(ink, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y - r), stroke.width, StrokeCap.Round)
                }
                FaceShape.Circle -> drawCircle(ink, radius = r, center = c, style = stroke)
                FaceShape.Triangle -> {
                    val path = Path().apply {
                        moveTo(c.x, c.y - r * 1.1f)
                        lineTo(c.x + r * 1.1f, c.y + r * 0.8f)
                        lineTo(c.x - r * 1.1f, c.y + r * 0.8f)
                        close()
                    }
                    drawPath(path, ink, style = stroke)
                }
                FaceShape.Square -> drawRect(
                    ink,
                    topLeft = Offset(c.x - r * 0.9f, c.y - r * 0.9f),
                    size = Size(r * 1.8f, r * 1.8f),
                    style = stroke,
                )
            }
        }
        return
    }
    val legend = stringResource(spec.legend)
    val single = legend.length == 1
    Box(
        described
            .height(size)
            .defaultMinSize(minWidth = size)
            .border(1.dp, colors.borderStrong, if (single) CircleShape else RoundedCornerShape(AdosRadius.Sm))
            .padding(horizontal = if (single) 0.dp else AdosSpacing.S1),
        contentAlignment = Alignment.Center,
    ) {
        Text(legend, style = MaterialTheme.typography.labelSmall, color = colors.fgPrimary, maxLines = 1)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ControllerGlyphPreview() = ThemePreviews {
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        GlyphSet.entries.forEach { set ->
            CompositionLocalProvider(LocalControllerUi provides ControllerUi(active = true, glyphs = set)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                    ConsoleButton.entries.forEach { ControllerGlyph(it) }
                }
            }
        }
    }
}
