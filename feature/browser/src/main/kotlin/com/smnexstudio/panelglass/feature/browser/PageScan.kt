package com.smnexstudio.panelglass.feature.browser

/**
 * The plan of a whole-page scan: where to stop, and what to keep.
 *
 * Pure logic, so it can be tested without a WebView: the scan loop in the reader only moves the page and asks the
 * pipeline to translate what is on screen.
 */
data class ScanProgress(val step: Int, val steps: Int, val gaps: Int = 0)

/** The page as measured now, in view pixels at the current zoom. */
data class PageMetrics(val pageHeight: Int, val viewportHeight: Int, val scrollY: Int)

object PageScanPlan {
    /** How much of the viewport one step covers; the rest overlaps, so a bubble cut by an edge is whole in the next. */
    const val STEP_FRACTION = 0.7f

    /** A very long page would take forever; the scan stops there. */
    const val MAX_STEPS = 120

    /** How far one step goes, in pixels. */
    fun stepPx(viewportHeight: Int): Int = (viewportHeight * STEP_FRACTION).toInt().coerceAtLeast(1)

    /** How far the page can be scrolled: its height less the viewport. */
    fun maxScrollY(pageHeight: Int, viewportHeight: Int): Int = (pageHeight - viewportHeight).coerceAtLeast(0)

    /**
     * The scroll offsets to capture: 0, one step, two steps, … and always the last one (the bottom of the page).
     * Ascending, without duplicates, at most [MAX_STEPS] of them.
     */
    fun positions(pageHeight: Int, viewportHeight: Int): List<Int> {
        val max = maxScrollY(pageHeight, viewportHeight)
        if (max <= 0) return listOf(0)
        val step = stepPx(viewportHeight)
        val out = ArrayList<Int>()
        var y = 0
        while (y < max && out.size < MAX_STEPS) {
            out += y
            y += step
        }
        // The bottom of the page is always one of the stops (the last step may jump over it).
        if (out.size < MAX_STEPS && out.lastOrNull() != max) out += max
        return out
    }

    /** How many stops a scan of this page has. */
    fun steps(pageHeight: Int, viewportHeight: Int): Int = positions(pageHeight, viewportHeight).size

    /**
     * The items to keep, with the ones farthest from the viewport dropped first until the total fits [budget]. The
     * original order is kept: the patches are drawn in the order they were made.
     */
    fun <T> evictFarthest(items: List<T>, budget: Long, bytes: (T) -> Long, distance: (T) -> Int): List<T> {
        if (items.isEmpty()) return items
        var total = items.sumOf(bytes)
        if (total <= budget) return items
        // The farthest first. Equally far ones go in the order they were made (the later one first), so the
        // result is the same every time.
        val byDistance = items.withIndex().sortedWith(
            compareByDescending<IndexedValue<T>>({ distance(it.value) }).thenByDescending { it.index },
        )
        val dropped = HashSet<Int>()
        for (entry in byDistance) {
            if (total <= budget) break
            dropped += entry.index
            total -= bytes(entry.value)
        }
        return items.filterIndexed { index, _ -> index !in dropped }
    }

    /** The page's URL without its #fragment, for "did the page change" checks. */
    fun pageKey(url: String): String = url.substringBefore('#')
}
