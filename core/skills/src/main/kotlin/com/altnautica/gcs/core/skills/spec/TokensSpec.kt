package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Colours are `#RRGGBB` or `#RRGGBBAA`; [SpecColor.argb] converts them. */
@Serializable
data class TokensSpec(
    val v: Int,
    val ar: Map<String, String>,
    @SerialName("ar_geometry") val arGeometry: Map<String, JsonElement> = emptyMap(),
    val layer: Map<String, JsonElement> = emptyMap(),
    val state: Map<String, String>,
    val lightbar: Map<String, String>,
    val controller: Map<String, String> = emptyMap(),
    val chrome: ChromeTokensSpec = ChromeTokensSpec(),
)

/** The flight-screen panel look: translucent blurred panels, hairline border, type scale and spacing. */
@Serializable
data class ChromeTokensSpec(
    @SerialName("panel_bg") val panelBg: String = "#0B0D12EB",
    @SerialName("panel_bg_no_blur") val panelBgNoBlur: String = "#0B0D12F5",
    @SerialName("panel_blur_px") val panelBlurPx: Float = 8f,
    val border: String = "#FFFFFF1A",
    @SerialName("border_px") val borderPx: Float = 1f,
    @SerialName("radius_px") val radiusPx: Float = 6f,
    @SerialName("radius_small_px") val radiusSmallPx: Float = 4f,
    /** The visual height of every top-bar control; hit areas stay at least 48. */
    @SerialName("control_px") val controlPx: Float = 36f,
    val text: String = "#F5F7FA",
    @SerialName("text_dim") val textDim: String = "#FFFFFFA3",
    @SerialName("severity_ok") val severityOk: String = "#00C853",
    @SerialName("severity_warning") val severityWarning: String = "#F59E0B",
    @SerialName("severity_critical") val severityCritical: String = "#EF4444",
    /** The glyph beside each alert list item, by severity (`critical`, `warning`, `info`). */
    @SerialName("severity_icon") val severityIcon: Map<String, String> = mapOf("critical" to "✕", "warning" to "⚠", "info" to "ℹ"),
    @SerialName("stop_bg") val stopBg: String = "#C81E1E",
    @SerialName("stop_text") val stopText: String = "#FFFFFF",
    @SerialName("text_critical") val textCritical: String = "#FF8A80",
    val type: ChromeTypeSpec = ChromeTypeSpec(),
    @SerialName("icon_px") val iconPx: Float = 18f,
    @SerialName("gap_px") val gapPx: Float = 8f,
    @SerialName("pad_px") val padPx: Float = 10f,
    @SerialName("edge_inset_px") val edgeInsetPx: Float = 8f,
)

/** The flight-screen type scale, in CSS px (one px is one dp on Android). */
@Serializable
data class ChromeTypeSpec(
    @SerialName("top_bar_px") val topBarPx: Float = 13f,
    @SerialName("top_bar_line_px") val topBarLinePx: Float = 15f,
    @SerialName("value_px") val valuePx: Float = 16f,
    @SerialName("label_px") val labelPx: Float = 11f,
    @SerialName("chip_px") val chipPx: Float = 13f,
    @SerialName("chip_weight") val chipWeight: Int = 600,
)
