package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds where free text should go when there is no container: grow a rectangle from the original
 * centroid toward the lowest-energy direction on a 1/8 Sobel map, capped at one text-height of drift.
 * Panel-edge clipping is approximated by the image bounds; panel edges are not used here.
 */
object FreeTextPlacer {
    /**
     * @param energy Sobel magnitude at [scale] (1/8 of the page).
     * @param bbox original region bbox in page pixels.
     * @param textHeight one line of text in page pixels; drift is capped to this.
     */
    fun place(energy: Gray, scale: Int, bbox: IntRect, textHeight: Int, pageW: Int, pageH: Int): IntRect {
        val cap = max(1, textHeight)
        val origin = energySum(energy, scale, bbox)
        var best = bbox; var bestE = origin
        val step = max(scale, cap / 4)
        for (dy in intArrayOf(-1, 0, 1)) for (dx in intArrayOf(-1, 0, 1)) {
            if (dx == 0 && dy == 0) continue
            var d = step
            while (d <= cap) {
                val cand = bbox.offset(dx * d, dy * d)
                if (cand.left < 0 || cand.top < 0 || cand.right > pageW || cand.bottom > pageH) break
                val e = energySum(energy, scale, cand)
                if (e < bestE) { bestE = e; best = cand }
                d += step
            }
        }
        // Only wander when it clearly helps; otherwise overlay in place.
        return if (bestE < origin * 0.8f) best else bbox
    }

    private fun energySum(energy: Gray, scale: Int, r: IntRect): Float {
        val l = (r.left / scale).coerceIn(0, energy.width - 1); val t = (r.top / scale).coerceIn(0, energy.height - 1)
        val rr = (r.right / scale).coerceIn(l + 1, energy.width); val b = (r.bottom / scale).coerceIn(t + 1, energy.height)
        var s = 0L
        for (y in t until b) for (x in l until rr) s += energy[x, y]
        return s.toFloat() / ((rr - l) * (b - t))
    }
}

/** Principal-axis geometry of a glyph mask: rotation, extent and thickness, plus a curvature estimate. */
class MaskGeometry(
    val cx: Float, val cy: Float,
    /** Degrees, canvas convention (clockwise positive, y down). */
    val angleDeg: Float,
    /** Full extent along the principal axis, in pixels. */
    val length: Float,
    /** Full extent across the principal axis, in pixels. */
    val thickness: Float,
    val eigenRatio: Float,
    /** Quadratic coefficient of the best-fit v = a·u² in the principal frame; non-zero means curved lettering. */
    val curvature: Float,
    val area: Int,
) {
    val isCurved: Boolean get() = eigenRatio < 3f && abs(curvature) * length > thickness * 0.5f

    companion object {
        fun of(mask: GlyphMask): MaskGeometry? {
            var n = 0; var sx = 0.0; var sy = 0.0
            for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask[x, y]) { n++; sx += x; sy += y }
            if (n < 8) return null
            val cx = sx / n; val cy = sy / n
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask[x, y]) {
                val dx = x - cx; val dy = y - cy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy
            }
            sxx /= n; syy /= n; sxy /= n
            val tr = sxx + syy; val det = sxx * syy - sxy * sxy
            val disc = sqrt(max(0.0, tr * tr / 4 - det))
            val l1 = tr / 2 + disc; val l2 = max(1e-6, tr / 2 - disc)
            val theta = if (abs(sxy) < 1e-9 && sxx >= syy) 0.0 else atan2(l1 - sxx, sxy)
            val ux = kotlin.math.cos(theta); val uy = kotlin.math.sin(theta)
            // Extent along / across the axis plus a least-squares quadratic across it.
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            var s2 = 0.0; var s4 = 0.0; var s2v = 0.0; var s0v = 0.0
            for (y in 0 until mask.height) for (x in 0 until mask.width) if (mask[x, y]) {
                val dx = x - cx; val dy = y - cy
                val u = dx * ux + dy * uy; val v = -dx * uy + dy * ux
                if (u < minU) minU = u; if (u > maxU) maxU = u; if (v < minV) minV = v; if (v > maxV) maxV = v
                s2 += u * u; s4 += u * u * u * u; s2v += u * u * v; s0v += v
            }
            // Fit v = a u² + c (b is ~0 by construction of the centroid).
            val denom = s4 * n - s2 * s2
            val a = if (abs(denom) < 1e-9) 0.0 else (s2v * n - s2 * s0v) / denom
            var deg = Math.toDegrees(theta).toFloat()
            if (deg > 90f) deg -= 180f; if (deg < -90f) deg += 180f
            return MaskGeometry(
                cx = (mask.left + cx).toFloat(), cy = (mask.top + cy).toFloat(), angleDeg = deg,
                length = (maxU - minU).toFloat(), thickness = (maxV - minV).toFloat(),
                eigenRatio = (l1 / l2).toFloat(), curvature = a.toFloat(), area = n,
            )
        }
    }
}
