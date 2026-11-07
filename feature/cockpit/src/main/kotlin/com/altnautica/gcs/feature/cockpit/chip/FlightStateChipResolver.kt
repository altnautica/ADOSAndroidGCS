package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.skills.spec.FlightStateRuleSpec
import com.altnautica.gcs.core.skills.spec.FlightStateSpec
import com.altnautica.gcs.core.skills.spec.SpecColor
import com.altnautica.gcs.core.skills.spec.TokensSpec
import kotlin.math.roundToInt

/** A button the chip offers beside its text. */
enum class ChipButton { SetUpAircraft, Resume, TakeControl }

/** One coloured text run of the chip; [color] is 0xAARRGGBB. */
data class ChipText(val text: String, val colorKey: String, val color: Long)

/**
 * The flight-state chip as drawn: the matched rule, its text and colour, whether it is
 * dimmed, its button, and the secondary line (hold reason or avoidance, then control mode).
 */
data class FlightStateChipUi(
    val ruleId: String,
    val primary: ChipText,
    val dim: Boolean,
    val button: ChipButton?,
    val secondary: List<ChipText>,
)

/** Values the chip reads besides the pilot state; each is null when unknown. */
data class ChipContext(
    val nowMs: Long,
    /** Local receive time at which the mission state turned `complete`. */
    val missionCompleteSinceMs: Long? = null,
    val homeDistM: Float? = null,
    /** Horizontal distance to the pilot's goal, for `goto`. */
    val goalDistM: Float? = null,
    /** Seconds to the next waypoint while a mission flies. */
    val missionEtaS: Float? = null,
    val vtolTransitioning: Boolean = false,
    val setupStep: String? = null,
    /** Flight mode from the FC heartbeat, used when the vehicle reports no pilot state. */
    val fcModeName: String? = null,
)

/**
 * Evaluates `flight-state.json`: rules in order, first match wins; `{placeholders}` fill from
 * the pilot state and [ChipContext]. A placeholder with no value drops its ` · ` segment, so
 * the chip never shows an empty field. A vehicle with no pilot state shows its FC mode.
 */
class FlightStateChipResolver(private val spec: FlightStateSpec, private val tokens: TokensSpec) {

    fun resolve(pilot: PilotState?, ctx: ChipContext): FlightStateChipUi {
        if (pilot == null) return noPilot(ctx)
        val stale = ctx.nowMs - pilot.receivedAtMs > spec.staleAfterMs
        val rule = spec.rules.firstOrNull { matches(it, pilot, ctx, stale) } ?: spec.rules.last()
        val text = fill(rule.text, values(pilot, ctx)) +
            (rule.suffixIfLandingSeq?.takeIf { pilot.mission?.landingSeq == true }.orEmpty())
        return FlightStateChipUi(
            ruleId = rule.id,
            primary = chipText(text, rule.color),
            dim = rule.dim,
            button = button(rule.button, pilot),
            secondary = if (stale) emptyList() else secondary(pilot),
        )
    }

    private fun noPilot(ctx: ChipContext): FlightStateChipUi {
        val rule = spec.rules.first { it.id == RULE_FC_OTHER }
        val mode = ctx.fcModeName?.uppercase()
        return FlightStateChipUi(
            ruleId = rule.id,
            primary = chipText(fill(rule.text, mapOf("fc_mode_name_upper" to mode)), rule.color),
            dim = mode == null,
            button = null,
            secondary = emptyList(),
        )
    }

    private fun matches(rule: FlightStateRuleSpec, p: PilotState, ctx: ChipContext, stale: Boolean): Boolean {
        val m = rule.match
        m.pilotStale?.let { if (it != stale) return false }
        m.owner?.let { if (p.owner.wire != it) return false }
        m.ready?.let { if ((p.firstBlock == null) != it) return false }
        m.missionState?.let { if (p.mission?.state?.wire != it) return false }
        m.completeAgeLtMs?.let { limit ->
            val since = ctx.missionCompleteSinceMs ?: return false
            if (ctx.nowMs - since >= limit) return false
        }
        m.vtolTransitioning?.let { if (it != ctx.vtolTransitioning) return false }
        m.fcModeClass?.let { if (fcModeClass(p) != it) return false }
        m.controlMode?.let { if (p.controlMode?.wire != it) return false }
        return true
    }

    /** `rtl`, `land` or null for the pilot's numeric FC mode, by firmware family. */
    fun fcModeClass(p: PilotState): String? {
        val mode = p.fcMode ?: return null
        val plane = p.vehicleClass == PilotVehicleClass.FixedWing || p.vehicleClass == PilotVehicleClass.Vtol
        fun inClass(c: com.altnautica.gcs.core.skills.spec.FcModeClassSpec) = mode in (if (plane) c.plane else c.copter)
        return when {
            inClass(spec.fcModeClasses.rtl) -> "rtl"
            inClass(spec.fcModeClasses.land) -> "land"
            else -> null
        }
    }

