package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.model.ReadinessItem
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A setup card's state (`setup.card.state.*`). */
enum class CardState(val key: String) {
    Done("done"),
    Attention("attention"),
    InProgress("in_progress"),
    NotSet("not_set"),
    Optional("optional"),
    NotNeeded("not_needed"),

    /** A card of a wizard that waits on the ground wizard (flight tuning). */
    AfterSetup("after_setup"),
}

/**
 * One component card of the overview: the steps it covers, its state, and [flag], why the
 * flight controller says it needs attention now (a failing readiness item), or null.
 */
data class SetupCard(val id: String, val titleKey: String, val steps: List<SetupStep>, val state: CardState, val flag: String? = null)

/** The overview's primary button: resume an interrupted run, open the running one, start, or run again. */
enum class PrimaryKind(val labelKey: String) {
    Resume("setup.overview.continue"),
    Continue("setup.overview.continue"),
    Start("setup.overview.start"),
    RunAgain("setup.overview.run_again"),
}

/**
 * The Aircraft setup overview, a port of the shared web module `setup-wizard/overview.ts`:
 * the wizard's steps grouped into component cards, each card's state and one-line summary,
 * the progress count and the primary button.
 */
object SetupOverview {
    private data class CardDef(val id: String, val steps: List<String>, val optional: Boolean = false)

    /** The `ground` wizard's cards. A card whose steps the adapter does not list is hidden; `detect` is not shown. */
    private val GROUND_CARDS = listOf(
        CardDef("frame", listOf("frame")),
        CardDef("sensors", listOf("accel", "level", "gyro", "compass", "airspeed")),
        CardDef("radio", listOf("radio", "rc_ados_switch")),
        CardDef("outputs", listOf("outputs")),
        CardDef("battery", listOf("battery")),
        CardDef("flight", listOf("initial_params", "notch_static")),
        CardDef("safety", listOf("ados_profile")),
        CardDef("ados", listOf("ados_intro"), optional = true),
    )

    /** The blocker an operator abort leaves on the step it stopped. */
    const val CANCELLED_BLOCKER = "setup.blocker.cancelled"

    private val WAITS_ON_GROUND = setOf("setup.blocker.ground_not_done", "setup.blocker.ground_required")

    /** A step the operator aborted: failed with only the cancelled blocker. It reads "Cancelled", neutral. */
    fun isCancelled(s: SetupStep): Boolean =
        s.state == SetupStepState.Failed && s.blockers.isNotEmpty() && s.blockers.all { it == CANCELLED_BLOCKER }

    private fun finished(s: SetupStep) = s.state == SetupStepState.Done || s.state == SetupStepState.Skipped

    /**
     * A card's state from its steps: a failed or blocked step needs attention (an operator
     * cancel does not); an active step is in progress; all skipped is not needed; all finished
     * is done; otherwise not set (or optional).
     */
    fun cardState(steps: List<SetupStep>, optional: Boolean = false): CardState = when {
        steps.any { !isCancelled(it) && (it.state == SetupStepState.Failed || (it.blockers.isNotEmpty() && !finished(it))) } -> CardState.Attention
        steps.any { it.state == SetupStepState.Running || it.state == SetupStepState.WaitingUser } -> CardState.InProgress
        steps.isNotEmpty() && steps.all { it.state == SetupStepState.Skipped } -> CardState.NotNeeded
        steps.isNotEmpty() && steps.all(::finished) -> CardState.Done
        optional -> CardState.Optional
        else -> CardState.NotSet
    }

    /**
     * The steps as the overview counts them. A wizard filed as done counts every step it has
     * no record of as done; a step with a state of its own keeps it.
     */
    fun filedSteps(wizard: SetupWizard): List<SetupStep> =
        if (wizard.state != SetupWizardState.Done) wizard.steps
        else wizard.steps.map { if (it.state == SetupStepState.Pending) it.copy(state = SetupStepState.Done) else it }

    /**
     * The setup steps the flight controller's readiness says need fixing now, each with what is
     * wrong: its parameter rows as `NAME have → want`, else the item's message. An
     * `apply_profile` fix flags the ADOS profile step, a `setup_step` fix its step.
     */
    fun readinessFlags(readiness: List<ReadinessItem>): Map<String, String> {
        val flags = linkedMapOf<String, String>()
        for (r in readiness) {
            if (r.ok) continue
            val step = when (val f = r.fix) {
                ReadinessFix.ApplyProfile -> "ados_profile"
                is ReadinessFix.SetupStep -> f.step
                else -> null
            } ?: continue
            if (step in flags) continue
            val rows = r.params.map { p -> "${p.name} ${p.have?.let { JsonPrimitive(it).displayText() } ?: "—"} → ${p.want}" }
            flags[step] = if (rows.isNotEmpty()) rows.joinToString(" · ") else r.msg
        }
        return flags
    }

    private fun card(wizard: SetupWizard, id: String, titleKey: String, steps: List<SetupStep>, flags: Map<String, String>, optional: Boolean = false): SetupCard {
        var state = cardState(steps, optional)
        val flag = steps.firstNotNullOfOrNull { flags[it.step] }
        // The FC's own report outranks a stored "done": its values drifted since.
        if (flag != null && state != CardState.InProgress) state = CardState.Attention
        if (state == CardState.NotSet && wizard.blockers.any { it in WAITS_ON_GROUND }) state = CardState.AfterSetup
        return SetupCard(id, titleKey, steps, state, flag)
    }

