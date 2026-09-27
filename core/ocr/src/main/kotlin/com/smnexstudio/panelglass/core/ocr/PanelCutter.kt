package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.ocr.raster.PixelSource

/**
 * Classical recursive X-Y cut on a downsampled binarised copy.
 *
 * Divides manga/comic pages into panels to establish reading order (essential for dialogue continuity)
 * and provide clip rectangles for free text placement.
 * Also detects horizontal gutters for webtoon strip tiling boundaries.
 */
class PanelCutter(
    private val scaleFactor: Int = 8,
    private val inkRatioThreshold: Float = 0.015f,
    private val minGutterLengthRatio: Float = 0.02f,
    private val maxCutDepth: Int = 4,
) {
    data class Panel(
        val bounds: IntRect,
        val index: Int = 0,
    )

    /**
     * Splits [pixels] into an ordered list of panels using recursive X-Y cut.
     */
    fun segment(pixels: PixelSource, rtl: Boolean): List<Panel> {
        val w = pixels.width
        val h = pixels.height
        if (w <= 0 || h <= 0) return emptyList()

        val fullRect = IntRect(0, 0, w, h)
        val fullCrop = pixels.crop(fullRect)
        val gray = fullCrop.toGray().downsample(scaleFactor)

        val thr = gray.otsuThreshold()
        val scaledPanels = recursiveCut(
            rect = IntRect(0, 0, gray.width, gray.height),
            gray = gray,
            thr = thr,
            depth = 0,
            horizontalFirst = true
        )

        val upscaled = scaledPanels.map { r ->
            IntRect(
                left = (r.left * scaleFactor).coerceIn(0, w),
                top = (r.top * scaleFactor).coerceIn(0, h),
                right = if (r.right >= gray.width) w else (r.right * scaleFactor).coerceIn(0, w),
                bottom = if (r.bottom >= gray.height) h else (r.bottom * scaleFactor).coerceIn(0, h)
            )
        }.filter { it.width > 20 && it.height > 20 }

        if (upscaled.isEmpty()) {
            return listOf(Panel(bounds = fullRect, index = 0))
        }

        val ordered = orderPanels(upscaled, rtl)
        return ordered.mapIndexed { idx, rect -> Panel(bounds = rect, index = idx) }
    }

    /**
     * Re-orders regions according to panel reading order, then reading order within each panel.
     */
    fun orderRegions(regions: List<TextRegion>, panels: List<Panel>, rtl: Boolean): List<TextRegion> {
        if (regions.size <= 1 || panels.isEmpty()) return regions

        val unassigned = mutableListOf<TextRegion>()
        val panelGroups = Array(panels.size) { mutableListOf<TextRegion>() }

        for (r in regions) {
            val cx = r.bbox.centerX
            val cy = r.bbox.centerY
            val pIdx = panels.indexOfFirst { it.bounds.contains(cx.toInt(), cy.toInt()) }
            if (pIdx >= 0) {
                panelGroups[pIdx].add(r)
            } else {
                val bestIdx = panels.indices.minByOrNull { i: Int ->
                    val pb = panels[i].bounds
                    val dx = if (cx < pb.left) pb.left - cx else if (cx > pb.right) cx - pb.right else 0f
                    val dy = if (cy < pb.top) pb.top - cy else if (cy > pb.bottom) cy - pb.bottom else 0f
                    dx * dx + dy * dy
                } ?: -1
                if (bestIdx >= 0) panelGroups[bestIdx].add(r) else unassigned.add(r)
            }
        }

        val result = mutableListOf<TextRegion>()
        for (i in panels.indices) {
            val group = panelGroups[i]
            if (group.isNotEmpty()) {
                result.addAll(ReadingOrder.sort(group, rtl))
            }
        }
        if (unassigned.isNotEmpty()) {
            result.addAll(ReadingOrder.sort(unassigned, rtl))
        }
        return result
    }

    private fun recursiveCut(
        rect: IntRect,
        gray: Gray,
        thr: Int,
        depth: Int,
        horizontalFirst: Boolean
    ): List<IntRect> {
        if (depth >= maxCutDepth || rect.width < 10 || rect.height < 10) {
            return listOf(rect)
        }

        val cut1 = if (horizontalFirst) findBestHorizontalCut(rect, gray, thr) else findBestVerticalCut(rect, gray, thr)
        if (cut1 != null) {
            val (r1, r2) = cut1
            return recursiveCut(r1, gray, thr, depth + 1, !horizontalFirst) +
                   recursiveCut(r2, gray, thr, depth + 1, !horizontalFirst)
        }

        val cut2 = if (horizontalFirst) findBestVerticalCut(rect, gray, thr) else findBestHorizontalCut(rect, gray, thr)
        if (cut2 != null) {
            val (r1, r2) = cut2
            return recursiveCut(r1, gray, thr, depth + 1, horizontalFirst) +
                   recursiveCut(r2, gray, thr, depth + 1, horizontalFirst)
        }

        return listOf(rect)
    }

    private fun findBestHorizontalCut(rect: IntRect, gray: Gray, thr: Int): Pair<IntRect, IntRect>? {
        val minLen = maxOf(2, (rect.height * minGutterLengthRatio).toInt())
        var bestStart = -1
        var bestLen = 0
        var curStart = -1

        val margin = maxOf(2, (rect.height * 0.05f).toInt())
        for (y in (rect.top + margin) until (rect.bottom - margin)) {
            var ink = 0
            for (x in rect.left until rect.right) {
                if (gray[x, y] < thr) ink++
            }
            val inkRatio = ink.toFloat() / rect.width
            if (inkRatio <= inkRatioThreshold) {
                if (curStart < 0) curStart = y
            } else {
                if (curStart >= 0) {
                    val len = y - curStart
                    if (len > bestLen) {
                        bestLen = len
                        bestStart = curStart
                    }
                    curStart = -1
                }
            }
        }
        if (curStart >= 0) {
            val len = (rect.bottom - margin) - curStart
            if (len > bestLen) {
                bestLen = len
                bestStart = curStart
            }
        }

        if (bestLen >= minLen && bestStart >= 0) {
            val splitY = bestStart + bestLen / 2
            val top = IntRect(rect.left, rect.top, rect.right, splitY)
            val bottom = IntRect(rect.left, splitY, rect.right, rect.bottom)
            return top to bottom
        }
        return null
    }

    private fun findBestVerticalCut(rect: IntRect, gray: Gray, thr: Int): Pair<IntRect, IntRect>? {
        val minLen = maxOf(2, (rect.width * minGutterLengthRatio).toInt())
        var bestStart = -1
        var bestLen = 0
        var curStart = -1

        val margin = maxOf(2, (rect.width * 0.05f).toInt())
        for (x in (rect.left + margin) until (rect.right - margin)) {
            var ink = 0
            for (y in rect.top until rect.bottom) {
                if (gray[x, y] < thr) ink++
            }
            val inkRatio = ink.toFloat() / rect.height
            if (inkRatio <= inkRatioThreshold) {
                if (curStart < 0) curStart = x
            } else {
                if (curStart >= 0) {
                    val len = x - curStart
                    if (len > bestLen) {
                        bestLen = len
                        bestStart = curStart
                    }
                    curStart = -1
                }
            }
        }
        if (curStart >= 0) {
            val len = (rect.right - margin) - curStart
            if (len > bestLen) {
                bestLen = len
                bestStart = curStart
            }
        }

        if (bestLen >= minLen && bestStart >= 0) {
            val splitX = bestStart + bestLen / 2
            val left = IntRect(rect.left, rect.top, splitX, rect.bottom)
            val right = IntRect(splitX, rect.top, rect.right, rect.bottom)
            return left to right
        }
        return null
    }

    private fun orderPanels(panels: List<IntRect>, rtl: Boolean): List<IntRect> {
        if (panels.size <= 1) return panels
        val sorted = panels.sortedBy { it.top }
        val medianH = sorted.map { it.height }.sorted()[sorted.size / 2].coerceAtLeast(1)
        val rows = mutableListOf<MutableList<IntRect>>()
        for (p in sorted) {
            val row = rows.lastOrNull()
            if (row != null && p.top - row.first().top <= medianH * 0.5f) {
                row.add(p)
            } else {
                rows.add(mutableListOf(p))
            }
        }
        return rows.flatMap { row ->
            if (rtl) row.sortedByDescending { it.right } else row.sortedBy { it.left }
        }
    }
}
