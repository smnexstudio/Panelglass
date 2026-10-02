package com.smnexstudio.panelglass.core.render

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import java.io.File

/**
 * Checks a font file the user added: that the platform loads it and draws with it, and which scripts it covers. The
 * typeface is built with no system fallback, so `hasGlyph` answers for this font alone (with fallback every script
 * would pass).
 */
object FontProbe {
    /** The scripts [file] draws (catalogue ids), or null when the platform cannot load it. */
    fun probe(file: File): Set<String>? = runCatching {
        val font = Font.Builder(file).build()
        val face = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
        val paint = Paint().apply { typeface = face }
        // It must draw something: a font with no glyph for any script is refused.
        StudioFonts.SAMPLES.filter { (_, line) -> line.all { it.isWhitespace() || !it.isLetter() || paint.hasGlyph(it.toString()) } }
            .keys.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
