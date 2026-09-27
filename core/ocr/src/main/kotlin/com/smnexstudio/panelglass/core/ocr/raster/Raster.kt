package com.smnexstudio.panelglass.core.ocr.raster

import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.model.IntRect
import kotlin.math.abs
import kotlin.math.sqrt

/** ARGB pixels for a crop. Pure Kotlin so the image-analysis stages run under plain JUnit. */
class Raster(val width: Int, val height: Int, val argb: IntArray) {
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]
    fun set(x: Int, y: Int, v: Int) { argb[y * width + x] = v }

    fun luminance(x: Int, y: Int): Int = lum(argb[y * width + x])

    fun toGray(): Gray {
        val out = ByteArray(width * height)
        for (i in argb.indices) out[i] = lum(argb[i]).toByte()
        return Gray(width, height, out)
    }

    fun copy() = Raster(width, height, argb.copyOf())

    companion object {
        fun lum(c: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            return (r * 77 + g * 151 + b * 28) shr 8
        }
        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        fun solid(width: Int, height: Int, color: Int) = Raster(width, height, IntArray(width * height) { color })
    }
}

/** Provides pixel crops on demand so we never materialise a full-page IntArray. */
interface PixelSource {
    val width: Int
    val height: Int
    /** [rect] is clamped to the image; the returned raster is the clamped size. */
    fun crop(rect: IntRect): Raster
}

class RasterPixelSource(private val raster: Raster) : PixelSource {
    override val width get() = raster.width
    override val height get() = raster.height
    override fun crop(rect: IntRect): Raster {
        val r = rect.clamp(width, height)
        val out = IntArray(r.width * r.height)
        for (y in 0 until r.height) System.arraycopy(raster.argb, (r.top + y) * width + r.left, out, y * r.width, r.width)
        return Raster(r.width, r.height, out)
    }
}

/** 8-bit luminance image. */
class Gray(val width: Int, val height: Int, val px: ByteArray) {
    operator fun get(x: Int, y: Int): Int = px[y * width + x].toInt() and 0xFF
    fun set(x: Int, y: Int, v: Int) { px[y * width + x] = v.toByte() }

    fun downsample(factor: Int): Gray {
        if (factor <= 1) return this
        val w = maxOf(1, width / factor); val h = maxOf(1, height / factor)
        val out = ByteArray(w * h)
        val n = factor * factor
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            val bx = x * factor; val by = y * factor
            for (dy in 0 until factor) for (dx in 0 until factor) s += this[bx + dx, by + dy]
            out[y * w + x] = (s / n).toByte()
        }
        return Gray(w, h, out)
    }

    /** Sobel gradient magnitude, clipped to 0..255. */
    fun sobelMagnitude(): Gray {
        val out = ByteArray(width * height)
        if (width < 3 || height < 3) return Gray(width, height, out)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val gx = -this[x - 1, y - 1] - 2 * this[x - 1, y] - this[x - 1, y + 1] +
                this[x + 1, y - 1] + 2 * this[x + 1, y] + this[x + 1, y + 1]
            val gy = -this[x - 1, y - 1] - 2 * this[x, y - 1] - this[x + 1, y - 1] +
                this[x - 1, y + 1] + 2 * this[x, y + 1] + this[x + 1, y + 1]
            val m = (abs(gx) + abs(gy)) / 4
            out[y * width + x] = minOf(255, m).toByte()
        }
        return Gray(width, height, out)
    }

    /** Otsu's threshold over the histogram; returns the value below which pixels are "dark". */
    fun otsuThreshold(): Int {
        val hist = IntArray(256)
        for (b in px) hist[b.toInt() and 0xFF]++
        val total = px.size
        var sum = 0L
        for (i in 0 until 256) sum += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0L; var best = 0.0; var thr = 127
        for (t in 0 until 256) {
            wB += hist[t]; if (wB == 0L) continue
            val wF = total - wB; if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB; val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = t }
        }
        return thr
    }

    /** Binary mask of pixels darker (or lighter, when [darkText] is false) than [threshold]. */
    fun threshold(threshold: Int, darkText: Boolean, left: Int = 0, top: Int = 0): GlyphMask {
        val m = GlyphMask.empty(left, top, width, height)
        for (y in 0 until height) for (x in 0 until width) {
            val v = this[x, y]
            if (if (darkText) v <= threshold else v > threshold) m.set(x, y, true)
        }
        return m
    }
}

