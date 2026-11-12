package com.altnautica.gcs.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-app language list, the system's per-app list and the shipped translations agree. */
class AppLocalesTest {

    private val appRes = File("../../app/src/main/res")

    @Test
    fun inAppListMatchesLocalesConfig() {
        val config = File(appRes, "xml/locales_config.xml").readText()
        val advertised = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(advertised, AppLocales.SUPPORTED.filter { it.isNotEmpty() })
    }

    @Test
    fun noLanguageIsOfferedWithoutItsTranslations() {
        AppLocales.SUPPORTED.filter { it.isNotEmpty() && it != "en" }.forEach { tag ->
            assertTrue("values-$tag missing", File(appRes, "values-$tag/strings.xml").exists())
        }
        // A partial translation folder must not exist for a language the app does not offer.
        val partial = appRes.listFiles().orEmpty().map { it.name }
            .filter { it.matches(Regex("values-[a-z]{2}")) }
            .map { it.removePrefix("values-") }
        assertTrue("untranslated locale folders: $partial", partial.all { it in AppLocales.SUPPORTED })
    }
}
