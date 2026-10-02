package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.model.Pt
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A float rectangle (page or screen pixels), pure Kotlin so the editor's geometry is tested on the JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = max(0f, width) * max(0f, height)

    fun inflate(d: Float) = Box(left - d, top - d, right + d, bottom + d)
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun intersects(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun intersect(o: Box): Box? = Box(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom)).takeIf { it.width > 0 && it.height > 0 }

    companion object {
        fun of(points: List<Pt>): Box = Box(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        fun normalized(x0: Float, y0: Float, x1: Float, y1: Float) = Box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

object PolygonHit {
    /** Even-odd ray casting: true when (x, y) is inside [polygon] (any simple polygon, concave ones too). */
    fun contains(polygon: List<Pt>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y)) {
                val xCross = (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
                if (x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Distance from (x, y) to the polygon's outline. */
    fun distanceToEdge(polygon: List<Pt>, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            best = min(best, segmentDistance(x, y, a.x, a.y, b.x, b.y))
        }
        return best
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * The polygon a tap at (x, y) picks, as an index into [polygons]: the smallest one containing the point (a text box
     * inside a balloon wins over the balloon), else the nearest outline within [slop] (a tap just off a thin shape).
     */
    fun pick(polygons: List<List<Pt>>, x: Float, y: Float, slop: Float): Int? {
        val inside = polygons.indices.filter { contains(polygons[it], x, y) }
        if (inside.isNotEmpty()) return inside.minBy { Box.of(polygons[it]).area }
        return polygons.indices
            .map { it to distanceToEdge(polygons[it], x, y) }
            .filter { abs(it.second) <= slop }
            .minByOrNull { it.second }?.first
    }
}
