package com.altnautica.gcs.feature.cockpit.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/** The cockpit spec's shared strings (`setup-strings.json`): flow, banner, skill and voice text. */
val LocalCockpitStrings = staticCompositionLocalOf<SetupStringsSpec> { error("cockpit spec strings are not provided") }

/** The spec string for [key] with `{name}` placeholders filled from [args]. */
@Composable
@ReadOnlyComposable
fun specText(key: String, vararg args: Pair<String, String>): String = LocalCockpitStrings.current.format(key, args.toMap())
