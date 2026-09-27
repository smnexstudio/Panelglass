package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextNorm
import com.smnexstudio.panelglass.core.model.TextRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Groups recognised lines into regions by proximity, alignment and font-size similarity.
 *
 * Vertical Japanese arrives from OCR as many one-character-wide columns; merging adjacent columns
 * right-to-left into one string is the largest single quality lever in the pipeline.
 */
class RegionClusterer(
    private val sizeRatioMax: Float = 1.6f,
    private val verticalGapFactor: Float = 1.1f,
    private val horizontalGapFactor: Float = 0.9f,
    private val minOverlap: Float = 0.2f,
) {
    fun cluster(lines: List<TextLine>, lang: Lang): List<TextRegion> {
        val input = distinct(lines.filter { it.text.isNotBlank() && !it.bbox.isEmpty })
        if (input.isEmpty()) return emptyList()
        val parent = IntArray(input.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }

        for (i in input.indices) for (j in i + 1 until input.size) {
            if (shouldMerge(input[i], input[j])) union(i, j)
        }
        val groups = input.indices.groupBy { find(it) }.values
        val regions = groups.map { idx -> build(idx.map { input[it] }, lang) }
        return ReadingOrder.sort(regions, lang.rtlReading)
    }

    internal fun shouldMerge(a: TextLine, b: TextLine): Boolean {
        if (a.vertical != b.vertical) return false
        val ra = a.bbox; val rb = b.bbox
        return if (a.vertical) {
            val wa = ra.width.toFloat(); val wb = rb.width.toFloat()
            if (max(wa, wb) / max(1f, min(wa, wb)) > sizeRatioMax) return false
            val avgW = (wa + wb) / 2f
            val gap = max(ra.left, rb.left) - min(ra.right, rb.right)
            if (gap > verticalGapFactor * avgW) return false
            val overlap = min(ra.bottom, rb.bottom) - max(ra.top, rb.top)
            overlap >= minOverlap * min(ra.height, rb.height) || abs(ra.top - rb.top) <= avgW
        } else {
            val ha = ra.height.toFloat(); val hb = rb.height.toFloat()
            if (max(ha, hb) / max(1f, min(ha, hb)) > sizeRatioMax) return false
            val avgH = (ha + hb) / 2f
            val gap = max(ra.top, rb.top) - min(ra.bottom, rb.bottom)
            if (gap > horizontalGapFactor * avgH) return false
            val overlap = min(ra.right, rb.right) - max(ra.left, rb.left)
            val centered = abs(ra.centerX - rb.centerX) <= 0.6f * max(ra.width, rb.width)
            overlap >= minOverlap * min(ra.width, rb.width) || centered
        }
    }

    /** One region from lines already known to belong together: columns right-to-left, rows top-to-bottom. */
    fun build(lines: List<TextLine>, lang: Lang): TextRegion {
        val group = distinct(lines)
        val vertical = group.count { it.vertical } * 2 >= group.size
        val ordered = if (vertical) group.sortedByDescending { it.bbox.right } // columns read right-to-left
        else group.sortedWith(compareBy({ it.bbox.top }, { it.bbox.left }))
        // Korean separates words with spaces, so its line breaks are word breaks; Japanese and Chinese lines join directly.
        val text = joinText(ordered.map { it.text }, (vertical || lang.isCjk) && lang != Lang.KO)
        val bbox = ordered.map { it.bbox }.reduce(IntRect::union)
        val angle = ordered.map { it.angle }.average().toFloat()
        return TextRegion(bbox = bbox, kind = RegionKind.FREE, angle = angle, vertical = vertical, text = text, lines = ordered)
    }

    /**
     * One copy of each line. Two recognition passes over the same text (overlapping detector boxes, e.g. a caption
     * boxed as both bubble text and free text) return it twice with the same box, and the region then read
     * "예로부터 중원은예로부터 중원은…".
     */
    internal fun distinct(lines: List<TextLine>): List<TextLine> {
        val out = ArrayList<TextLine>(lines.size)
        for (l in lines) if (out.none { it.text.trim() == l.text.trim() && iou(it.bbox, l.bbox) >= SAME_LINE_IOU }) out += l
        return out
    }

    private fun iou(a: IntRect, b: IntRect): Float {
        val w = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0)
        val h = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0)
        val inter = w.toLong() * h
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    private companion object { const val SAME_LINE_IOU = 0.8f }

    private fun joinText(parts: List<String>, cjk: Boolean): String {
        val sb = StringBuilder()
        for (p in parts) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val prev = sb.last(); val next = t.first()
                val noSpace = cjk && (TextNorm.isCjk(prev) || TextNorm.isCjk(next))
                if (!noSpace) sb.append(' ')
            }
            sb.append(t)
        }
        return sb.toString()
    }
}

/** Orders regions the way a reader's eye moves: rows top-to-bottom, then right-to-left (JA) or left-to-right. */
object ReadingOrder {
    fun sort(regions: List<TextRegion>, rtl: Boolean): List<TextRegion> {
        if (regions.size <= 1) return regions
        val sorted = regions.sortedBy { it.bbox.top }
        val medianH = sorted.map { it.bbox.height }.sorted()[sorted.size / 2].coerceAtLeast(1)
        val rows = ArrayList<MutableList<TextRegion>>()
        for (r in sorted) {
            val row = rows.lastOrNull()
            if (row != null && r.bbox.top - row.first().bbox.top <= medianH * 0.75f) row += r else rows += mutableListOf(r)
        }
        return rows.flatMap { row -> if (rtl) row.sortedByDescending { it.bbox.right } else row.sortedBy { it.bbox.left } }
    }
}
