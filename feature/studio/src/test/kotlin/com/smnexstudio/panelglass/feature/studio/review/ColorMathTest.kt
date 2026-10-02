package com.smnexstudio.panelglass.feature.studio.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ColorMathTest {
    @Test fun hsvRoundTrip() {
        for (c in listOf(0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFF1F3A93, 0xFFC0392B, 0xFF7F8C8D, 0xFFF39C12).map { it.toInt() }) {
            assertEquals(c, ColorMath.toArgb(ColorMath.toHsv(c)), "%08X".format(c))
        }
    }

    @Test fun primaryHues() {
        assertEquals(0f, ColorMath.toHsv(0xFFFF0000.toInt()).h, 0.01f)
        assertEquals(120f, ColorMath.toHsv(0xFF00FF00.toInt()).h, 0.01f)
        assertEquals(240f, ColorMath.toHsv(0xFF0000FF.toInt()).h, 0.01f)
        assertEquals(0xFFFFFF00.toInt(), ColorMath.toArgb(Hsv(60f, 1f, 1f)))
    }

    @Test fun alphaIsKept() {
        assertEquals(0x80FF0000.toInt(), ColorMath.toArgb(Hsv(0f, 1f, 1f), alpha = 0x80))
    }

    @Test fun parse() {
        assertEquals(0xFF1F3A93.toInt(), ColorMath.parse("#1f3a93", withAlpha = false))
        assertEquals(0xFF1F3A93.toInt(), ColorMath.parse("1F3A93", withAlpha = false))
        assertEquals(0xFFFF00CC.toInt(), ColorMath.parse("F0C", withAlpha = false))
        // Typed without alpha, a colour keeps the opacity it had.
        assertEquals(0x80FFFFFF.toInt(), ColorMath.parse("FFFFFF", withAlpha = true, keepAlpha = 0x80))
        assertEquals(0x80FFFFFF.toInt(), ColorMath.parse("80FFFFFF", withAlpha = true))
        assertNull(ColorMath.parse("80FFFFFF", withAlpha = false))
        assertNull(ColorMath.parse("12345", withAlpha = false))
        assertNull(ColorMath.parse("GG0000", withAlpha = false))
        assertNull(ColorMath.parse("", withAlpha = false))
    }

    @Test fun format() {
        assertEquals("1F3A93", ColorMath.format(0xFF1F3A93.toInt(), withAlpha = false))
        assertEquals("1F3A93", ColorMath.format(0xFF1F3A93.toInt(), withAlpha = true))
        assertEquals("801F3A93", ColorMath.format(0x801F3A93.toInt(), withAlpha = true))
        assertEquals("1F3A93", ColorMath.format(0x801F3A93.toInt(), withAlpha = false))
    }
}
