package com.altnautica.gcs.feature.configure.params

import java.util.Locale

/** One parameter line of a saved parameter file. [type] is the MAVLink `MAV_PARAM_TYPE` when known. */
data class ParamFileEntry(val name: String, val value: Double, val type: Int? = null)

enum class ParamFileFormat {
    /** Tab-separated `sysid compid name value type`, as written by QGroundControl. */
    Qgc,

    /** `NAME,value` lines, as written by Mission Planner. */
    MissionPlanner,
}

/** Reads and writes `.params` / `.param` files in both common layouts. */
object ParamFile {
    private const val MAV_PARAM_TYPE_REAL32 = 9

    fun parse(text: String): List<ParamFileEntry> = text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val tokens = line.split('\t', ',', ' ').filter { it.isNotEmpty() }
        when {
            tokens.size >= 5 && tokens[0].toIntOrNull() != null && tokens[1].toIntOrNull() != null -> {
                val value = tokens[3].toDoubleOrNull() ?: return@mapNotNull null
                ParamFileEntry(tokens[2], value, tokens[4].toIntOrNull())
            }
            tokens.size >= 2 -> tokens[1].toDoubleOrNull()?.let { ParamFileEntry(tokens[0], it) }
            else -> null
        }
    }.toList()

    fun write(
        entries: List<ParamFileEntry>,
        format: ParamFileFormat,
        sysId: Int = 1,
        compId: Int = 1,
    ): String = buildString {
        val sorted = entries.sortedBy { it.name }
        when (format) {
            ParamFileFormat.Qgc -> {
                append("# Onboard parameters for vehicle ").append(sysId).append('\n')
                append("# Vehicle-Id Component-Id Name Value Type\n")
                sorted.forEach {
                    append(sysId).append('\t').append(compId).append('\t').append(it.name).append('\t')
                        .append(formatValue(it.value)).append('\t').append(it.type ?: MAV_PARAM_TYPE_REAL32).append('\n')
                }
            }
            ParamFileFormat.MissionPlanner -> sorted.forEach {
                append(it.name).append(',').append(formatValue(it.value)).append('\n')
            }
        }
    }

    /** Whole numbers are written without a fraction; others with full float precision. */
    fun formatValue(value: Double): String =
        if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else String.format(Locale.ROOT, "%.9g", value).trimEnd('0').trimEnd('.')

    /** Differences between the vehicle's values and a file; names absent on the vehicle are listed separately. */
    fun compare(vehicle: Map<String, Double>, file: List<ParamFileEntry>): ParamComparison {
        val changed = mutableListOf<ParamDiff>()
        val missing = mutableListOf<String>()
        file.forEach { entry ->
            val current = vehicle[entry.name]
            when {
                current == null -> missing += entry.name
                !sameValue(current, entry.value) -> changed += ParamDiff(entry.name, current, entry.value)
            }
        }
        return ParamComparison(changed, missing)
    }

    /** Parameters travel as float32, so compare at float precision. */
    fun sameValue(a: Double, b: Double): Boolean = a.toFloat() == b.toFloat()
}

data class ParamDiff(val name: String, val current: Double, val fromFile: Double)

data class ParamComparison(val changed: List<ParamDiff>, val unknownNames: List<String>)
