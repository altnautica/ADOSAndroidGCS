package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** A setup card's state (`setup.card.state.*`). */
enum class CardState(val key: String) {
    Done("done"),
    Attention("attention"),
    InProgress("in_progress"),
    NotSet("not_set"),
    Optional("optional"),
    NotNeeded("not_needed"),
}

/** One component card of the overview: the steps it covers and its state. */
data class SetupCard(val id: String, val titleKey: String, val steps: List<SetupStep>, val state: CardState)

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

    private fun finished(s: SetupStep) = s.state == SetupStepState.Done || s.state == SetupStepState.Skipped

    /**
     * A card's state from its steps: a failed or blocked step needs attention; an active step is
     * in progress; all skipped is not needed; all finished is done; otherwise not set (or optional).
     */
    fun cardState(steps: List<SetupStep>, optional: Boolean = false): CardState = when {
        steps.any { it.state == SetupStepState.Failed || (it.blockers.isNotEmpty() && !finished(it)) } -> CardState.Attention
        steps.any { it.state == SetupStepState.Running || it.state == SetupStepState.WaitingUser } -> CardState.InProgress
        steps.isNotEmpty() && steps.all { it.state == SetupStepState.Skipped } -> CardState.NotNeeded
        steps.isNotEmpty() && steps.all(::finished) -> CardState.Done
        optional -> CardState.Optional
        else -> CardState.NotSet
    }

    /** The wizard's cards: the ground wizard by component, any other wizard one card per step. */
    fun cards(wizard: SetupWizard): List<SetupCard> {
        if (wizard.id != FcSetupApi.WIZARD_GROUND) {
            return wizard.steps.map { SetupCard(it.step, it.titleKey, listOf(it), cardState(listOf(it))) }
        }
        val byId = wizard.steps.associateBy { it.step }
        return GROUND_CARDS.mapNotNull { def ->
            val steps = def.steps.mapNotNull(byId::get)
            if (steps.isEmpty()) null else SetupCard(def.id, "setup.card.${def.id}.title", steps, cardState(steps, def.optional))
        }
    }

    /** The steps the overview counts and the guided view walks, in order. */
    fun guidedSteps(wizard: SetupWizard): List<SetupStep> = cards(wizard).flatMap { it.steps }

    /** "N of M done": finished (done or not needed) steps out of the shown ones. */
    fun progress(wizard: SetupWizard): Pair<Int, Int> {
        val steps = guidedSteps(wizard)
        return steps.count(::finished) to steps.size
    }

    /** The step a card opens: its first unfinished step, else its first. */
    fun target(card: SetupCard): SetupStep = card.steps.firstOrNull { !finished(it) } ?: card.steps.first()

    fun primary(state: SetupWizardState): PrimaryKind = when (state) {
        SetupWizardState.Interrupted -> PrimaryKind.Resume
        SetupWizardState.Running -> PrimaryKind.Continue
        SetupWizardState.NotStarted -> PrimaryKind.Start
        SetupWizardState.Done, SetupWizardState.Failed, SetupWizardState.Aborted -> PrimaryKind.RunAgain
    }

    /**
     * A card's one-line summary: "Not needed" when skipped, else up to three facts from its
     * finished steps' results (an option's label, or a `setup.summary.<key>` template); empty
     * when there are none.
     */
    fun summary(card: SetupCard, text: SetupText): String {
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
}
