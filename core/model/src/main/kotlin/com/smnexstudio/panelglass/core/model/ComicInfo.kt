package com.smnexstudio.panelglass.core.model

/**
 * The ComicInfo.xml fields the Studio reads from a CBZ and writes next to an exported chapter (the Anansi schema
 * that Kavita, Komga and most readers use). Built with plain string building and parsed with a small scanner over
 * the root's simple elements: no XML library, so a DOCTYPE or an entity is never resolved, only skipped.
 */
data class ComicInfo(
    val series: String = "",
    val number: String = "",
    val volume: Int? = null,
    val title: String = "",
    val summary: String = "",
    val writer: String = "",
    val penciller: String = "",
    val genre: String = "",
    val languageIso: String = "",
    val pageCount: Int? = null,
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val notes: String = "",
    /** `YesAndRightToLeft` for right-to-left reading, as ComicInfo's `Manga` field. */
    val manga: String = "",
) {
    fun toXml(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        append("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
        field("Title", title)
        field("Series", series)
        field("Number", number)
        field("Volume", volume?.toString())
        field("Summary", summary)
        field("Notes", notes)
        field("Year", year?.toString())
        field("Month", month?.toString())
        field("Day", day?.toString())
        field("Writer", writer)
        field("Penciller", penciller)
        field("Genre", genre)
        field("PageCount", pageCount?.toString())
        field("LanguageISO", languageIso)
        field("Manga", manga)
        append("</ComicInfo>\n")
    }

    private fun StringBuilder.field(name: String, value: String?) {
        if (value.isNullOrBlank()) return
        append("  <").append(name).append('>').append(escape(value.trim())).append("</").append(name).append(">\n")
    }

    companion object {
        /** Bigger than any real ComicInfo.xml; the importer reads no more than this. */
        const val MAX_BYTES = 256 * 1024

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // Characters XML 1.0 cannot carry at all.
                else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
            }
        }

        private val reference = Regex("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);")

        /** The elements [parse] reads; every other one is passed over. */
        private val FIELDS = setOf(
            "Series", "Number", "Volume", "Title", "Summary", "Writer", "Penciller", "Genre", "LanguageISO", "PageCount",
            "Year", "Month", "Day", "Notes", "Manga",
        )

        /**
         * Parses what it recognises and ignores the rest; malformed input gives an empty [ComicInfo], never an error.
         *
         * Plain string searches, each one either moving forward past what it read or done at most once per field, so
         * the time is linear in the input. Lazy regexes with a missing end (`<!--`, `<Title>` or `<![CDATA[` never
         * closed, repeated) rescanned to the end from every start: a crafted 256 KB file took minutes.
         */
        fun parse(xml: String): ComicInfo {
            val f = fields(root(stripDeclarations(xml)) ?: return ComicInfo())
            return ComicInfo(
                series = f["Series"].orEmpty(),
                number = f["Number"].orEmpty(),
                volume = f["Volume"]?.toIntOrNull(),
                title = f["Title"].orEmpty(),
                summary = f["Summary"].orEmpty(),
                writer = f["Writer"].orEmpty(),
                penciller = f["Penciller"].orEmpty(),
                genre = f["Genre"].orEmpty(),
                languageIso = f["LanguageISO"].orEmpty(),
                pageCount = f["PageCount"]?.toIntOrNull(),
                year = f["Year"]?.toIntOrNull(),
                month = f["Month"]?.toIntOrNull(),
                day = f["Day"]?.toIntOrNull(),
                notes = f["Notes"].orEmpty(),
                manga = f["Manga"].orEmpty(),
            )
        }

        /** [xml] without its comments and DOCTYPE (internal subset included); an unclosed one takes the rest. */
        private fun stripDeclarations(xml: String): String {
            val sb = StringBuilder(xml.length)
            var i = 0
            while (i < xml.length) {
                val lt = xml.indexOf('<', i)
                if (lt < 0) { sb.append(xml, i, xml.length); break }
                sb.append(xml, i, lt)
                when {
                    xml.startsWith("<!--", lt) -> {
                        val end = xml.indexOf("-->", lt + 4)
                        i = if (end < 0) xml.length else end + 3
                    }
                    xml.regionMatches(lt, "<!DOCTYPE", 0, 9, ignoreCase = true) -> {
                        // To its '>', or past its internal subset when a '[' opens before that '>'.
                        var k = lt + 9
                        while (k < xml.length && xml[k] != '>' && xml[k] != '[') k++
                        if (k < xml.length && xml[k] == '[') k = xml.indexOf(']', k).let { if (it < 0) xml.length else xml.indexOf('>', it) }
                        i = if (k < 0 || k >= xml.length) xml.length else k + 1
                    }
                    else -> { sb.append('<'); i = lt + 1 }
                }
            }
            return sb.toString()
        }

        /** What lies between the first `<ComicInfo …>` and the last `</ComicInfo>`; null without both. */
        private fun root(xml: String): String? {
            var open = xml.indexOf("<ComicInfo")
            var start = -1
            while (open >= 0) {
                val gt = tagEnd(xml, open + "<ComicInfo".length)
                if (gt != null) { start = gt + 1; break }
                open = xml.indexOf("<ComicInfo", open + 1)
            }
            if (start < 0) return null
            var close = xml.lastIndexOf("</ComicInfo")
            while (close >= start) {
                if (closeEnd(xml, close + "</ComicInfo".length) != null) return xml.substring(start, close)
                close = xml.lastIndexOf("</ComicInfo", close - 1)
            }
            return null
        }

        /**
         * The '>' ending an open tag whose name ends at [nameEnd]: right there, or after attributes (whitespace first).
         * Null for a self-closing tag, a longer name, or a '<' before any '>'. Reads no further than the next '<'.
         */
        private fun tagEnd(s: String, nameEnd: Int): Int? {
            if (nameEnd >= s.length) return null
            if (s[nameEnd] != '>' && !s[nameEnd].isWhitespace()) return null
            var k = nameEnd
            while (k < s.length && s[k] != '>' && s[k] != '<') k++
            return if (k < s.length && s[k] == '>' && s[k - 1] != '/') k else null
        }

        /** The index after the '>' of a closing tag whose name ends at [nameEnd] (`</Title >`); null if it is not one. */
        private fun closeEnd(s: String, nameEnd: Int): Int? {
            var k = nameEnd
            while (k < s.length && s[k].isWhitespace()) k++
            return if (k < s.length && s[k] == '>') k + 1 else null
        }

        /** The first complete element of each of [FIELDS] in [body], as text. */
        private fun fields(body: String): Map<String, String> {
            val f = HashMap<String, String>()
            // A field with no closing tag after its first opening has none after a later one either: searched once.
            val unclosed = HashSet<String>()
            var i = 0
            while (f.size + unclosed.size < FIELDS.size) {
                val lt = body.indexOf('<', i)
                if (lt < 0) break
                i = lt + 1
                var j = i
                while (j < body.length && (body[j].isLetterOrDigit() || body[j] == '_' || body[j] == '.' || body[j] == '-')) j++
                val name = body.substring(i, j)
                if (name !in FIELDS || name in f || name in unclosed) continue
                val gt = tagEnd(body, j) ?: continue
                var close = body.indexOf("</$name", gt + 1)
                var end: Int? = null
                while (close >= 0) {
                    end = closeEnd(body, close + 2 + name.length)
                    if (end != null) break
                    close = body.indexOf("</$name", close + 1)
                }
                if (end == null) { unclosed += name; continue }
                f[name] = text(body.substring(gt + 1, close))
                i = end
            }
            return f
        }

        /** Element text: CDATA kept literally, the five XML entities and character references decoded, others left as written. */
        private fun text(raw: String): String {
            if (raw.contains('<') && !raw.contains("<![CDATA[")) return "" // an element with children (Pages)
            val sb = StringBuilder()
            var last = 0
            while (true) {
                val open = raw.indexOf("<![CDATA[", last)
                if (open < 0) break
                val close = raw.indexOf("]]>", open + 9)
                if (close < 0) break
                sb.append(unescape(raw.substring(last, open))).append(raw, open + 9, close)
                last = close + 3
            }
            sb.append(unescape(raw.substring(last)))
            return sb.toString().trim()
        }

        private fun unescape(s: String): String = reference.replace(s) { m ->
            val r = m.groupValues[1]
            when {
                r.startsWith("#x") -> r.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                r.startsWith("#") -> r.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
                r == "amp" -> "&"
                r == "lt" -> "<"
                r == "gt" -> ">"
                r == "quot" -> "\""
                r == "apos" -> "'"
                else -> m.value
            }
        }

        private fun codePoint(cp: Int): String? =
            if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null
    }
}
