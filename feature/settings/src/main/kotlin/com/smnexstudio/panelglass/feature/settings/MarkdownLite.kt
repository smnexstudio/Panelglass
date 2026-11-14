package com.smnexstudio.panelglass.feature.settings

/**
 * The little Markdown the bundled PRIVACY.md and LICENSES.md use, parsed for Settings' document pages: headings,
 * paragraphs, bullets (continued by indented lines), pipe tables, and inline **bold**, `code`, [links](…) and bare
 * https addresses. Anything else is shown as plain text. No library: these two files are the only input, and both are
 * ours (they are copied into the APK from the repository root at build time).
 */
internal object MarkdownLite {

    /** A run of text; [url] set makes it a link (already resolved: https only). */
    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false, val url: String? = null)

    sealed interface Block {
        data class Heading(val level: Int, val spans: List<Span>) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Bullet(val spans: List<Span>) : Block
        data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block
    }

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^[-*]\\s+(.*)$")
    private val TABLE_RULE = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?$")

    /**
     * Parses [text]. Relative links (`CHANGELOG.md`) resolve against [base] (`https://github.com/<repo>/blob/main/`);
     * anything that is not https after that (an anchor, `http:`, `mailto:`) stays plain text.
     */
    fun parse(text: String, base: String): List<Block> {
        val out = mutableListOf<Block>()
        val para = StringBuilder()
        var bullet: StringBuilder? = null
        var table: MutableList<String>? = null

        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(inline(para.toString(), base)).also { para.clear() }
            bullet?.let { out += Block.Bullet(inline(it.toString(), base)) }
            bullet = null
            table?.let { t -> tableOf(t, base)?.let { out += it } }
            table = null
        }

        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("|") -> {
                    if (table == null) flush()
                    (table ?: mutableListOf<String>().also { table = it }).add(trimmed)
                }
                HEADING.matches(trimmed) && !line.startsWith(" ") -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2], base))
                }
                BULLET.matches(trimmed) && !line.startsWith("    ") -> {
                    flush()
                    bullet = StringBuilder(BULLET.find(trimmed)!!.groupValues[1])
                }
                bullet != null && line.startsWith(" ") -> bullet!!.append(' ').append(trimmed)
                else -> {
                    if (bullet != null || table != null) flush()
                    if (para.isNotEmpty()) para.append(' ')
                    para.append(trimmed)
                }
            }
        }
        flush()
        return out
    }

    private fun tableOf(lines: List<String>, base: String): Block.Table? {
        val rows = lines.filterNot { TABLE_RULE.matches(it) }.map { cells(it).map { c -> inline(c, base) } }
        if (rows.isEmpty()) return null
        return Block.Table(rows.first(), rows.drop(1))
    }

    private fun cells(line: String): List<String> = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline runs of [s]: bold, code, links and bare https addresses; the rest is text. */
    fun inline(s: String, base: String): List<Span> {
        val out = mutableListOf<Span>()
        val text = StringBuilder()
        fun plain() { if (text.isNotEmpty()) out += Span(text.toString()).also { text.clear() } }
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> {
                    val end = s.indexOf("**", i + 2)
                    plain(); inline(s.substring(i + 2, end), base).forEach { out += it.copy(bold = true) }
                    i = end + 2
                }
                s[i] == '`' && s.indexOf('`', i + 1) > i + 1 -> {
                    val end = s.indexOf('`', i + 1)
                    plain(); out += Span(s.substring(i + 1, end), code = true)
                    i = end + 1
                }
                s[i] == '[' -> {
                    val close = s.indexOf("](", i + 1)
                    val end = if (close > i) s.indexOf(')', close + 2) else -1
                    if (close > i && end > close) {
                        plain()
                        val label = s.substring(i + 1, close)
                        out += Span(label, url = resolve(s.substring(close + 2, end).trim(), base))
                        i = end + 1
                    } else { text.append(s[i]); i++ }
                }
                s.startsWith("https://", i) -> {
                    var end = i
                    while (end < s.length && !s[end].isWhitespace()) end++
                    while (end > i && s[end - 1] in ".,;:)") end--
                    plain(); val url = s.substring(i, end); out += Span(url, url = url)
                    i = end
                }
                else -> { text.append(s[i]); i++ }
            }
        }
        plain()
        return out
    }

    /** An https link as it is; a relative path against [base]; anything else (anchors, http, mailto) is no link. */
    fun resolve(target: String, base: String): String? = when {
        target.startsWith("https://") -> target
        target.isEmpty() || target.startsWith("#") || target.contains(':') -> null
        else -> base + target.removePrefix("./").removePrefix("/")
    }
}
