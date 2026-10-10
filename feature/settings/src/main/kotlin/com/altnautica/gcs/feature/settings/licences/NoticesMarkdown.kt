package com.altnautica.gcs.feature.settings.licences

/** The block subset `THIRD_PARTY_NOTICES.md` uses: headings, paragraphs, pipe tables. */
sealed interface NoticeBlock {
    data class Heading(val level: Int, val text: String) : NoticeBlock
    data class Paragraph(val text: String) : NoticeBlock

    /** One table; every row is aligned to [header]. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : NoticeBlock
}

object NoticesMarkdown {

    fun parse(markdown: String): List<NoticeBlock> {
        val blocks = mutableListOf<NoticeBlock>()
        val paragraph = StringBuilder()
        val table = mutableListOf<List<String>>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) blocks += NoticeBlock.Paragraph(inline(paragraph.toString()))
            paragraph.clear()
        }

        fun flushTable() {
            if (table.isNotEmpty()) {
                val header = table.first()
                val rows = table.drop(1).filterNot(::isSeparator).map { row -> List(header.size) { i -> row.getOrElse(i) { "" } } }
                blocks += NoticeBlock.Table(header, rows)
            }
            table.clear()
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    flushParagraph()
                    flushTable()
                }
                line.startsWith("#") -> {
                    flushParagraph()
                    flushTable()
                    val level = line.takeWhile { it == '#' }.length
                    blocks += NoticeBlock.Heading(level, inline(line.drop(level).trim()))
                }
                line.trimStart().startsWith("|") -> {
                    flushParagraph()
                    table += cells(line)
                }
                else -> {
                    flushTable()
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flushParagraph()
        flushTable()
        return blocks
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()) }

    private fun isSeparator(row: List<String>): Boolean = row.all { cell -> cell.isNotEmpty() && cell.all { it == '-' || it == ':' } }

    /** Drops inline code ticks and emphasis markers; the text stays. */
    private fun inline(text: String): String = text.replace("`", "").replace("**", "")
}
