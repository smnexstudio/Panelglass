package com.smnexstudio.panelglass.core.render

/**
 * Reads what a font file says about itself, without the platform: its kind from the first bytes, and the family and
 * style names from its `name` table (ids 1, 2, 4, 16 and 17). Only these few bytes are read, by bounds-checked
 * offsets, so a broken or hostile file yields null, never an exception or a read outside the data.
 */
object FontNameTable {
    enum class Kind(val extension: String) { TRUETYPE("ttf"), OPENTYPE("otf"), COLLECTION("ttc") }

    /** Family ("Wild Words"), style ("Bold Italic") and full name, as the font's own `name` table gives them. */
    data class Names(val family: String, val style: String, val full: String) {
        val bold: Boolean get() = style.contains("bold", ignoreCase = true) || style.contains("black", ignoreCase = true) ||
            style.contains("heavy", ignoreCase = true)
        val italic: Boolean get() = style.contains("italic", ignoreCase = true) || style.contains("oblique", ignoreCase = true)
        /** `regular`, `bold`, `italic` or `boldItalic`: the slot this file fills in its family. */
        val slot: String get() = when {
            bold && italic -> "boldItalic"
            bold -> "bold"
            italic -> "italic"
            else -> "regular"
        }
    }

    /** The file's kind from its signature: `00010000` or `true` (TrueType), `OTTO` (CFF), `ttcf` (collection). */
    fun kind(head: ByteArray): Kind? {
        if (head.size < 4) return null
        return when {
            head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte() -> Kind.TRUETYPE
            tag(head, 0) == "true" -> Kind.TRUETYPE
            tag(head, 0) == "OTTO" -> Kind.OPENTYPE
            tag(head, 0) == "ttcf" -> Kind.COLLECTION
            else -> null
        }
    }

    /** The names of the font in [data] (the first font of a collection); null when there is no readable `name` table. */
    fun names(data: ByteArray): Names? = runCatching {
        var font = 0
        if (kind(data) == Kind.COLLECTION) font = u32(data, 12).toInt() // the first font's offset
        val tables = u16(data, font + 4)
        var name = -1
        for (i in 0 until tables) {
            val rec = font + 12 + i * 16
            if (tag(data, rec) == "name") name = u32(data, rec + 8).toInt()
        }
        if (name < 0) return null
        val count = u16(data, name + 2)
        val strings = name + u16(data, name + 4)
        val found = HashMap<Int, String>()
        val rank = HashMap<Int, Int>()
        for (i in 0 until count) {
            val r = name + 6 + i * 12
            val platform = u16(data, r)
            val encoding = u16(data, r + 2)
            val language = u16(data, r + 4)
            val id = u16(data, r + 6)
            if (id !in setOf(1, 2, 4, 16, 17)) continue
            val len = u16(data, r + 8)
            val off = strings + u16(data, r + 10)
            if (off < 0 || off + len > data.size) continue
            val text = when {
                platform == 3 || platform == 0 -> String(data, off, len, Charsets.UTF_16BE)
                platform == 1 && encoding == 0 -> String(data, off, len, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            // Prefer Windows English, then any Unicode record, then Mac Roman.
            val score = when {
                platform == 3 && language == 0x409 -> 3
                platform == 3 || platform == 0 -> 2
                else -> 1
            }
            if (score > (rank[id] ?: 0)) { found[id] = text; rank[id] = score }
        }
        val family = found[16] ?: found[1] ?: return null
        val style = found[17] ?: found[2] ?: "Regular"
        Names(family, style, found[4] ?: "$family $style")
    }.getOrNull()

    private fun u16(b: ByteArray, at: Int): Int {
        if (at < 0 || at + 2 > b.size) throw IndexOutOfBoundsException()
        return ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    }

    private fun u32(b: ByteArray, at: Int): Long {
        if (at < 0 || at + 4 > b.size) throw IndexOutOfBoundsException()
        return (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
    }

    private fun tag(b: ByteArray, at: Int): String =
        if (at < 0 || at + 4 > b.size) "" else String(b, at, 4, Charsets.ISO_8859_1)
}
