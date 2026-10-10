package com.altnautica.gcs.core.extensions.parameters

import com.altnautica.gcs.core.extensions.manifest.asArray
import com.altnautica.gcs.core.extensions.manifest.asObject
import com.altnautica.gcs.core.extensions.manifest.num
import com.altnautica.gcs.core.extensions.manifest.scalar
import com.altnautica.gcs.core.extensions.manifest.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses `gcs.contributes.parameters[]`. An entry with a missing key, a duplicate key or a
 * malformed schema is dropped; an unknown binding falls back to `plugin.config`.
 */
object ParameterParser {

    fun parse(v: JsonElement?): List<PluginParameter> {
        val arr = v.asArray() ?: return emptyList()
        val out = ArrayList<PluginParameter>()
        val seen = HashSet<String>()
        for (raw in arr) {
            val entry = raw.asObject() ?: continue
            val key = entry["key"].str()?.takeIf { it.isNotEmpty() } ?: continue
            if (key in seen) continue
            // The camera selector has no schema: it stores a roster camera id (or "auto")
            // in the extension's own config.
            if (entry["control"].str() == "camera-selector") {
                val base = readUi(entry["ui"]) ?: ParameterUi()
                val ui = base.copy(
                    widget = ParameterWidget.Camera,
                    label = entry["label"].str() ?: base.label,
                    purpose = entry["purpose"].str() ?: base.purpose,
                )
                seen += key
                out += PluginParameter(
                    key = key,
                    schema = ParameterSchema(
                        type = ParameterType.Str,
                        default = JsonPrimitive(entry["default"].str() ?: "auto"),
                    ),
                    ui = ui,
                    binding = ParameterBinding.PluginConfig,
                )
                continue
            }
            val schema = readSchema(entry["schema"]) ?: continue
            if (!ParameterRules.validateSchema(schema).ok) continue
            val binding = entry["binding"].str()?.let { ParameterBinding.fromWire(it) } ?: ParameterBinding.PluginConfig
            seen += key
            out += PluginParameter(key, schema, readUi(entry["ui"]), binding)
        }
        return out
    }

    internal fun readSchema(raw: JsonElement?): ParameterSchema? {
        val o = raw.asObject() ?: return null
        val type = ParameterType.fromWire(o["type"].str()) ?: return null
        if (o.containsKey("minimum") && o["minimum"].num() == null) return null
        if (o.containsKey("maximum") && o["maximum"].num() == null) return null
        val enumVals = o["enum"].asArray()?.mapNotNull { it.scalar() }?.takeIf { it.isNotEmpty() }
        return ParameterSchema(
            type = type,
            minimum = o["minimum"].num(),
            maximum = o["maximum"].num(),
            step = o["step"].num(),
            enum = enumVals,
            pattern = o["pattern"].str(),
            default = o["default"].scalar(),
        )
    }

    internal fun readUi(raw: JsonElement?): ParameterUi? {
        val o: JsonObject = raw.asObject() ?: return null
        val visible = o["visible_if"].asObject()?.let { vi ->
            val k = vi["key"].str()
            val eq = vi["equals"].scalar()
            if (k != null && eq != null) VisibleIf(k, eq) else null
        }
        val bits = o["bits"].asArray()?.let { arr ->
            val seen = HashSet<Int>()
            arr.mapNotNull { e ->
                val b = e.asObject() ?: return@mapNotNull null
                val bit = b["bit"].num() ?: return@mapNotNull null
                val label = b["label"].str() ?: return@mapNotNull null
                if (bit < 0 || bit != Math.floor(bit) || !seen.add(bit.toInt())) return@mapNotNull null
                BitLabel(bit.toInt(), label)
            }.takeIf { it.isNotEmpty() }
        }
        val ui = ParameterUi(
            widget = ParameterWidget.fromWire(o["widget"].str()),
            label = o["label"].str(),
            group = o["group"].str(),
            help = o["help"].str(),
            visibleIf = visible,
            task = o["task"].str(),
            order = o["order"].num(),
            bits = bits,
            purpose = o["purpose"].str(),
        )
        return ui.takeIf { it != ParameterUi() }
    }
}
