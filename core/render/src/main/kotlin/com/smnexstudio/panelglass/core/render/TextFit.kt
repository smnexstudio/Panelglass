package com.smnexstudio.panelglass.core.render

import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/**
 * Text fitting shared by the reader's [RegionRenderer] and the Studio's [StudioRenderer]: the largest size at which the
 * wrapped text fits a box, by binary search on two tests that only get harder as the size grows (the layout's height
 * fits; the widest unbreakable run fits the width). Reading line widths or break positions back from the layout made
 * sizes fail at random and drove text to ~10 px (CLAUDE.md), so nothing else is tested.
 */
internal object TextFit {
    const val LINE_SPACING = 0.95f

    /** Largest text size whose wrapped layout fits [w]×[h] with every word whole on its line; falls back to [minSize]. */
    fun fit(
        text: String, w: Int, h: Int, tp: TextPaint, maxSize: Float, minSize: Float, why: StringBuilder? = null,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER, rtl: Boolean = false,
    ): StaticLayout {
        var lo = minSize; var hi = maxSize
        var best: StaticLayout? = null
        repeat(8) {
            val mid = (lo + hi) / 2f
            tp.textSize = mid
            val l = layout(text, w, tp, alignment, rtl)
            val tall = l.height > h
            val wide = longestWord(text, tp) > w
            val fits = !tall && !wide
            why?.append(" ")?.append(mid.toInt())?.append(if (fits) "ok" else (if (tall) "T" else "") + (if (wide) "W" else ""))
            if (fits) { best = l; lo = mid } else hi = mid
        }
        if (best == null) { tp.textSize = minSize; best = layout(text, w, tp, alignment, rtl) } else tp.textSize = lo
        return best!!
    }

    /** Whether [text] at the paint's size lays out in [w]×[h] with no word split across lines. */
    fun fitsAt(text: String, w: Int, h: Int, tp: TextPaint): Boolean =
        longestWord(text, tp) <= w && layout(text, w, tp).height <= h

    /** Width of the widest unbreakable run of [text] at the paint's current size. */
    fun longestWord(text: String, tp: TextPaint): Float = RegionRenderer.longestRun(text) { s, e -> tp.measureText(text, s, e) }

    fun layout(
        text: String, w: Int, tp: TextPaint, alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER, rtl: Boolean = false,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, tp, w.coerceAtLeast(1))
            .setAlignment(alignment)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setTextDirection(if (rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()
}
