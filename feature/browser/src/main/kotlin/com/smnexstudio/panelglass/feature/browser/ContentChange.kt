package com.smnexstudio.panelglass.feature.browser

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.IntRect
import kotlin.math.abs

/**
 * A few thousand luminance cells of the viewport: enough to tell that a paged reader turned its page (the whole
 * picture changes) from the same page with our patches over it (only the patched cells change, and those are masked).
 */
class Thumb(val luma: IntArray, val gw: Int, val gh: Int, val viewW: Int, val viewH: Int) {
    companion object {
        const val WIDTH = 48

        fun of(bitmap: Bitmap): Thumb {
            val gw = WIDTH; val gh = (WIDTH * bitmap.height / bitmap.width.coerceAtLeast(1)).coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bitmap, gw, gh, true)
            val px = IntArray(gw * gh).also { small.getPixels(it, 0, gw, 0, 0, gw, gh) }
            if (small !== bitmap) small.recycle()
            return Thumb(IntArray(px.size) { luma(px[it]) }, gw, gh, bitmap.width, bitmap.height)
        }

        private fun luma(c: Int) = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    }
}

/**
 * Whether the viewport shows other content than [before]: the mean luminance difference over the cells no patch
 * covers ([patched], view pixels) is above [threshold]. Null when the two cannot be compared or everything is patched.
 */
internal fun contentChanged(before: Thumb, now: Thumb, patched: List<IntRect>, threshold: Int = CHANGE_THRESHOLD): Boolean? {
    if (before.gw != now.gw || before.gh != now.gh) return null
    val sx = before.viewW.toFloat() / before.gw; val sy = before.viewH.toFloat() / before.gh
    var sum = 0L; var n = 0
    for (y in 0 until before.gh) for (x in 0 until before.gw) {
        val cell = IntRect((x * sx).toInt(), (y * sy).toInt(), ((x + 1) * sx).toInt(), ((y + 1) * sy).toInt())
        if (patched.any { it.intersects(cell) }) continue
        val i = y * before.gw + x
        sum += abs(before.luma[i] - now.luma[i]); n++
    }
    if (n < before.gw * before.gh / 10) return null
    return sum / n > threshold
}

/** Mean per-cell luminance change (0-255) that counts as another page; video-like noise and a fading control stay below it. */
internal const val CHANGE_THRESHOLD = 18
