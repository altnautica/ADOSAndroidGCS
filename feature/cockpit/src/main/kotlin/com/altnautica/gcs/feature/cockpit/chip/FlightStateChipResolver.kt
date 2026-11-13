package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.skills.spec.FcModeClassSpec
import com.altnautica.gcs.core.skills.spec.FlightStateMatchSpec
import com.altnautica.gcs.core.skills.spec.FlightStateSpec
import com.altnautica.gcs.core.skills.spec.SpecColor
import com.altnautica.gcs.core.skills.spec.TokensSpec
import java.util.Locale

/** A button the chip offers beside its text. */
enum class ChipButton { SetUpAircraft, Resume, TakeControl, Abort }

/** One coloured text run of the chip; [color] is 0xAARRGGBB. */
data class ChipText(val text: String, val colorKey: String, val color: Long)

/**
 * The flight-state chip as drawn: the matched rule, its text and colour, whether it is
 * dimmed, its button, and the secondary line (hold reason, avoidance state, control mode).
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
    /** A VTOL transition the flight controller reports; the pilot state's own flag counts too. */
    val vtolTransitioning: Boolean = false,
    /** The running skill's detail line (`ORBIT 8 m`); read only while a skill owns. */
    val modeDetail: String? = null,
    /** Flight mode from the FC heartbeat, used when the vehicle reports no pilot state. */
    val fcModeName: String? = null,
)

/**
 * Evaluates `flight-state.json`: rules in order, first match wins; `{placeholders}` fill from
 * the pilot state and [ChipContext], a missing number reads `—`, and a trailing empty ` · `
 * segment is dropped. A vehicle with no pilot state shows its FC mode.
 */
