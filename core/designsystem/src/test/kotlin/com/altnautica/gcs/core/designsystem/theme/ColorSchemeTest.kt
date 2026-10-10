package com.altnautica.gcs.core.designsystem.theme

import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.designsystem.tokens.AdosThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeTest {
    @Test
    fun everyThemeMapsPrimaryAndBackgroundFromRoles() {
        AdosThemeId.entries.forEach { id ->
            val roles = AdosColors.forTheme(id)
            val scheme = adosColorScheme(id)
            assertEquals("primary for $id", roles.accentPrimary, scheme.primary)
            assertEquals("background for $id", roles.bgCanvas, scheme.background)
        }
    }
}
