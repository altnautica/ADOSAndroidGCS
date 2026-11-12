package com.altnautica.gcs.feature.configure.wizard

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.configure.R
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Resolves a setup string key from the cockpit spec's `setup-strings.json`, interpolating `{arg}`. */
fun interface SetupText {
    fun format(key: String, args: Map<String, String>): String
}

/** Supplies the setup strings to the wizard composables. */
val LocalSetupText = staticCompositionLocalOf<SetupText> { SetupText { key, _ -> key } }

@Module
@InstallIn(SingletonComponent::class)
object SetupTextModule {
    @Provides
    fun provideSetupText(spec: CockpitSpecRepository): SetupText =
        SetupText { key, args -> spec.setupStrings.format(key, args) }
}

/** The label of [key]: the key itself when the strings have it, else [prefix] + [key], else the bare key. */
fun SetupText.label(key: String, prefix: String, args: Map<String, String> = emptyMap()): String {
    val direct = format(key, args)
    if (direct != key) return direct
    val prefixed = prefix + key
    return format(prefixed, args).takeIf { it != prefixed } ?: key
}

/** A live or result value as text; a string value that is itself a setup key (`setup.question.…`) resolves to its string. */
fun SetupText.value(v: JsonElement, args: Map<String, String> = emptyMap()): String {
    val p = v as? JsonPrimitive
    if (p != null && p.isString && p.content.startsWith("setup.")) return format(p.content, args)
    return v.displayText()
}

/** A JSON value as display text: strings bare, numbers trimmed to at most two decimals. */
fun JsonElement.displayText(): String = when (this) {
    JsonNull -> "–"
    is JsonPrimitive -> if (isString) content else content.toDoubleOrNull()?.let(::trimNumber) ?: content
    else -> toString()
}

private fun trimNumber(v: Double): String =
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

/** Step arguments as string interpolation values. */
fun JsonObject.textArgs(): Map<String, String> = filterKeys { it != INPUTS_KEY }.mapValues { (_, v) -> v.displayText() }

/**
 * A setup failure: the engine's message key ([key], `setup.err.<code>`) and the local reason
 * ([local]) that words it when the spec has no text for the key.
 */
data class SetupProblem(val key: String? = null, val local: SetupLocalProblem? = null)

enum class SetupLocalProblem {
    Unreachable,

    /** The agent answered but its setup engine is not running. */
    EngineDown,

    /** The engine refused the request with a code the spec has no text for, or with none. */
    Refused,
    Unauthorized,
    Unsupported,
    Armed,
}

private const val ERR_PREFIX = "setup.err."

/** The engine's own code for an unavailable setup service. */
private const val SETUP_UNAVAILABLE = "setup_unavailable"

/** The spec key of an engine error: a bare code (`not_running`) becomes `setup.err.not_running`. */
fun setupErrKey(err: String): String = if (err.startsWith("setup.")) err else ERR_PREFIX + err

fun AgentError.setupProblem(): SetupProblem = when (this) {
    is AgentError.Server -> serverProblem(code, errorCode)
    is AgentError.Network, is AgentError.PeerApi, is AgentError.RelayTicket -> SetupProblem(local = SetupLocalProblem.Unreachable)
    AgentError.Unauthorized, is AgentError.PinRequired -> SetupProblem(local = SetupLocalProblem.Unauthorized)
    AgentError.FeatureAbsent, AgentError.ProfileMismatch -> SetupProblem(local = SetupLocalProblem.Unsupported)
    is AgentError.Armed -> SetupProblem(local = SetupLocalProblem.Armed)
}

private fun serverProblem(status: Int, err: String?): SetupProblem = when {
    err == SETUP_UNAVAILABLE -> SetupProblem(key = setupErrKey(err), local = SetupLocalProblem.EngineDown)
    err != null -> SetupProblem(key = setupErrKey(err), local = SetupLocalProblem.Refused)
    // The agent answers 503 with a plain detail when its setup engine does not reply.
    status == 503 -> SetupProblem(local = SetupLocalProblem.EngineDown)
    // A refusal without a code (the engine's bare `ok: false`).
    status in 400..499 -> SetupProblem(local = SetupLocalProblem.Refused)
    else -> SetupProblem(local = SetupLocalProblem.Unreachable)
}

/** The spec text of [problem]'s engine key, or null when it has none (the caller words the local reason). */
fun SetupText.problemText(problem: SetupProblem): String? =
    problem.key?.let { key -> format(key, emptyMap()).takeIf { it != key } }

/** The engine's code behind [this], filling the placeholder of [localText]'s string. */
val SetupProblem.engineCode: String? get() = key?.removePrefix(ERR_PREFIX)

/** The string that words [this] when the spec has no text for its key. */
@StringRes
fun SetupProblem.localText(): Int = when (local) {
    SetupLocalProblem.Unauthorized -> R.string.cfg_setup_err_unauthorized
    SetupLocalProblem.Unsupported -> R.string.cfg_setup_err_unsupported
    SetupLocalProblem.Armed -> R.string.cfg_setup_err_armed
    SetupLocalProblem.EngineDown -> R.string.cfg_setup_err_engine_down
    SetupLocalProblem.Refused -> if (engineCode != null) R.string.cfg_setup_err_refused_code else R.string.cfg_setup_err_refused
    SetupLocalProblem.Unreachable, null -> R.string.cfg_setup_err_unreachable
}

/** A setup failure in operator words: the engine's spec text, else the local reason (unreachable by default). */
@Composable
fun SetupProblemText(problem: SetupProblem, modifier: Modifier = Modifier) {
    val text = LocalSetupText.current.problemText(problem) ?: stringResource(problem.localText(), problem.engineCode.orEmpty())
    Text(text, modifier, color = AdosTheme.colors.statusError)
}
