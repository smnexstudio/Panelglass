package com.smnexstudio.panelglass.feature.browser

import kotlin.math.abs

/**
 * Which patches to keep in memory.
 *
 * Two rules, in this order:
 *
 * 1. **Distance.** A patch is kept while it is within [screens] viewport heights above or below the viewport. The
 *    others are dropped: they are re-made on return.
 * 2. **Bytes.** The kept patches may not use more than [maxBytes] of decoded memory. The ones farthest from the
 *    viewport go first — but a patch that is on screen is never dropped, whatever the cap says: the user is looking
 *    at it.
 *
 * The function is pure: the same input always gives the same output, and nothing here reads a clock.
 */
object PatchWindow {
    /** The result of a pruning: what is kept, and what was dropped for which reason. */
    class Pruned(val kept: List<ScreenPatch>, val droppedByDistance: Int, val droppedByBytes: Int)

    /**
     * Keeps the patches that are near the viewport and within the byte budget.
     *
     * [scrollY] and [viewportH] are in page (content) pixels; [scale] is the WebView's zoom, so a patch's own rect
     * is measured with it.
     */
    fun prune(
        patches: List<ScreenPatch>,
        scrollY: Int,
        viewportH: Int,
        scale: Float,
        screens: Int,
        maxBytes: Long,
    ): Pruned {
        // Rule 1: the same comparison the reader has always used — a patch whose bottom is above the window's top
        // edge, or whose top is below its bottom edge, is too far away.
        val keep = viewportH * screens
        val near = patches.filter { val r = it.rectAt(scale); r.bottom >= scrollY - keep && r.top <= scrollY + viewportH + keep }
        val droppedByDistance = patches.size - near.size

        // Rule 2: the byte budget, farthest from the viewport centre first.
        val centre = scrollY + viewportH / 2
        val ordered = near.sortedBy { p ->
            val r = p.rectAt(scale)
            abs((r.top + r.height / 2) - centre)
        }
        var total = 0L
        val kept = ArrayList<ScreenPatch>(near.size)
        var droppedByBytes = 0
        for (p in ordered) {
            val visible = isVisible(p, scrollY, viewportH, scale)
            if (total + p.bytes <= maxBytes || visible) {
                kept += p
                total += p.bytes
            } else {
                droppedByBytes++
            }
        }
        // The kept ones come back in the order they were given, so the draw order does not depend on the pruning.
        val order = patches.withIndex().associate { (i, p) -> p to i }
        return Pruned(kept.sortedBy { order[it] ?: 0 }, droppedByDistance, droppedByBytes)
    }

    /** Whether a patch is on screen: it must not be dropped for the byte budget. */
    private fun isVisible(p: ScreenPatch, scrollY: Int, viewportH: Int, scale: Float): Boolean {
        val r = p.rectAt(scale)
        return r.bottom >= scrollY && r.top <= scrollY + viewportH
    }
}

/** The decoded size of a patch: an ARGB bitmap is four bytes a pixel. */
val ScreenPatch.bytes: Long
    get() = image.width.toLong() * image.height.toLong() * 4L
