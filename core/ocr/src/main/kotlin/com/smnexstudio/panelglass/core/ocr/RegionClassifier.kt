package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextNorm
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.PixelSource
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import com.smnexstudio.panelglass.core.ocr.raster.Stats
import kotlin.math.abs

/**
 * Decides how a region may be erased by sampling a ring 8–12 px outside its bbox:
 *
 *  - low variance + high luminance → ENCLOSED (speech bubble; safe to flood-fill)
 *  - low variance + mid/low luminance → CAPTION (flat box; safe to fill with the box colour)
 *  - high variance → FREE, or SFX when the text reads like onomatopoeia, or IN_SCENE when it is rotated
 *
 * It runs before the engine call because SFX and dialogue need different prompts, and it is what
 * stops the eraser punching a white rectangle through a character's face.
 */
class RegionClassifier(
    private val ringInner: Int = 8,
    private val ringOuter: Int = 12,
    private val lowVarianceStdDev: Float = 20f,
    private val highLuminance: Int = 200,
) {
    fun classify(source: PixelSource, region: TextRegion): TextRegion {
        val ring = ringStats(source, region.bbox)
        val inner = source.crop(region.bbox)
        // Colours come from inside the text box: the larger of its two luminance classes is the ground the
        // lettering sits on, the smaller is the ink. The ring outside the box is wrong for lettering on a dark
        // burst (it straddles the burst's edge and reads as grey), and it is what decides the region kind below.
        val split = innerSplit(inner)
        val bg = split?.first ?: ring?.meanColor ?: 0xFFFFFFFF.toInt()
        val fg = split?.second ?: foregroundColor(inner, Raster.lum(bg))
        val lowVar = ring != null && ring.lum.stdDev <= lowVarianceStdDev
        val sfxLike = TextNorm.looksLikeSfx(region.text) && region.fontSizePx >= 0.03f * minOf(source.width, source.height)
        val kind = when {
            ring == null -> RegionKind.FREE
            lowVar && ring.lum.mean >= highLuminance -> RegionKind.ENCLOSED
            lowVar -> RegionKind.CAPTION
            sfxLike -> RegionKind.SFX
            abs(region.angle) >= IN_SCENE_ANGLE -> RegionKind.IN_SCENE
            else -> RegionKind.FREE
        }
        return region.copy(kind = kind, bgColor = bg, fgColor = fg)
    }

    class RingStats(val lum: Stats, val meanColor: Int)

    fun ringStats(source: PixelSource, bbox: IntRect): RingStats? {
        val outer = bbox.inflate(ringOuter).clamp(source.width, source.height)
        if (outer.isEmpty) return null
        val innerRect = bbox.inflate(ringInner)
        val crop = source.crop(outer)
        var n = 0; var s = 0.0; var s2 = 0.0
        var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until crop.height) {
            val ay = outer.top + y
            for (x in 0 until crop.width) {
                val ax = outer.left + x
                if (innerRect.contains(ax, ay)) continue
                val c = crop[x, y]
                val l = Raster.lum(c).toDouble()
                n++; s += l; s2 += l * l
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
        }
        if (n < 16) return null
        val mean = s / n
        val variance = maxOf(0.0, s2 / n - mean * mean)
        return RingStats(Stats(mean.toFloat(), variance.toFloat(), n), Raster.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()))
    }

    /** (background, ink) mean colours by Otsu over the box; null when one class is too small to be lettering. */
    private fun innerSplit(crop: Raster): Pair<Int, Int>? {
        val n = crop.width * crop.height
        if (n < 64) return null
        val gray = crop.toGray()
        val thr = gray.otsuThreshold()
        var nd = 0; var rd = 0L; var gd = 0L; var bd = 0L
        var nl = 0; var rl = 0L; var gl = 0L; var bl = 0L
        for (i in 0 until n) {
            val c = crop.argb[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (gray.px[i].toInt() and 0xFF <= thr) { nd++; rd += r; gd += g; bd += b } else { nl++; rl += r; gl += g; bl += b }
        }
        val minor = minOf(nd, nl)
        if (minor < n * MIN_CLASS_FRACTION) return null
        val dark = Raster.rgb((rd / nd).toInt(), (gd / nd).toInt(), (bd / nd).toInt())
        val light = Raster.rgb((rl / nl).toInt(), (gl / nl).toInt(), (bl / nl).toInt())
        return if (nd >= nl) dark to light else light to dark
    }

    /** Mean colour of the pixels on the far side of the background luminance: the glyph ink. */
    private fun foregroundColor(crop: Raster, bgLum: Int): Int {
        val darkText = bgLum >= 128
        var n = 0; var r = 0L; var g = 0L; var b = 0L
        for (c in crop.argb) {
            val l = Raster.lum(c)
            val ink = if (darkText) l < bgLum - 60 else l > bgLum + 60
            if (ink) { n++; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
        }
        if (n == 0) return if (darkText) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Raster.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    companion object {
        const val IN_SCENE_ANGLE = 8f
        /** Ink or ground below this share of the box is not a lettering class; fall back to the ring. */
        const val MIN_CLASS_FRACTION = 0.03f
    }
}
