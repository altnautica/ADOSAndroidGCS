package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.runtime.staticCompositionLocalOf
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

/** A setup failure: the engine's message key ([key]) or a local reason ([local]). */
data class SetupProblem(val key: String? = null, val local: SetupLocalProblem? = null)

enum class SetupLocalProblem { Unreachable, Unauthorized, Unsupported, Armed }

fun AgentError.setupProblem(): SetupProblem = when (this) {
    is AgentError.Server -> message?.let { SetupProblem(key = it) } ?: SetupProblem(local = SetupLocalProblem.Unreachable)
    is AgentError.Network, is AgentError.PeerApi, is AgentError.RelayTicket -> SetupProblem(local = SetupLocalProblem.Unreachable)
    AgentError.Unauthorized, is AgentError.PinRequired -> SetupProblem(local = SetupLocalProblem.Unauthorized)
    AgentError.FeatureAbsent, AgentError.ProfileMismatch -> SetupProblem(local = SetupLocalProblem.Unsupported)
    is AgentError.Armed -> SetupProblem(local = SetupLocalProblem.Armed)
}
