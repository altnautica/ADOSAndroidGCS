package com.altnautica.gcs.data.voice

import com.altnautica.gcs.data.telemetry.FlightMode

/**
 * Maps recognized speech text to GCS actions.
 *
 * Supports English and Hindi voice commands for common drone operations.
 * Matching is case-insensitive substring search, so "return to launch"
 * matches "please return to launch now" as well.
 */
sealed class GcsAction {
    data object Arm : GcsAction()
    data object Disarm : GcsAction()
    data object RTL : GcsAction()
    data object Land : GcsAction()
    data class SetMode(val mode: FlightMode) : GcsAction()
}

object CommandGrammar {

    private val commands = listOf(
        // English commands (ordered longest-first to match most specific)
        "return to launch" to GcsAction.RTL,
        "come back" to GcsAction.RTL,
        "rtl" to GcsAction.RTL,
        "arm" to GcsAction.Arm,
        "disarm" to GcsAction.Disarm,
        "land" to GcsAction.Land,
        "loiter" to GcsAction.SetMode(FlightMode.LOITER),
        "stabilize" to GcsAction.SetMode(FlightMode.STABILIZE),
        "auto" to GcsAction.SetMode(FlightMode.AUTO),
        "guided" to GcsAction.SetMode(FlightMode.GUIDED),
        "altitude hold" to GcsAction.SetMode(FlightMode.ALT_HOLD),
        "alt hold" to GcsAction.SetMode(FlightMode.ALT_HOLD),
        "position hold" to GcsAction.SetMode(FlightMode.POSHOLD),
        "pos hold" to GcsAction.SetMode(FlightMode.POSHOLD),
        "brake" to GcsAction.SetMode(FlightMode.BRAKE),
        "circle" to GcsAction.SetMode(FlightMode.CIRCLE),
        "sport" to GcsAction.SetMode(FlightMode.SPORT),
        "acro" to GcsAction.SetMode(FlightMode.ACRO),
        // Hindi commands
        "\u0909\u0921\u093c\u093e\u0928 \u092d\u0930\u094b" to GcsAction.Arm,          // "udaan bharo" (take off)
        "\u0935\u093e\u092a\u0938 \u0906\u0913" to GcsAction.RTL,       // "wapas aao" (come back)
        "\u0909\u0924\u0930\u094b" to GcsAction.Land,          // "utro" (land)
    )

    /**
     * Match recognized text against the command grammar.
     * Returns the first matching GcsAction, or null if no match.
     */
    fun match(text: String): GcsAction? {
        val lower = text.lowercase()
        return commands.firstOrNull { (key, _) ->
            lower.contains(key)
        }?.second
    }

    /**
     * Returns a human-readable description of the action for confirmation.
     */
    fun describe(action: GcsAction): String = when (action) {
        is GcsAction.Arm -> "Arm motors"
        is GcsAction.Disarm -> "Disarm motors"
        is GcsAction.RTL -> "Return to launch"
        is GcsAction.Land -> "Land"
        is GcsAction.SetMode -> "Switch to ${action.mode.label}"
    }
}
