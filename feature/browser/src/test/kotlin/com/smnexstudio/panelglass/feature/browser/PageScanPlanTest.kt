package com.smnexstudio.panelglass.feature.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plan of a whole-page scan: where to stop, and what to keep. */
class PageScanPlanTest {
    @Test fun aStepCoversSeventyPercentOfTheViewport() {
        assertEquals(700, PageScanPlan.stepPx(1000))
        assertEquals(1, PageScanPlan.stepPx(1))
    }

    @Test fun theStopsOfAThreeScreenPage() {
        assertEquals(2000, PageScanPlan.maxScrollY(3000, 1000))
        assertEquals(listOf(0, 700, 1400, 2000), PageScanPlan.positions(3000, 1000))
    }

    @Test fun aPageThatFitsIsOneStop() {
        assertEquals(listOf(0), PageScanPlan.positions(800, 1000))
        assertEquals(listOf(0), PageScanPlan.positions(1000, 1000))
    }

    @Test fun aPageOfOneAndAHalfScreens() {
        assertEquals(listOf(0, 700), PageScanPlan.positions(1700, 1000))
    }

    @Test fun theLastStopIsAlwaysTheBottomAndTheyAscend() {
        for (page in listOf(1001, 1700, 3000, 5500, 12000)) {
            val positions = PageScanPlan.positions(page, 1000)
            assertEquals(PageScanPlan.maxScrollY(page, 1000), positions.last(), "page $page")
            assertTrue(positions.zipWithNext().all { (a, b) -> b > a }, "page $page: $positions")
            assertEquals(positions.size, PageScanPlan.steps(page, 1000))
        }
    }

    @Test fun aVeryLongPageIsCapped() {
        val positions = PageScanPlan.positions(1_000_000, 1000)
        assertEquals(PageScanPlan.MAX_STEPS, positions.size)
        assertEquals(0, positions.first())
    }

    @Test fun underTheBudgetNothingIsDropped() {
        val items = listOf("a", "b", "c")
        assertEquals(items, PageScanPlan.evictFarthest(items, 100, { 10 }, { 0 }))
    }

    @Test fun overTheBudgetTheFarthestGoesFirst() {
        val items = listOf("near", "far", "middle")
        // 10 + 10 + 10 = 30; a budget of 20 keeps two: the far one goes.
        val kept = PageScanPlan.evictFarthest(items, 20, { 10 }, { if (it == "far") 100 else 1 })
        assertEquals(listOf("near", "middle"), kept)
    }

    @Test fun itStopsAsSoonAsItFits() {
        val items = listOf(1, 2, 3, 4)
        // 1+2+3+4 = 10; a budget of 6 keeps three (1+2+3).
        val kept = PageScanPlan.evictFarthest(items, 6, { it.toLong() }, { it })
        assertEquals(listOf(1, 2, 3), kept)
    }

    @Test fun aBudgetOfZeroKeepsNothing() {
        assertTrue(PageScanPlan.evictFarthest(listOf("a", "b"), 0, { 1 }, { 0 }).isEmpty())
    }

    @Test fun equalDistancesAreDroppedInOrder() {
        val items = listOf("a", "b", "c")
        val kept = PageScanPlan.evictFarthest(items, 20, { 10 }, { 5 })
        // All equally far: the last one goes first, so the earlier ones stay.
        assertEquals(listOf("a", "b"), kept)
    }

    @Test fun thePageKeyDropsTheFragment() {
        assertEquals("https://a/b?x=1", PageScanPlan.pageKey("https://a/b?x=1#c"))
        assertEquals("https://a/b?x=1", PageScanPlan.pageKey("https://a/b?x=1"))
    }
}
