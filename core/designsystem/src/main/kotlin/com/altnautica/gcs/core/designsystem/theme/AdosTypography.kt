package com.altnautica.gcs.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.R
import com.altnautica.gcs.core.designsystem.tokens.AdosFontWeight

private val WeightRegular = FontWeight(AdosFontWeight.Regular)
private val WeightMedium = FontWeight(AdosFontWeight.Medium)
private val WeightSemibold = FontWeight(AdosFontWeight.Semibold)

/** Bundled font families. */
object AdosFonts {
    val Inter = FontFamily(
        Font(R.font.inter_regular, WeightRegular),
        Font(R.font.inter_medium, WeightMedium),
        Font(R.font.inter_semibold, WeightSemibold),
    )
    val SpaceGrotesk = FontFamily(
        Font(R.font.space_grotesk_regular, WeightRegular),
        Font(R.font.space_grotesk_medium, WeightMedium),
        Font(R.font.space_grotesk_semibold, WeightSemibold),
    )
    val JetBrainsMono = FontFamily(
        Font(R.font.jetbrains_mono_regular, WeightRegular),
        Font(R.font.jetbrains_mono_medium, WeightMedium),
        Font(R.font.jetbrains_mono_semibold, WeightSemibold),
    )
}

private const val TABULAR_NUMS = "tnum"

/** Monospaced tabular-figure styles for telemetry readouts and counters. */
object AdosTypography {
    val numeric = AdosNumericStyles()
}

class AdosNumericStyles internal constructor() {
    val small = numericStyle(12, 16, WeightRegular)
    val medium = numericStyle(14, 20, WeightMedium)
    val large = numericStyle(20, 26, WeightMedium)
    val display = numericStyle(32, 38, WeightSemibold)

    private fun numericStyle(size: Int, line: Int, weight: FontWeight) = TextStyle(
        fontFamily = AdosFonts.JetBrainsMono,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontFeatureSettings = TABULAR_NUMS,
    )
}

internal fun adosMaterialTypography(): Typography {
    val base = Typography()
    fun TextStyle.display(weight: FontWeight) = copy(fontFamily = AdosFonts.SpaceGrotesk, fontWeight = weight)
    fun TextStyle.text(weight: FontWeight) = copy(fontFamily = AdosFonts.Inter, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.display(WeightSemibold),
        displayMedium = base.displayMedium.display(WeightSemibold),
        displaySmall = base.displaySmall.display(WeightSemibold),
        headlineLarge = base.headlineLarge.display(WeightSemibold),
        headlineMedium = base.headlineMedium.display(WeightMedium),
        headlineSmall = base.headlineSmall.display(WeightMedium),
        titleLarge = base.titleLarge.text(WeightSemibold),
        titleMedium = base.titleMedium.text(WeightMedium),
        titleSmall = base.titleSmall.text(WeightMedium),
        bodyLarge = base.bodyLarge.text(WeightRegular),
        bodyMedium = base.bodyMedium.text(WeightRegular),
        bodySmall = base.bodySmall.text(WeightRegular),
        labelLarge = base.labelLarge.text(WeightMedium),
        labelMedium = base.labelMedium.text(WeightMedium),
        labelSmall = base.labelSmall.text(WeightMedium),
    )
}
