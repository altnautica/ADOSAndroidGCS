package com.altnautica.gcs.feature.settings.licences

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticesMarkdownTest {

    @Test
    fun parsesHeadingsParagraphsAndTables() {
        val md = """
            # Third-party notices

            Licensed under `GPL-3.0-only`, with the
            components below.

            ## Libraries

            | Library | Licence |
            |---|---|
            | OkHttp | Apache-2.0 |
            | Short |
        """.trimIndent()
        val blocks = NoticesMarkdown.parse(md)
        assertEquals(
            listOf(
                NoticeBlock.Heading(1, "Third-party notices"),
                NoticeBlock.Paragraph("Licensed under GPL-3.0-only, with the components below."),
                NoticeBlock.Heading(2, "Libraries"),
                NoticeBlock.Table(listOf("Library", "Licence"), listOf(listOf("OkHttp", "Apache-2.0"), listOf("Short", ""))),
            ),
            blocks,
        )
    }
}