    /** The wizard's cards: the ground wizard by component, any other wizard one card per step. */
    fun cards(wizard: SetupWizard, flags: Map<String, String> = emptyMap()): List<SetupCard> {
        val all = filedSteps(wizard)
        if (wizard.id != FcSetupApi.WIZARD_GROUND) {
            return all.map { card(wizard, it.step, it.titleKey, listOf(it), flags) }
        }
        val byId = all.associateBy { it.step }
        return GROUND_CARDS.mapNotNull { def ->
            val steps = def.steps.mapNotNull(byId::get)
            if (steps.isEmpty()) null else card(wizard, def.id, "setup.card.${def.id}.title", steps, flags, def.optional)
        }
    }

    /** The steps the overview counts and the guided view walks, in order. */
    fun guidedSteps(wizard: SetupWizard): List<SetupStep> = cards(wizard).flatMap { it.steps }

    /** "N of M done": finished (done or not needed) steps out of the shown ones. */
    fun progress(wizard: SetupWizard): Pair<Int, Int> {
        val steps = guidedSteps(wizard)
        return steps.count(::finished) to steps.size
    }

    /** The step a card opens: a flagged step first, else its first unfinished step, else its first. */
    fun target(card: SetupCard, flags: Map<String, String> = emptyMap()): SetupStep =
        card.steps.firstOrNull { it.step in flags } ?: card.steps.firstOrNull { !finished(it) } ?: card.steps.first()

    /**
     * The step a run in progress is on in [wizard], from the overview's top-level `running` (a
     * whole-wizard run, or one step run on its own after the wizard finished), else null.
     */
    fun runningStep(overview: FcSetupOverview, wizard: SetupWizard): String? =
        overview.running?.takeIf { it.wizard == wizard.id }?.step

    /** The primary button; [running] is true while a run of this wizard is in progress, whatever its filed state. */
    fun primary(state: SetupWizardState, running: Boolean = false): PrimaryKind = when {
        running -> PrimaryKind.Continue
        state == SetupWizardState.Interrupted -> PrimaryKind.Resume
        state == SetupWizardState.Running -> PrimaryKind.Continue
        state == SetupWizardState.NotStarted -> PrimaryKind.Start
        else -> PrimaryKind.RunAgain
    }

    /**
     * A card's one-line summary: the flight controller's flag when it has one, "Not needed" when
     * skipped, else up to three facts from its finished steps' results; empty when there are none.
     */
    fun summary(card: SetupCard, text: SetupText): String {
        card.flag?.let { return it }
        if (card.state == CardState.NotNeeded) return text.format("setup.card.state.not_needed", emptyMap())
        val parts = mutableListOf<String>()
        for (s in card.steps) {
            if (s.state != SetupStepState.Done) continue
            for ((k, v) in s.result.orEmpty()) {
                val p = v as? JsonPrimitive ?: continue
                if (p is JsonNull) continue
                val option = "setup.input.$k.${p.content}"
                val summaryKey = "setup.summary.$k"
                when {
                    text.format(option, emptyMap()) != option -> parts += text.format(option, emptyMap())
                    text.format(summaryKey, emptyMap()) != summaryKey -> parts += text.format(summaryKey, mapOf("v" to p.displayText()))
                }
            }
        }
        return parts.take(MAX_SUMMARY_PARTS).joinToString(" · ")
    }

    private const val MAX_SUMMARY_PARTS = 3

    /** Flight-controller firmware names as their projects write them. */
    private val FIRMWARE_NAMES = mapOf(
        "ardupilot" to "ArduPilot",
        "arducopter" to "ArduCopter",
        "arduplane" to "ArduPlane",
        "px4" to "PX4",
        "betaflight" to "Betaflight",
        "inav" to "INAV",
    )

    /** A firmware name as its project writes it ("ardupilot" → "ArduPilot"), else as sent. */
    fun firmwareLabel(fw: String?): String? = fw?.let { FIRMWARE_NAMES[it.lowercase()] ?: it }

    private val SNAPSHOT_STAMP = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2})[-:](\d{2})[-:](\d{2})Z""")

    /** A snapshot name or stamp (`2026-11-24T13-09-07Z.param`, or an ISO time) as a local date and time; null when it is neither. */
    fun snapshotTime(stamp: String, zone: ZoneId = ZoneId.systemDefault()): String? {
        val m = SNAPSHOT_STAMP.find(stamp)
        val iso = m?.let { "${it.groupValues[1]}T${it.groupValues[2]}:${it.groupValues[3]}:${it.groupValues[4]}Z" } ?: stamp
        val instant = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone).format(instant)
    }

    /** Result keys the engine stores for itself, never shown to the operator. */
    private val INTERNAL_RESULT_KEYS = setOf("uid", "uid_source", "mission_state_inferred")

    /** A step result as the operator reads it: internal keys left out, the firmware named as its project writes it, a snapshot as its time. */
    fun resultRows(result: JsonObject?): JsonObject = JsonObject(
        result.orEmpty().filterKeys { it !in INTERNAL_RESULT_KEYS }.mapValues { (k, v) ->
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                s != null && k == "firmware" -> JsonPrimitive(firmwareLabel(s))
                s != null && k == "snapshot" -> JsonPrimitive(snapshotTime(s) ?: s)
                else -> v
            }
        },
    )
}
