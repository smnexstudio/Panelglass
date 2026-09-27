package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.FloodFill
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.ocr.raster.Morphology
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import com.smnexstudio.panelglass.core.ocr.raster.Stats
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/**
 * Result of an erase attempt on a crop. [raster] is the crop after erasure (untouched when
 * [erased] is false), [glyphMask] is in crop-local coordinates.
 */
class EraseResult(
    val raster: Raster,
    val erased: Boolean,
    val glyphMask: GlyphMask?,
    val method: Method,
) {
    enum class Method { NONE, CONTAINER_FILL, HALO_FILL, INPAINT, REJECTED_SMEAR }
}

/**
 * Removes source glyphs from a crop so translated text can be drawn on clean pixels.
 *
 * ENCLOSED / CAPTION: flood-fill from the container ring to find the background, fill the holes
 * (glyphs) with the sampled colour. FREE / IN_SCENE: Otsu glyph mask, halo test first, then ray
 * inpainting gated by gradient variance so smeared results are discarded rather than shown.
 */
class TextEraser(
    private val floodTolerance: Int = 28,
    private val glyphDilate: Int = 3,
    private val haloDilate: Int = 5,
    private val haloStdDevMax: Float = 18f,
    private val haloMinLum: Int = 150,
) {
    /**
     * @param crop pixels covering [cropRect]; [region] bbox is in image space and lies inside [cropRect].
     */
    fun erase(crop: Raster, cropRect: IntRect, region: TextRegion): EraseResult = when (region.kind) {
        RegionKind.ENCLOSED, RegionKind.CAPTION -> eraseContainer(crop, cropRect, region)
        RegionKind.FREE, RegionKind.IN_SCENE, RegionKind.SFX -> eraseFree(crop, cropRect, region)
    }

    // ---- enclosed / caption -------------------------------------------------------------------

    private fun eraseContainer(crop: Raster, cropRect: IntRect, region: TextRegion): EraseResult {
        val gray = crop.toGray()
        val local = region.bbox.offset(-cropRect.left, -cropRect.top).clamp(crop.width, crop.height)
        val reached = GlyphMask.empty(0, 0, crop.width, crop.height)

        // Seeds come from a ring just outside the text box and from a grid inside it (the gaps between glyphs are
        // background too). The ring alone fails when the box is as large as the balloon: it lands on the outline
        // or beyond it, the flood fills the wrong side, and the glyphs are never holes. The background level is
        // the median of the bright seed candidates, not the classifier's ring average, which the outline tints.
        val candidates = ringSeeds(local, crop.width, crop.height) + gridSeeds(local)
        val fgLum = Raster.lum(region.fgColor)
        val split = (fgLum + Raster.lum(region.bgColor)) / 2
        val bright = candidates.map { (x, y) -> gray[x, y] }.filter { if (fgLum < split) it > split else it < split }.sorted()
        val bgLum = if (bright.isEmpty()) Raster.lum(region.bgColor) else bright[bright.size / 2]
        for ((sx, sy) in candidates) {
            if (reached[sx, sy]) continue
            if (abs(gray[sx, sy] - bgLum) > floodTolerance) continue
            or(reached, FloodFill.fromPoint(gray, sx, sy, floodTolerance))
        }
        if (reached.count() == 0) return eraseFree(crop, cropRect, region)

        // Complement components that touch the crop border are outside the container; the rest are glyph holes.
        val notReached = invert(reached)
        val borderTouching = GlyphMask.empty(0, 0, crop.width, crop.height)
        val notReachedGray = maskToGray(notReached)
        for (x in 0 until crop.width) for (y in intArrayOf(0, crop.height - 1)) {
            if (notReached[x, y] && !borderTouching[x, y]) or(borderTouching, FloodFill.fromPoint(notReachedGray, x, y, 0))
        }
        for (y in 0 until crop.height) for (x in intArrayOf(0, crop.width - 1)) {
            if (notReached[x, y] && !borderTouching[x, y]) or(borderTouching, FloodFill.fromPoint(notReachedGray, x, y, 0))
        }
        val holes = Morphology.ring(notReached, borderTouching)
        // Anti-aliased glyph edges sit inside tolerance; a dilation proportional to the glyph size catches them
        // (1 px at ~12 px screen text, 3 px on a page scan) without eating the border, which stays protected below.
        val fillMask = Morphology.dilate(holes, (region.fontSizePx / 12f).roundToInt().coerceIn(1, 3))

        // The fill must actually take the lettering out. Ink inside the text box that is neither a hole nor
        // border-touching means the flood found the wrong side of the outline; drawing a translation over intact
        // glyphs is the worst outcome, so hand over to the free-text path (and ultimately the mask).
        var ink = 0; var inkCovered = 0
        for (y in local.top until local.bottom) for (x in local.left until local.right) {
            if (abs(gray[x, y] - bgLum) <= 2 * floodTolerance) continue
            ink++
            if (fillMask[x, y]) inkCovered++
        }
        if (ink > 0 && inkCovered < ink * MIN_INK_COVERED) return eraseFree(crop, cropRect, region)

        val out = crop.copy()
        // Paint with the flooded interior's own colour near the text: the ring average behind bgColor picks up
        // outline and hatching and would leave a visibly tinted rectangle once the whole text box is filled.
        val nearBox = local.inflate(max(4, region.fontSizePx.roundToInt()))
        val bg = meanColor(crop, reached, nearBox).takeIf { reached.count() >= 16 } ?: region.bgColor
        var filled = 0
        // The text bbox lies inside the balloon, so its near-background pixels (thin anti-aliased strokes the flood
        // swallowed) are lettering too and go to background; anything dark that is not a hole is treated as a
        // balloon outline crossing the box and kept.
        val interior = local.inflate(-1)
        val fillLum = Raster.lum(bg)
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            if (borderTouching[x, y]) continue
            val edge = interior.contains(x, y) && abs(gray[x, y] - fillLum) <= 2 * floodTolerance
            if (fillMask[x, y] || edge) { out.set(x, y, bg); filled++ }
        }
        if (filled == 0) return eraseFree(crop, cropRect, region)
        return EraseResult(out, true, holes, EraseResult.Method.CONTAINER_FILL)
    }

    /** Background samples between the glyphs: a coarse grid over the text box. */
    private fun gridSeeds(local: IntRect): List<Pair<Int, Int>> {
        if (local.width < 4 || local.height < 4) return emptyList()
        val step = max(4, min(local.width, local.height) / 8)
        val out = ArrayList<Pair<Int, Int>>()
        var y = local.top + step / 2
        while (y < local.bottom) {
            var x = local.left + step / 2
            while (x < local.right) { out += x to y; x += step }
            y += step
        }
        return out
    }

    private fun ringSeeds(local: IntRect, w: Int, h: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val step = max(4, min(local.width, local.height) / 6)
        for (d in intArrayOf(3, 6)) {
            val r = local.inflate(d)
            var x = r.left
            while (x <= r.right) { out += x to r.top; out += x to r.bottom; x += step }
            var y = r.top
            while (y <= r.bottom) { out += r.left to y; out += r.right to y; y += step }
        }
        return out.filter { (x, y) -> x in 0 until w && y in 0 until h }
    }

    // ---- free text --------------------------------------------------------------------------

    private fun eraseFree(crop: Raster, cropRect: IntRect, region: TextRegion): EraseResult {
        val gray = crop.toGray()
        val local = region.bbox.offset(-cropRect.left, -cropRect.top).clamp(crop.width, crop.height)
        val glyph = glyphMask(gray, local, region) ?: return EraseResult(crop, false, null, EraseResult.Method.NONE)
        val glyphDil = Morphology.dilate(glyph, glyphDilate)

        // Halo test: the artist often gives free text a flat bright surround. Fill it and we're done.
        val halo = Morphology.dilate(glyphDil, haloDilate)
        val ring = Morphology.ring(halo, glyphDil)
        val ringStats = maskStats(gray, ring)
        if (ringStats.count > 12 && ringStats.stdDev <= haloStdDevMax && ringStats.mean >= haloMinLum) {
            val color = meanColor(crop, ring)
            val out = crop.copy()
            for (y in 0 until crop.height) for (x in 0 until crop.width) if (glyphDil[x, y]) out.set(x, y, color)
            return EraseResult(out, true, glyph, EraseResult.Method.HALO_FILL)
        }

        // Inpaint only the glyph pixels: 8 rays, nearest unmasked value each, weighted median.
        val out = crop.copy()
        RayInpainter.inpaint(out, glyphDil)

        // Gate: compare gradient-magnitude statistics inside the patch against the surrounding ring.
        // Ray inpainting fails two ways: it introduces streak edges (variance jumps) or it flattens
        // texture the surround still has (mean drops). Either means the page would look worse.
        val inside = gradientStats(out.toGray(), glyphDil)
        val around = gradientStats(gray, ring)
        val varRatio = inside.variance / maxOf(1f, around.variance)
        val meanRatio = inside.mean / maxOf(1f, around.mean)
        val smeared = varRatio > SMEAR_RATIO_MAX || (around.mean > TEXTURED_MEAN && (meanRatio < SMEAR_RATIO_MIN || varRatio < SMEAR_VAR_LOSS))
        return if (smeared) EraseResult(crop, false, glyph, EraseResult.Method.REJECTED_SMEAR)
        else EraseResult(out, true, glyph, EraseResult.Method.INPAINT)
    }

    /** Otsu on the bbox crop; the "dark" (or light, on dark art) class is the ink. */
    private fun glyphMask(gray: Gray, local: IntRect, region: TextRegion): GlyphMask? {
        if (local.isEmpty) return null
        val sub = ByteArray(local.width * local.height)
        for (y in 0 until local.height) System.arraycopy(gray.px, (local.top + y) * gray.width + local.left, sub, y * local.width, local.width)
        val g = Gray(local.width, local.height, sub)
        val thr = g.otsuThreshold()
        val darkText = Raster.lum(region.fgColor) < Raster.lum(region.bgColor)
        val inner = g.threshold(thr, darkText)
        val full = GlyphMask.empty(0, 0, gray.width, gray.height)
        var n = 0
        for (y in 0 until local.height) for (x in 0 until local.width) if (inner[x, y]) { full.set(local.left + x, local.top + y, true); n++ }
        val frac = n.toFloat() / (local.width * local.height)
        // Nearly empty or nearly solid masks mean thresholding found no lettering.
        return if (n < 4 || frac > 0.85f) null else full
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun or(dst: GlyphMask, src: GlyphMask) {
        for (i in dst.bits.indices) dst.bits[i] = (dst.bits[i].toInt() or src.bits[i].toInt()).toByte()
    }

    private fun invert(m: GlyphMask): GlyphMask {
        val out = GlyphMask.empty(m.left, m.top, m.width, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) if (!m[x, y]) out.set(x, y, true)
        return out
    }

    private fun maskToGray(m: GlyphMask): Gray {
        val g = Gray(m.width, m.height, ByteArray(m.width * m.height))
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) g.set(x, y, 255)
        return g
    }

    private fun maskStats(gray: Gray, mask: GlyphMask): Stats {
        var n = 0; var s = 0.0; var s2 = 0.0
        for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask[x, y]) {
            val v = gray[x, y].toDouble(); n++; s += v; s2 += v * v
        }
        if (n == 0) return Stats(0f, 0f, 0)
        val mean = s / n
        return Stats(mean.toFloat(), max(0.0, s2 / n - mean * mean).toFloat(), n)
    }

    private fun meanColor(crop: Raster, mask: GlyphMask, within: IntRect? = null): Int {
        var n = 0; var r = 0L; var g = 0L; var b = 0L
        val ys = if (within == null) 0 until mask.height else max(0, within.top) until min(mask.height, within.bottom)
        val xs = if (within == null) 0 until mask.width else max(0, within.left) until min(mask.width, within.right)
        for (y in ys) for (x in xs) if (mask[x, y]) {
            val c = crop[x, y]; n++; r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        if (n == 0 && within != null) return meanColor(crop, mask)
        return if (n == 0) 0xFFFFFFFF.toInt() else Raster.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun gradientStats(gray: Gray, mask: GlyphMask): Stats = maskStats(gray.sobelMagnitude(), mask)

    companion object {
        /** Share of the ink inside the text box the container fill must cover to count as an erase. */
        const val MIN_INK_COVERED = 0.6f
        const val SMEAR_RATIO_MAX = 2.5f
        const val SMEAR_RATIO_MIN = 0.6f
        const val SMEAR_VAR_LOSS = 0.5f
        /** Mean Sobel magnitude above which the surround counts as textured art rather than flat colour. */
        const val TEXTURED_MEAN = 20f
    }
}

/** For each masked pixel cast 8 rays, take the nearest unmasked value along each, weighted median. */
object RayInpainter {
    private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

    fun inpaint(raster: Raster, mask: GlyphMask, maxRay: Int = 64) {
        val w = raster.width; val h = raster.height
        val src = raster.argb.copyOf()
        val vals = IntArray(8); val weights = FloatArray(8)
        for (y in 0 until h) for (x in 0 until w) {
            if (!mask[x, y]) continue
            var n = 0
            for (d in 0 until 8) {
                var cx = x; var cy = y; var dist = 0
                while (dist < maxRay) {
                    cx += DX[d]; cy += DY[d]; dist++
                    if (cx < 0 || cy < 0 || cx >= w || cy >= h) { dist = -1; break }
                    if (!mask[cx, cy]) break
                }
                if (dist <= 0 || dist >= maxRay) continue
                vals[n] = src[cy * w + cx]; weights[n] = 1f / dist; n++
            }
            if (n == 0) continue
            raster.argb[y * w + x] = weightedMedian(vals, weights, n)
        }
    }

    private fun weightedMedian(vals: IntArray, weights: FloatArray, n: Int): Int {
        // Per-channel weighted median keeps colours plausible without cross-channel bleeding.
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val idx = (0 until n).sortedBy { (vals[it] shr shift) and 0xFF }
            var total = 0f
            for (i in 0 until n) total += weights[i]
            var acc = 0f; var pick = idx.last()
            for (i in idx) { acc += weights[i]; if (acc >= total / 2f) { pick = i; break } }
            out = out or (((vals[pick] shr shift) and 0xFF) shl shift)
        }
        return out
    }
}
