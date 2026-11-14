package com.smnexstudio.panelglass.feature.settings

import com.smnexstudio.panelglass.feature.settings.MarkdownLite.Block
import com.smnexstudio.panelglass.feature.settings.MarkdownLite.Span
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The Markdown subset Settings' document pages read (PRIVACY.md, LICENSES.md). Invented text throughout. */
class MarkdownLiteTest {
    private val base = "https://github.com/owner/repo/blob/main/"
    private fun parse(s: String) = MarkdownLite.parse(s, base)

    @Test fun headingsParagraphsAndBulletsAreBlocks() {
        val blocks = parse("# Title\n\nOne line\nand its next line.\n\n## Part\n- first\n  continued\n- second\n")
        assertEquals(
            listOf(
                Block.Heading(1, listOf(Span("Title"))),
                Block.Paragraph(listOf(Span("One line and its next line."))),
                Block.Heading(2, listOf(Span("Part"))),
                Block.Bullet(listOf(Span("first continued"))),
                Block.Bullet(listOf(Span("second"))),
            ),
            blocks,
        )
    }

    @Test fun boldCodeAndLinksAreSpans() {
        val spans = MarkdownLite.inline("A **bold** word, `code`, [a link](https://example.com/x) and https://example.org/y.", base)
        assertEquals(
            listOf(
                Span("A "), Span("bold", bold = true), Span(" word, "), Span("code", code = true), Span(", "),
                Span("a link", url = "https://example.com/x"), Span(" and "),
                Span("https://example.org/y", url = "https://example.org/y"), Span("."),
            ),
            spans,
        )
    }

    @Test fun linksResolveToHttpsOrNothing() {
        assertEquals(base + "CHANGELOG.md", MarkdownLite.resolve("CHANGELOG.md", base))
        assertEquals(base + "docs/A.md", MarkdownLite.resolve("./docs/A.md", base))
        assertNull(MarkdownLite.resolve("http://example.com", base))
        assertNull(MarkdownLite.resolve("#privacy", base))
        assertNull(MarkdownLite.resolve("mailto:someone@example.com", base))
    }

    @Test fun aTableKeepsItsHeaderAndRowsWithoutTheRule() {
        val t = parse("| Name | Kind |\n|---|---|\n| One | **first** |\n| Two | second |\n").single() as Block.Table
        assertEquals(listOf(listOf(Span("Name")), listOf(Span("Kind"))), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(Span("first", bold = true)), t.rows[0][1])
    }

    @Test fun unclosedMarkersStayText() {
        assertEquals(listOf(Span("a **b and [c")), MarkdownLite.inline("a **b and [c", base))
    }

    /** The repository's own documents parse into something readable, and every link they hold is https. */
    @Test fun theBundledDocumentsParse() {
        for (name in listOf("PRIVACY.md", "LICENSES.md")) {
            val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve(name) }.first { it.isFile }
            val blocks = parse(file.readText())
            assertTrue(blocks.count { it is Block.Heading } >= 3, name)
            val urls = blocks.flatMap { b ->
                when (b) {
                    is Block.Heading -> b.spans
                    is Block.Paragraph -> b.spans
                    is Block.Bullet -> b.spans
                    is Block.Table -> (b.header + b.rows.flatten()).flatten()
                }
            }.mapNotNull { it.url }
            assertTrue(urls.isNotEmpty() && urls.all { it.startsWith("https://") }, name)
        }
    }
}
