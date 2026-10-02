package com.smnexstudio.panelglass.feature.studio.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileCacheTest {
    @Test fun theBaseIsEnoughUntilTheZoomNeedsMorePixels() {
        // A base at 1/4: up to 0.25 screen px per page px the base is enough.
        assertNull(TileGrid.sampleFor(0.2f, 4))
        assertNull(TileGrid.sampleFor(0.25f, 4))
        assertEquals(2, TileGrid.sampleFor(0.4f, 4))
        assertEquals(1, TileGrid.sampleFor(0.9f, 4))
        assertEquals(1, TileGrid.sampleFor(3f, 4))
        // A base at full resolution never needs tiles.
        assertNull(TileGrid.sampleFor(5f, 1))
    }

    @Test fun onlyTheVisibleTilesAreListed() {
        // 2000 × 3000 page at sample 1: 512 px tiles, 4 × 6 grid.
        val keys = TileGrid.visible(Box(600f, 1100f, 1100f, 1500f), 1, 2000, 3000)
        assertEquals(setOf(TileKey(1, 1, 2), TileKey(1, 2, 2)), keys.toSet())
        // The whole page at sample 2 (1024 page px per tile): 2 × 3.
        assertEquals(6, TileGrid.visible(Box(0f, 0f, 2000f, 3000f), 2, 2000, 3000).size)
    }

    @Test fun edgeTilesAreClippedToThePage() {
        val r = TileGrid.rect(TileKey(1, 3, 5), 2000, 3000)
        assertEquals(1536f, r.left); assertEquals(2000f, r.right)
        assertEquals(2560f, r.top); assertEquals(3000f, r.bottom)
    }

    @Test fun theLruStaysUnderItsByteBudgetAndFreesWhatItDrops() {
        val freed = mutableListOf<Int>()
        val lru = TileLru<Int, Int>(budget = 12, sizeOf = { 4L }, onEvict = { freed += it })
        for (i in 1..5) lru.put(i, i)
        assertTrue(lru.bytes <= 12)
        assertEquals(setOf(3, 4, 5), lru.keys())
        assertEquals(listOf(1, 2), freed)
        // A read makes a tile recent: it outlives an older one.
        lru[3]
        lru.put(6, 6)
        assertEquals(setOf(3, 5, 6), lru.keys())
    }

    @Test fun twelveMegabytesUnderFourGigabytes() {
        assertEquals(12L * 1024 * 1024, PageTiles.BUDGET_SMALL)
        assertEquals(24L * 1024 * 1024, PageTiles.BUDGET)
    }

    @Test fun clearingForAPageChangeFreesEverything() {
        val freed = mutableListOf<Int>()
        val lru = TileLru<Int, Int>(budget = 100, sizeOf = { 10L }, onEvict = { freed += it })
        (1..3).forEach { lru.put(it, it) }
        lru.clear()
        assertEquals(0L, lru.bytes)
        assertFalse(lru.keys().isNotEmpty())
        assertEquals(listOf(1, 2, 3), freed)
    }
}
