package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Integer rectangle in image pixel space; right/bottom are exclusive. Pure Kotlin stand-in for android.graphics.Rect. */
@Serializable
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Int get() = max(0, width) * max(0, height)
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(o: IntRect) = IntRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun intersect(o: IntRect): IntRect? {
        val r = IntRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
        return if (r.isEmpty) null else r
    }
    fun intersects(o: IntRect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun inflate(dx: Int, dy: Int = dx) = IntRect(left - dx, top - dy, right + dx, bottom + dy)
    fun offset(dx: Int, dy: Int) = IntRect(left + dx, top + dy, right + dx, bottom + dy)
    fun clamp(w: Int, h: Int) = IntRect(left.coerceIn(0, w), top.coerceIn(0, h), right.coerceIn(0, w), bottom.coerceIn(0, h))
    fun scale(f: Float) = IntRect((left * f).toInt(), (top * f).toInt(), (right * f).roundToInt(), (bottom * f).roundToInt())
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom

    /** Fraction of this rect covered by [o]. */
    fun overlapFraction(o: IntRect): Float {
        val i = intersect(o) ?: return 0f
        return if (area == 0) 0f else i.area.toFloat() / area
    }

    companion object {
        val EMPTY = IntRect(0, 0, 0, 0)
    }
}

/**
 * A binary mask positioned inside an image. Bits are packed row-major, one bit per pixel,
 * relative to ([left],[top]). Pure Kotlin stand-in for an android.graphics.Path glyph mask.
 */
@Serializable
class GlyphMask(val left: Int, val top: Int, val width: Int, val height: Int, val bits: ByteArray) {
    val bounds: IntRect get() = IntRect(left, top, left + width, top + height)

    /** Local coordinates. */
    operator fun get(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= width || y >= height) return false
        val i = y * width + x
        return (bits[i ushr 3].toInt() shr (i and 7)) and 1 == 1
    }

    fun set(x: Int, y: Int, v: Boolean) {
        val i = y * width + x
        val b = i ushr 3
        val m = 1 shl (i and 7)
        bits[b] = if (v) (bits[b].toInt() or m).toByte() else (bits[b].toInt() and m.inv()).toByte()
    }

    fun count(): Int {
        var c = 0
        for (b in bits) c += Integer.bitCount(b.toInt() and 0xFF)
        return c
    }

    companion object {
        fun empty(left: Int, top: Int, width: Int, height: Int) =
            GlyphMask(left, top, width, height, ByteArray((width * height + 7) / 8))
    }
}
