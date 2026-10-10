package com.altnautica.gcs.feature.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language. Android 13+ stores it in the system ([LocaleManager]) so
 * the system language picker agrees; older releases keep the tag in a small
 * shared-preferences file read synchronously by [wrap] when the activity
 * attaches its base context.
 */
object AppLocales {

    /** The shipped translations; an empty tag follows the system language. */
    val SUPPORTED: List<String> = listOf("", "en", "hi", "bn", "mr", "ta", "te", "kn", "pa")

    private const val PREFS = "app_locale"
    private const val KEY = "tag"

    fun current(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.language.orEmpty()
    } else {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    /** Applies [tag] ("" = system); recreates [activity] where the platform does not. */
    fun apply(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Base-context wrapper for releases before Android 13. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    /** The language's own name for its tag, e.g. "हिन्दी" for "hi". */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}

/** The activity behind a Compose [Context], unwrapping context wrappers. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
