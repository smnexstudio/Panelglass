package com.smnexstudio.panelglass.core.engine.local

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * Undoes byte-level BPE leaking into a runtime's output. Qwen's vocabulary is GPT-2 style: every byte has a stand-in
 * character (printable bytes are themselves, the rest are U+0100..U+0143, e.g. `Ġ` for a space), and a token that
 * holds only part of a UTF-8 character comes back from the detokenizer as those stand-ins instead of the character.
 * So `é` arrives as `Ã©` and Devanagari as runs of `à¤…`. Every stand-in run that forms a valid UTF-8 sequence is
 * decoded back; anything else (a real `é` or `©` in the reply) is left alone because on its own it is not valid UTF-8.
 */
object ByteLevel {
    private val byteOf: Map<Char, Int> = buildMap {
        var n = 0
        for (b in 0..255) {
            val printable = b in 0x21..0x7E || b in 0xA1..0xAC || b in 0xAE..0xFF
            put(if (printable) b.toChar() else (256 + n++).toChar(), b)
        }
    }
    private const val SPACE = 'Ġ'   // Ġ
    private const val NEWLINE = 'Ċ' // Ċ

    fun repair(text: String): String {
        if (text.none { it.code in 0x80..0x143 }) return text
        val out = StringBuilder(text.length)
        var repaired = false
        var i = 0
        while (i < text.length) {
            val lead = byteOf[text[i]]
            val len = when (lead) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }
            val decoded = if (len > 0 && i + len <= text.length) decode(text, i, len) else null
            if (decoded != null) {
                out.append(decoded); i += len; repaired = true
            } else {
                out.append(text[i]); i++
            }
        }
        if (!repaired) return text
        // Space and newline stand-ins only mean anything next to the bytes they came with.
        return out.toString().replace(SPACE, ' ').replace(NEWLINE, '\n')
    }

    private fun decode(text: String, from: Int, len: Int): String? {
        val bytes = ByteArray(len)
        for (k in 0 until len) {
            val b = byteOf[text[from + k]] ?: return null
            if (k > 0 && b !in 0x80..0xBF) return null
            bytes[k] = b.toByte()
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