class Stats(val mean: Float, val variance: Float, val count: Int) {
    val stdDev: Float get() = sqrt(variance)

    companion object {
        inline fun of(n: Int, value: (Int) -> Int): Stats {
            if (n == 0) return Stats(0f, 0f, 0)
            var s = 0.0; var s2 = 0.0
            for (i in 0 until n) { val v = value(i).toDouble(); s += v; s2 += v * v }
            val mean = s / n
            return Stats(mean.toFloat(), maxOf(0.0, s2 / n - mean * mean).toFloat(), n)
        }
    }
}

object Morphology {
    fun dilate(mask: GlyphMask, radius: Int): GlyphMask {
        if (radius <= 0) return mask
        val w = mask.width; val h = mask.height
        val out = GlyphMask.empty(mask.left, mask.top, w, h)
        // Separable square dilation: horizontal then vertical.
        val tmp = GlyphMask.empty(0, 0, w, h)
        for (y in 0 until h) {
            var run = -1
            for (x in 0 until w) {
                if (mask[x, y]) run = x
                if (run >= 0 && x - run <= radius) tmp.set(x, y, true)
            }
            run = -1
            for (x in w - 1 downTo 0) {
                if (mask[x, y]) run = x
                if (run >= 0 && run - x <= radius) tmp.set(x, y, true)
            }
        }
        for (x in 0 until w) {
            var run = -1
            for (y in 0 until h) {
                if (tmp[x, y]) run = y
                if (run >= 0 && y - run <= radius) out.set(x, y, true)
            }
            run = -1
            for (y in h - 1 downTo 0) {
                if (tmp[x, y]) run = y
                if (run >= 0 && run - y <= radius) out.set(x, y, true)
            }
        }
        return out
    }

    /** Pixels in [outer] that are not in [inner]; both must share geometry. */
    fun ring(outer: GlyphMask, inner: GlyphMask): GlyphMask {
        val out = GlyphMask.empty(outer.left, outer.top, outer.width, outer.height)
        for (y in 0 until outer.height) for (x in 0 until outer.width) if (outer[x, y] && !inner[x, y]) out.set(x, y, true)
        return out
    }

    /** Keeps only the connected components touching at least one mask pixel of [seed]'s bounding centre region; used to drop border noise. */
    fun bounds(mask: GlyphMask): IntRect? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask[x, y]) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else IntRect(mask.left + l, mask.top + t, mask.left + r + 1, mask.top + b + 1)
    }
}

object FloodFill {
    /**
     * Flood-fills from every border pixel of [gray] through pixels whose luminance is within
     * [tolerance] of the border seed, returning the mask of *reached* pixels. For a speech
     * bubble crop that started slightly outside the bubble, the reached set is the outside plus
     * the bubble interior only if the border touches it — so callers seed from inside instead.
     */
    fun fromPoint(gray: Gray, sx: Int, sy: Int, tolerance: Int): GlyphMask {
        val w = gray.width; val h = gray.height
        val mask = GlyphMask.empty(0, 0, w, h)
        if (sx !in 0 until w || sy !in 0 until h) return mask
        val seed = gray[sx, sy]
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = sy * w + sx
        mask.set(sx, sy, true)
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            // 4-connected scan.
            if (x > 0) push(gray, mask, x - 1, y, seed, tolerance, stack, sp).also { sp = it }
            if (x < w - 1) push(gray, mask, x + 1, y, seed, tolerance, stack, sp).also { sp = it }
            if (y > 0) push(gray, mask, x, y - 1, seed, tolerance, stack, sp).also { sp = it }
            if (y < h - 1) push(gray, mask, x, y + 1, seed, tolerance, stack, sp).also { sp = it }
        }
        return mask
    }

    private fun push(gray: Gray, mask: GlyphMask, x: Int, y: Int, seed: Int, tol: Int, stack: IntArray, sp: Int): Int {
        if (mask[x, y]) return sp
        if (abs(gray[x, y] - seed) > tol) return sp
        mask.set(x, y, true)
        stack[sp] = y * gray.width + x
        return sp + 1
    }
}
