package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.ocr.TextBox

/**
 * Detection over a tall page (a manhwa strip) in overlapping tiles. Scaled whole to the detector's ≤ 1600 px, a
 * 800 × 20000 strip would shrink 12× and its text become unreadable; each tile is at most [TILE_ASPECT] × the width
 * tall, so it is barely scaled at all. What a tile finds is mapped back to page pixels, and the copies the overlaps
 * produce are merged into one. Pure geometry, so it is tested on the JVM.
 */
object TileDetection {
    /** A page taller than this many widths is detected in tiles. */
    const val TALL_ASPECT = 2f
    /** Tile height in widths. */
    const val TILE_ASPECT = 1.5f
    /** Share of a tile shared with the next one (the plan's 15% lost bubbles taller than the overlap; 30% keeps most). */
    const val OVERLAP = 0.3f
    /** A box this close to a tile's inner edge (one another tile covers) is taken as cut there. */
    private const val EDGE = 3

    fun isTall(width: Int, height: Int) = height > width * TALL_ASPECT

    /** Tiles top to bottom, full width, each overlapping the next; the last one ends at the page's bottom. */
    fun tiles(width: Int, height: Int): List<IntRect> {
        val tileH = (width * TILE_ASPECT).toInt().coerceAtLeast(1)
        if (height <= tileH) return listOf(IntRect(0, 0, width, height))
        val step = (tileH * (1 - OVERLAP)).toInt().coerceAtLeast(1)
        val out = ArrayList<IntRect>()
        var top = 0
        while (true) {
            if (top + tileH >= height) {
                out += IntRect(0, (height - tileH).coerceAtLeast(0), width, height)
                break
            }
            out += IntRect(0, top, width, top + tileH)
            top += step
        }
        return out
    }

    /** One tile's findings, in page pixels, and the tile they came from. */
    class TileResult(val tile: IntRect, val boxes: List<TextBox>, val lines: List<TextLine>)

    /**
     * True when [r] touches an edge of [tile] that is inside the page: the part beyond it is in the neighbouring tile,
     * so what this tile saw is only a piece.
     */
    fun cutByTile(r: IntRect, tile: IntRect, pageHeight: Int): Boolean =
        (tile.top > 0 && r.top <= tile.top + EDGE) || (tile.bottom < pageHeight && r.bottom >= tile.bottom - EDGE)

    /**
     * Boxes from every tile, the overlaps' copies merged: boxes of one kind that overlap belong together; a copy a tile
     * saw whole wins (the largest one, if several), and when every copy was cut the box is their union.
     */
    fun mergeBoxes(results: List<TileResult>, pageHeight: Int): List<TextBox> {
        val all = results.flatMap { t -> t.boxes.map { it to cutByTile(it.bbox, t.tile, pageHeight) } }
        val groups = group(all.map { it.first.bbox }) { a, b -> all[a].first.kind == all[b].first.kind }
        return groups.map { members ->
            val whole = members.filter { !all[it].second }
            if (whole.isNotEmpty()) all[whole.maxBy { all[it].first.bbox.area }].first
            else {
                val first = all[members.first()].first
                first.copy(
                    bbox = members.map { all[it].first.bbox }.reduce(IntRect::union),
                    confidence = members.maxOf { all[it].first.confidence },
                )
            }
        }
    }

    /**
     * Lines from every tile. A line cut by a tile edge is dropped (the neighbouring tile reads it whole), unless no
     * whole copy exists; the copies of one line are then merged, keeping the longest reading.
     */
    fun mergeLines(results: List<TileResult>, pageHeight: Int): List<TextLine> {
        val all = results.flatMap { t -> t.lines.map { it to cutByTile(it.bbox, t.tile, pageHeight) } }
        val groups = group(all.map { it.first.bbox }) { _, _ -> true }
        return groups.map { members ->
            val whole = members.filter { !all[it].second }
            val pick = (whole.ifEmpty { members }).maxWith(compareBy({ all[it].first.text.length }, { all[it].first.bbox.area }))
            all[pick].first
        }
    }

    /** Two rects are copies of one thing when they overlap by half of the smaller one. */
    fun sameThing(a: IntRect, b: IntRect): Boolean {
        val i = a.intersect(b)?.area ?: return false
        return i >= minOf(a.area, b.area) * 0.5f
    }

    /** Connected groups of indices under [sameThing] (and [compatible]), in first-seen order. */
    private fun group(rects: List<IntRect>, compatible: (Int, Int) -> Boolean): List<List<Int>> {
        val parent = IntArray(rects.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in rects.indices) for (j in i + 1 until rects.size) {
            if (compatible(i, j) && sameThing(rects[i], rects[j])) parent[find(j)] = find(i)
        }
        return rects.indices.groupBy { find(it) }.values.toList()
    }
}
