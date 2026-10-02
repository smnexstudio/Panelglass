package com.smnexstudio.panelglass.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class FontNameTableTest {
    /** A minimal sfnt: an offset table with one `name` table holding Windows-English records [names] (id → text). */
    private fun font(names: Map<Int, String>, signature: Int = 0x00010000): ByteArray {
        val strings = ByteArrayOutputStream()
        val records = names.map { (id, text) ->
            val bytes = text.toByteArray(Charsets.UTF_16BE)
            val off = strings.size()
            strings.write(bytes)
            Triple(id, bytes.size, off)
        }
        val name = ByteArrayOutputStream()
        DataOutputStream(name).apply {
            writeShort(0); writeShort(records.size); writeShort(6 + records.size * 12)
            records.forEach { (id, len, off) -> writeShort(3); writeShort(1); writeShort(0x409); writeShort(id); writeShort(len); writeShort(off) }
            write(strings.toByteArray())
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(signature); writeShort(1); writeShort(16); writeShort(0); writeShort(0)
            writeBytes("name"); writeInt(0); writeInt(12 + 16); writeInt(name.size())
            write(name.toByteArray())
        }
        return out.toByteArray()
    }

    @Test fun kinds() {
        assertEquals(FontNameTable.Kind.TRUETYPE, FontNameTable.kind(byteArrayOf(0, 1, 0, 0)))
        assertEquals(FontNameTable.Kind.OPENTYPE, FontNameTable.kind("OTTO".toByteArray()))
        assertEquals(FontNameTable.Kind.COLLECTION, FontNameTable.kind("ttcf".toByteArray()))
        assertEquals(FontNameTable.Kind.TRUETYPE, FontNameTable.kind("true".toByteArray()))
        assertNull(FontNameTable.kind("PK\u0003\u0004".toByteArray()))
        assertNull(FontNameTable.kind(byteArrayOf(0, 1)))
    }

    @Test fun familyAndStyleFromTheNameTable() {
        val n = FontNameTable.names(font(mapOf(1 to "Wild Words", 2 to "Bold Italic", 4 to "Wild Words Bold Italic")))!!
        assertEquals("Wild Words", n.family)
        assertEquals("Bold Italic", n.style)
        assertEquals("boldItalic", n.slot)
    }

    @Test fun typographicNamesWin() {
        // Ids 16 / 17 name the real family when 1 / 2 only carry a weight-split one.
        val n = FontNameTable.names(font(mapOf(1 to "Comic Neue Light", 2 to "Regular", 16 to "Comic Neue", 17 to "Light")))!!
        assertEquals("Comic Neue", n.family)
        assertEquals("regular", n.slot)
    }

    @Test fun brokenFilesGiveNothing() {
        assertNull(FontNameTable.names(ByteArray(0)))
        assertNull(FontNameTable.names(byteArrayOf(0, 1, 0, 0, 0, 9)))
        val f = font(mapOf(1 to "A"))
        assertNull(FontNameTable.names(f.copyOf(f.size - 3).also { it[26] = 0x7F }), "a table pointing past the end")
        assertNull(FontNameTable.names(font(emptyMap()).also { it[12] = 'x'.code.toByte() }), "no name table")
    }
}