class FlightStateChipResolver(
    private val spec: FlightStateSpec,
    private val tokens: TokensSpec,
    /** `setup-strings.json` strings, for the launch step names in `{step}`. */
    private val strings: Map<String, String> = emptyMap(),
) {

    fun resolve(pilot: PilotState?, ctx: ChipContext): FlightStateChipUi {
        if (pilot == null) return noPilot(ctx)
        val stale = ctx.nowMs - pilot.receivedAtMs >= spec.staleAfterMs
        val rule = spec.rules.firstOrNull { matches(it.match, pilot, ctx, stale) }
            ?: return FlightStateChipUi(
                ruleId = RULE_NONE,
                primary = chipText(wordLabel(pilot.owner.wire), FC_KEY),
                dim = false,
                button = null,
                secondary = secondary(pilot, ctx, stale),
            )
        val text = fill(rule.text, values(pilot, ctx)) +
            (rule.suffixIfLandingSeq?.takeIf { pilot.mission?.landingSeq == true }.orEmpty())
        return FlightStateChipUi(
            ruleId = rule.id,
            primary = chipText(text, rule.color),
            dim = rule.dim,
            button = rule.buttonIf?.takeIf { matches(it.match, pilot, ctx, stale) }?.let { button(it.button, pilot) }
                ?: button(rule.button, pilot),
            secondary = if (rule.id == RULE_LINK_STALE) emptyList() else secondary(pilot, ctx, stale),
        )
    }

    private fun noPilot(ctx: ChipContext): FlightStateChipUi {
        val rule = spec.rules.first { it.id == RULE_FC_OTHER }
        val mode = ctx.fcModeName?.uppercase()
        return FlightStateChipUi(
            ruleId = rule.id,
            primary = chipText(fill(rule.text, mapOf("fc_mode_name_upper" to (mode ?: NO_VALUE))), rule.color),
            dim = mode == null,
            button = null,
            secondary = emptyList(),
        )
    }

    private fun matches(m: FlightStateMatchSpec, p: PilotState, ctx: ChipContext, stale: Boolean): Boolean {
        m.pilotStale?.let { if (it != stale) return false }
        m.owner?.let { if (p.owner.wire != it) return false }
        m.ready?.let { if ((p.firstBlock == null) != it) return false }
        m.missionState?.let { if (p.mission?.state?.wire != it) return false }
        m.completeAgeLtMs?.let { limit ->
            val since = ctx.missionCompleteSinceMs ?: return false
            if (ctx.nowMs - since >= limit) return false
        }
        m.vtolTransitioning?.let { if ((p.vtolTransitioning || ctx.vtolTransitioning) != it) return false }
        m.fcModeClass?.let { if (fcModeClass(p) != it) return false }
        m.controlMode?.let { if (p.controlMode?.wire != it) return false }
        m.sequenceRunning?.let { if ((launchStep(p) != null) != it) return false }
        m.fcFailsafe?.let { if (p.fcFailsafe != it) return false }
        return true
    }

    /** `rtl`, `land` or null for the pilot's numeric FC mode, by firmware family. */
    fun fcModeClass(p: PilotState): String? {
        val mode = p.fcMode ?: return null
        val plane = p.vehicleClass == PilotVehicleClass.FixedWing || p.vehicleClass == PilotVehicleClass.Vtol
        fun inClass(c: FcModeClassSpec) = mode in (if (plane) c.plane else c.copter)
        return when {
            inClass(spec.fcModeClasses.rtl) -> "rtl"
            inClass(spec.fcModeClasses.land) -> "land"
            else -> null
        }
    }

    private fun values(p: PilotState, ctx: ChipContext): Map<String, String> = mapOf(
        "step" to launchStep(p)?.let(::stepLabel).orEmpty(),
        "first_block_msg" to p.firstBlock?.msg.orEmpty(),
        "profile" to profileLabel((p.profile ?: PilotProfile.Normal).wire),
        "skill_label" to (p.skill?.label ?: DEFAULT_SKILL_LABEL).replaceFirstChar { it.uppercaseChar() },
        "mode_detail" to (ctx.modeDetail?.takeIf { p.owner == PilotOwner.Skill } ?: DEFAULT_MODE_DETAIL),
        "dist_m" to num(ctx.goalDistM),
        "home_dist_m" to num(ctx.homeDistM),
        "setup_step" to wordLabel(p.setupStep.orEmpty()),
        "seq" to num(p.mission?.seq),
        "total" to num(p.mission?.total),
        "eta" to formatEta(ctx.missionEtaS),
        "fc_mode_name_upper" to (p.fcModeName ?: NO_VALUE).uppercase(),
    )

    /** A profile as the mode sheet labels it (`mode_sheet.profile`); an unknown value reads with a capital first letter. */
    private fun profileLabel(wire: String): String {
        val sheet = spec.modeSheet.profile
        val i = sheet.values.indexOf(wire)
        return if (i >= 0) sheet.labels[i] else wire.replaceFirstChar { it.uppercaseChar() }
    }

    /** The rule's button; the setup link shows only while the setup-needed readiness item is unmet. */
    private fun button(id: String?, p: PilotState): ChipButton? = when (id) {
        "setup_link_when_setup_needed" -> ChipButton.SetUpAircraft.takeIf { setupNeeded(p) }
        "resume" -> ChipButton.Resume
        "abort" -> ChipButton.Abort
        "take_control" -> ChipButton.TakeControl
        else -> null
    }

    /** The running launch step: a sequence is present, its step is not `done` and it has no error. */
    private fun launchStep(p: PilotState): LaunchStep? =
        p.sequence?.takeIf { it.step != LaunchStep.Done && it.error == null }?.step

    /**
     * The step's spec name (`flow.launch.step.<step>`, `after` reads `after_label`). The take-off
     * step reads as its action (`chip.launch_step.takeoff`), so the chip never says "Taking off · Take off".
     */
    private fun stepLabel(step: LaunchStep): String {
        val key = when (step) {
            LaunchStep.After -> "flow.launch.step.after_label"
            LaunchStep.Takeoff -> "chip.launch_step.takeoff"
            else -> "flow.launch.step.${step.wire}"
        }
        return strings[key] ?: step.wire
    }

    /** The secondary line in the spec's order (FC failsafe, speed cap, paused mission, hold reason, avoidance, control mode). */
    private fun secondary(p: PilotState, ctx: ChipContext, stale: Boolean): List<ChipText> = spec.secondary.order.mapNotNull { part ->
        when (part) {
            "fc_failsafe" -> spec.secondary.fcFailsafe?.takeIf { p.fcFailsafe }?.let { chipText(it.text, it.color) }
            "speed_cap" -> p.speedCap?.let { cap ->
                spec.secondary.speedCap?.let { chipText(it.text.replace("{v}", String.format(Locale.ROOT, "%.0f", cap.vHMps)), it.color) }
            }
            "mission_paused_line" -> spec.secondary.missionPausedLine?.takeIf { matches(it.match, p, ctx, stale) }?.let {
                chipText(fill(it.text, values(p, ctx)), it.color)
            }
            "hold_reason" -> p.holdReason?.takeIf { it.isNotEmpty() }?.let { reason ->
                val hold = spec.secondary.holdReason
                chipText(fill(hold.text, mapOf("hold_reason_label" to holdReasonLabel(reason))), hold.color)
            }
            "avoid_state" -> p.avoidState?.let { spec.secondary.avoidState[it.wire] }?.let { chipText(it.text, it.color) }
            "control_mode" -> p.controlMode?.let { chipText(spec.secondary.controlMode[it.wire] ?: wordLabel(it.wire), FC_KEY) }
            else -> null
        }
    }

    /** Label of a hold reason, keyed by its exact wire string; an unlisted reason reads with spaces. */
    fun holdReasonLabel(reason: String): String =
        spec.holdReasonLabels[reason] ?: reason.replace('-', ' ').replace('_', ' ')

    private fun chipText(text: String, colorKey: String) = ChipText(text, colorKey, color(colorKey))

    /** The `tokens.json` state colour for [key]; the FC colour when the key is unknown. */
    fun color(key: String): Long =
        tokens.state[key]?.let(SpecColor::argb) ?: tokens.state[FC_KEY]?.let(SpecColor::argb) ?: FALLBACK_GREY

    companion object {
        const val RULE_FC_OTHER = "fc_other"
        const val RULE_LINK_STALE = "link_stale"

        /** Rule id reported when no spec rule matches. */
        const val RULE_NONE = "none"
        private const val FC_KEY = "fc"
        private const val SETUP_NEEDED = "setup_needed"

        /** The pilot reports the aircraft still needs setup (its `setup_needed` readiness item is unmet). */
        fun setupNeeded(p: PilotState?): Boolean = p?.readiness?.any { it.id == SETUP_NEEDED && !it.ok } == true
        private const val DEFAULT_SKILL_LABEL = "Skill"
        private const val DEFAULT_MODE_DETAIL = "Active"
        private const val NO_VALUE = "—"
        private const val FALLBACK_GREY = 0xFF909090L
        private const val SEPARATOR = " · "
        private val PLACEHOLDER = Regex("\\{([a-z_]+)\\}")

        /**
         * The part of the pilot's owner label after the skill label, matched in any case
         * (`Follow · Orbit 8 m` → `Orbit 8 m`): a source for [ChipContext.modeDetail].
         */
        fun modeDetail(ownerLabel: String?, skillLabel: String?): String? {
            val label = ownerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (skillLabel == null) return null
            val prefix = skillLabel + SEPARATOR
            return when {
                label.startsWith(prefix, ignoreCase = true) -> label.substring(prefix.length).takeIf { it.isNotBlank() }
                label.equals(skillLabel, ignoreCase = true) -> null
                else -> label
            }
        }

        /** A wire identifier (`autotune_roll`) as a sentence-case word ("Autotune roll"). */
        fun wordLabel(s: String): String =
            s.replace(WORD_SEPARATORS, " ").lowercase().replaceFirstChar { it.uppercaseChar() }

        private val WORD_SEPARATORS = Regex("[-_]+")

        /** Fills `{name}` placeholders (an unknown one reads empty), then drops one trailing ` · `. */
        fun fill(template: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(template) { values[it.groupValues[1]].orEmpty() }.removeSuffix(SEPARATOR)

        /** A number rounded half up, or `—` when unknown. */
        fun num(v: Number?): String {
            val d = v?.toDouble()
            return if (d == null || !d.isFinite()) NO_VALUE else Math.round(d).toString()
        }

        /** `ETA m:ss`, or `ETA s s` under a minute; the total is rounded first (119.6 s reads 2:00); empty when unknown. */
        fun formatEta(s: Float?): String {
            val d = s?.toDouble()
            if (d == null || !d.isFinite()) return ""
            val total = Math.round(d)
            val m = total / 60
            return if (m > 0) "ETA $m:${(total % 60).toString().padStart(2, '0')}" else "ETA $total s"
        }

        /** True when the pilot state is older than the chip's staleness limit. */
        fun isStale(pilot: PilotState?, nowMs: Long, staleAfterMs: Long = PilotState.STALE_MS): Boolean =
            pilot != null && nowMs - pilot.receivedAtMs >= staleAfterMs
    }
}