    private fun values(p: PilotState, ctx: ChipContext): Map<String, String?> {
        val skillLabel = p.skill?.label?.uppercase()
        return mapOf(
            "first_block_msg" to p.firstBlock?.msg,
            "profile_upper" to p.profile?.wire?.uppercase(),
            "skill_label_upper" to (skillLabel ?: p.ownerLabel?.uppercase()),
            "mode_detail" to modeDetail(p.ownerLabel, skillLabel),
            "dist_m" to ctx.goalDistM?.roundToInt()?.toString(),
            "setup_step" to ctx.setupStep,
            "seq" to p.mission?.seq?.toString(),
            "total" to p.mission?.total?.toString(),
            "eta" to ctx.missionEtaS?.let(::formatEta),
            "home_dist_m" to ctx.homeDistM?.roundToInt()?.toString(),
            "fc_mode_name_upper" to p.fcModeName?.uppercase(),
        )
    }

    private fun button(id: String?, p: PilotState): ChipButton? = when (id) {
        "setup_link_when_setup_needed" -> ChipButton.SetUpAircraft.takeIf { p.firstBlock != null }
        "resume" -> ChipButton.Resume
        "take_control" -> ChipButton.TakeControl
        else -> null
    }

    private fun secondary(p: PilotState): List<ChipText> {
        val lines = ArrayList<ChipText>(2)
        var attention: ChipText? = null
        for (item in spec.secondary.order) {
            when (item) {
                "hold_reason" -> if (attention == null) {
                    p.holdReason?.let { reason ->
                        val label = holdReasonLabel(reason)
                        attention = chipText(fill(spec.secondary.holdReason.text, mapOf("hold_reason_label" to label)), spec.secondary.holdReason.color)
                    }
                }
                "avoid_state" -> if (attention == null) {
                    p.avoidState?.let { s -> spec.secondary.avoidState[s.wire]?.let { attention = chipText(it.text, it.color) } }
                }
                "control_mode" -> {
                    attention?.let(lines::add)
                    attention = null
                    p.controlMode?.let { m -> spec.secondary.controlMode[m.wire]?.let { lines += chipText(it, CONTROL_MODE_COLOR) } }
                }
            }
        }
        attention?.let(lines::add)
        return lines
    }

    /** Label of a hold reason; the spec's table is keyed with underscores, the wire uses hyphens. */
    fun holdReasonLabel(reason: String): String =
        spec.holdReasonLabels[reason]
            ?: spec.holdReasonLabels[reason.replace('-', '_')]
            ?: spec.holdReasonLabels[reason.replace('_', '-')]
            ?: reason.replace('-', ' ').replace('_', ' ').uppercase()

    private fun chipText(text: String, colorKey: String) = ChipText(text, colorKey, color(colorKey))

    /** The `tokens.json` state colour for [key]; the stale grey when the key is unknown. */
    fun color(key: String): Long =
        tokens.state[key]?.let(SpecColor::argb) ?: tokens.state[STALE_KEY]?.let(SpecColor::argb) ?: FALLBACK_GREY

    companion object {
        const val RULE_FC_OTHER = "fc_other"
        private const val STALE_KEY = "stale"
        private const val CONTROL_MODE_COLOR = "stale"
        private const val FALLBACK_GREY = 0xFF909090L
        private const val SEPARATOR = " · "
        private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

        /** The part of the pilot's owner label after the skill label (`FOLLOW · ORBIT 8 m` → `ORBIT 8 m`). */
        fun modeDetail(ownerLabel: String?, skillLabelUpper: String?): String? {
            val label = ownerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (skillLabelUpper == null) return null
            val prefix = skillLabelUpper + SEPARATOR
            return when {
                label.uppercase().startsWith(prefix) -> label.substring(prefix.length).takeIf { it.isNotBlank() }
                label.equals(skillLabelUpper, ignoreCase = true) -> null
                else -> label
            }
        }

        /** Fills `{name}` placeholders; a segment between ` · ` separators whose value is missing is dropped. */
        fun fill(template: String, values: Map<String, String?>): String =
            template.split(SEPARATOR).mapNotNull { segment ->
                var missing = false
                val filled = PLACEHOLDER.replace(segment) { m ->
                    values[m.groupValues[1]] ?: run {
                        missing = true
                        ""
                    }
                }
                filled.takeUnless { missing }
            }.joinToString(SEPARATOR)

        /** ETA as `m:ss`, or `s s` under a minute. */
        fun formatEta(s: Float): String {
            val total = s.roundToInt().coerceAtLeast(0)
            return if (total < 60) "$total s" else "%d:%02d".format(total / 60, total % 60)
        }

        /** True when the pilot state is older than the chip's staleness limit. */
        fun isStale(pilot: PilotState?, nowMs: Long, staleAfterMs: Long = PilotState.STALE_MS): Boolean =
            pilot != null && nowMs - pilot.receivedAtMs > staleAfterMs
    }
}
