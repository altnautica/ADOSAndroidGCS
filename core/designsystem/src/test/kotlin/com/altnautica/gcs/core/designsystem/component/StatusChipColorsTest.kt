package com.altnautica.gcs.core.designsystem.component

import androidx.compose.ui.graphics.Color
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusChipColorsTest {
    @Test
    fun containerIsTransparentAndBorderIsTheHueAtFortyPercent() {
        AdosThemeId.entries.forEach { id ->
            val roles = AdosColors.forTheme(id)
            StatusLevel.entries.forEach { level ->
                val c = statusChipColors(level, roles)
                assertEquals("container $id $level", Color.Transparent, c.container)
                assertEquals("border alpha $id $level", 0.4f, c.border.alpha, 0.01f)
                assertEquals("border hue $id $level", c.icon.copy(alpha = 0.4f), c.border)
            }
        }
    }

    @Test
    fun chipTextReadsAtAaOnEveryThemeBackground() {
        AdosThemeId.entries.forEach { id ->
            val roles = AdosColors.forTheme(id)
            StatusLevel.entries.forEach { level ->
                val text = statusChipColors(level, roles).text
                listOf(roles.bgCanvas, roles.bgSurface, roles.bgRaised).forEach { bg ->
                    val ratio = contrastRatio(text, bg)
                    assertTrue("$id $level text $ratio < 4.5", ratio >= STATUS_CHIP_TEXT_MIN_CONTRAST)
                }
            }
        }
    }

    @Test
    fun aHueBelowAaIsLiftedTowardPrimaryText() {
        val dim = Color(0xFF2979FF)
        val lifted = readableOn(dim, listOf(Color(0xFF1A1A1A)), Color(0xFFEDEDED))
        assertTrue(contrastRatio(dim, Color(0xFF1A1A1A)) < 4.5f)
        assertTrue(contrastRatio(lifted, Color(0xFF1A1A1A)) >= 4.5f)
    }
}
