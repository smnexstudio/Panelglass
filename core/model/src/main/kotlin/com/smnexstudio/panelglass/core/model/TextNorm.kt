package com.smnexstudio.panelglass.core.model

object TextNorm {
    private val strip = Regex("[\\s\\p{Punct}、。！？…〜～―‐・「」『』（）♪☆★]+")

    /** Normalisation used for SFX dedupe / cache keys: fold width, strip punctuation and whitespace, lower-case. */
    fun sfxKey(text: String): String {
        val folded = StringBuilder(text.length)
        for (ch in text) folded.append(foldWidth(ch))
        return strip.replace(folded, "").lowercase()
    }

    private fun foldWidth(c: Char): Char = when (c.code) {
        in 0xFF01..0xFF5E -> (c.code - 0xFEE0).toChar()   // fullwidth ASCII → ASCII
        0x3000 -> ' '
        else -> c
    }

    fun isKatakana(c: Char) = c.code in 0x30A0..0x30FF || c.code in 0x31F0..0x31FF
    fun isHiragana(c: Char) = c.code in 0x3040..0x309F
    fun isHangul(c: Char) = c.code in 0xAC00..0xD7AF || c.code in 0x1100..0x11FF || c.code in 0x3130..0x318F
    fun isCjkIdeograph(c: Char) = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF
    fun isCjk(c: Char) = isKatakana(c) || isHiragana(c) || isHangul(c) || isCjkIdeograph(c)

    /** Heuristic: short, mostly katakana/hangul, often with elongation marks → onomatopoeia. */
    fun looksLikeSfx(text: String): Boolean {
        val t = sfxKey(text)
        if (t.isEmpty() || t.length > 6) return false
        val kana = t.count { isKatakana(it) || isHangul(it) || it == 'ー' || it == '〜' }
        return kana.toFloat() / t.length >= 0.7f
    }
}
