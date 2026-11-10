package com.altnautica.gcs.core.skills.spec

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** The files of the cockpit spec, by their name under `cockpit-spec/`. */
enum class CockpitSpecFile(val fileName: String) {
    Builtins("builtins.json"),
    Ds4("ds4.json"),
    Layout("layout.json"),
    Layers("layers.json"),
    Views("views.json"),
    FlightState("flight-state.json"),
    Tokens("tokens.json"),
    Ds4Art("ds4-art.json"),
    Ds4Hotspots("ds4-hotspots.json"),
    GenericArt("gamepad-generic-art.json"),
    GenericHotspots("gamepad-generic-hotspots.json"),
    SetupStrings("setup-strings.json"),
    Tips("tips.json"),
}

/** Reads the raw text of one spec file. */
fun interface CockpitSpecSource {
    fun read(file: CockpitSpecFile): String
}

/** A spec source over a directory holding the JSON files (JVM tests, tools). */
class DirectorySpecSource(private val dir: File) : CockpitSpecSource {
    override fun read(file: CockpitSpecFile): String = File(dir, file.fileName).readText()
}

/** Pure parser for the cockpit spec files; no Android dependency. */
object CockpitSpecParser {
    const val VERSION = 1

    /** Asset directory of the spec inside the app. */
    const val ASSET_DIR = "cockpit-spec"

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = false
    }

    fun builtins(text: String): BuiltinsSpec = decode(BuiltinsSpec.serializer(), text) { it.v }
    fun ds4(text: String): Ds4Spec = decode(Ds4Spec.serializer(), text) { it.v }
    fun layout(text: String): LayoutSpec = decode(LayoutSpec.serializer(), text) { it.v }
    fun layers(text: String): LayersSpec = decode(LayersSpec.serializer(), text) { it.v }
    fun views(text: String): ViewsSpec = decode(ViewsSpec.serializer(), text) { it.v }
    fun flightState(text: String): FlightStateSpec = decode(FlightStateSpec.serializer(), text) { it.v }
    fun tokens(text: String): TokensSpec = decode(TokensSpec.serializer(), text) { it.v }
    fun art(text: String): ControllerArtSpec = decode(ControllerArtSpec.serializer(), text) { it.v }
    fun hotspots(text: String): ControllerHotspotsSpec = decode(ControllerHotspotsSpec.serializer(), text) { it.v }
    fun setupStrings(text: String): SetupStringsSpec = decode(SetupStringsSpec.serializer(), text) { it.v }
    fun tips(text: String): TipsSpec = decode(TipsSpec.serializer(), text) { it.v }

    /** Parses [file]'s [text] into its model. */
    fun parse(file: CockpitSpecFile, text: String): Any = when (file) {
        CockpitSpecFile.Builtins -> builtins(text)
        CockpitSpecFile.Ds4 -> ds4(text)
        CockpitSpecFile.Layout -> layout(text)
        CockpitSpecFile.Layers -> layers(text)
        CockpitSpecFile.Views -> views(text)
        CockpitSpecFile.FlightState -> flightState(text)
        CockpitSpecFile.Tokens -> tokens(text)
        CockpitSpecFile.Ds4Art, CockpitSpecFile.GenericArt -> art(text)
        CockpitSpecFile.Ds4Hotspots, CockpitSpecFile.GenericHotspots -> hotspots(text)
        CockpitSpecFile.SetupStrings -> setupStrings(text)
        CockpitSpecFile.Tips -> tips(text)
    }

    private inline fun <T> decode(strategy: DeserializationStrategy<T>, text: String, version: (T) -> Int): T {
        val value = json.decodeFromString(strategy, text)
        val v = version(value)
        require(v == VERSION) { "cockpit spec version $v is not supported" }
        return value
    }
}

/** Layer ids of a level given as a list; null for the `full` sentence form. */
val LevelSpec.layerIds: List<String>?
    get() = (layers as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Spec colour strings (`#RRGGBB` or `#RRGGBBAA`) as ARGB. */
object SpecColor {
    /** The colour as a 0xAARRGGBB long; null when [hex] is not a spec colour. */
    fun argb(hex: String): Long? {
        if (!hex.startsWith('#')) return null
        val digits = hex.substring(1)
        val value = digits.toLongOrNull(16) ?: return null
        return when (digits.length) {
            6 -> 0xFF000000L or value
            8 -> ((value and 0xFF) shl 24) or (value ushr 8)
            else -> null
        }
    }
}
