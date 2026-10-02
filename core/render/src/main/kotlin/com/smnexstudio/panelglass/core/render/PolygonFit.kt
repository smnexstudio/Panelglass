package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.Pt
import kotlin.math.max
import kotlin.math.min

/** A float rectangle in page pixels. */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inset(d: Float) = FRect(left + d, top + d, right - d, bottom - d)
}

/**
 * Where text goes inside a bubble's shape (docs/STUDIO_PLAN.md › Styled rendering): the largest axis-aligned rectangle
 * with the shape's bounding-box proportions, centred on the shape, whose corners, edge midpoints and centre all lie
 * inside it. Binary search on the scale. Works for any simple polygon (a rectangle, an ellipse drawn as many points, a
 * concave reshape). Pure Kotlin, tested on the JVM (`PolygonFitTest`).
 */
object PolygonFit {
    fun bounds(polygon: List<Pt>): FRect =
        FRect(polygon.minOf { it.x }, polygon.minOf { it.y }, polygon.maxOf { it.x }, polygon.maxOf { it.y })

    /** Even-odd ray casting. */
    fun contains(polygon: List<Pt>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * The text rectangle for [polygon], shrunk by [insetShare] of its smaller side (text off the outline). For a
     * concave shape whose bounding-box centre falls outside it (an L), the centre moves to the inside point nearest to
     * it on a coarse grid. Null for a degenerate shape.
     */
    fun innerRect(polygon: List<Pt>, insetShare: Float = 0.04f): FRect? {
        if (polygon.size < 3) return null
        val b = bounds(polygon)
        if (b.width <= 1f || b.height <= 1f) return null
        val (cx, cy) = centre(polygon, b) ?: return null
        val hw = b.width / 2f
        val hh = b.height / 2f
        var lo = 0f
        var hi = 1f
        repeat(18) {
            val s = (lo + hi) / 2f
            if (fits(polygon, cx, cy, hw * s, hh * s)) lo = s else hi = s
        }
        if (lo <= 0f) return null
        val r = FRect(cx - hw * lo, cy - hh * lo, cx + hw * lo, cy + hh * lo)
        return r.inset(min(r.width, r.height) * insetShare)
    }

    private fun fits(p: List<Pt>, cx: Float, cy: Float, hw: Float, hh: Float): Boolean {
        val xs = floatArrayOf(cx - hw, cx, cx + hw)
        val ys = floatArrayOf(cy - hh, cy, cy + hh)
        for (x in xs) for (y in ys) if (!contains(p, x, y)) return false
        // Quarter points along each edge too: a notch between the corners and the midpoints would be missed.
        for (t in floatArrayOf(0.25f, 0.75f)) {
            val x = cx - hw + 2 * hw * t
            val y = cy - hh + 2 * hh * t
            if (!contains(p, x, cy - hh) || !contains(p, x, cy + hh) || !contains(p, cx - hw, y) || !contains(p, cx + hw, y)) return false
        }
        return true
    }

    private fun centre(p: List<Pt>, b: FRect): Pair<Float, Float>? {
        if (contains(p, b.centerX, b.centerY)) return b.centerX to b.centerY
        // The inside point that leaves the most room: the farthest from the outline on an 16 × 16 grid.
        var best: Pair<Float, Float>? = null
        var bestScore = -1f
        for (i in 1 until 16) for (j in 1 until 16) {
            val x = b.left + b.width * i / 16f
            val y = b.top + b.height * j / 16f
            if (!contains(p, x, y)) continue
            val score = distanceToEdge(p, x, y)
            if (score > bestScore) { bestScore = score; best = x to y }
        }
        return best
    }

    private fun distanceToEdge(p: List<Pt>, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (i in p.indices) {
            val a = p[i]
            val c = p[(i + 1) % p.size]
            val dx = c.x - a.x
            val dy = c.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0f, 1f)
            val ex = a.x + t * dx - x
            val ey = a.y + t * dy - y
            best = min(best, kotlin.math.sqrt(ex * ex + ey * ey))
        }
        return max(0f, best)
    }

    /** An ellipse inscribed in [r] as [n] points: the "Ellipse" reshape preset. */
    fun ellipse(r: FRect, n: Int = 24): List<Pt> = (0 until n).map { i ->
        val a = i * 2.0 * Math.PI / n
        Pt((r.centerX + r.width / 2f * kotlin.math.cos(a)).toFloat(), (r.centerY + r.height / 2f * kotlin.math.sin(a)).toFloat())
    }

    /** The "Rectangle" reshape preset. */
    fun rectangle(r: FRect): List<Pt> = listOf(Pt(r.left, r.top), Pt(r.right, r.top), Pt(r.right, r.bottom), Pt(r.left, r.bottom))
}
