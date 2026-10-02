package com.smnexstudio.panelglass.core.pipeline

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.ocr.BoxKind
import com.smnexstudio.panelglass.core.ocr.TextBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TileDetectionTest {
    private fun box(l: Int, t: Int, r: Int, b: Int, kind: BoxKind = BoxKind.TEXT_BUBBLE) = TextBox(IntRect(l, t, r, b), kind, 0.9f)
    private fun line(l: Int, t: Int, r: Int, b: Int, text: String) = TextLine(IntRect(l, t, r, b), text)

    @Test fun onlyTallPagesAreTiled() {
        assertFalse(TileDetection.isTall(1200, 1700))
        assertFalse(TileDetection.isTall(800, 1600))
        assertTrue(TileDetection.isTall(800, 20000))
    }

    @Test fun tilesCoverTheStripAndOverlap() {
        val tiles = TileDetection.tiles(800, 20000)
        assertEquals(0, tiles.first().top)
        assertEquals(20000, tiles.last().bottom)
        tiles.forEach { assertEquals(1200, it.height); assertEquals(800, it.width) }
        for ((a, b) in tiles.zipWithNext()) {
            assertTrue(b.top < a.bottom, "tiles overlap")
            assertTrue(a.bottom - b.top >= (1200 * TileDetection.OVERLAP).toInt() - 1)
        }
        // Every row is in some tile.
        for (y in 0 until 20000 step 97) assertTrue(tiles.any { y >= it.top && y < it.bottom }, "row $y")
    }

    @Test fun aShortPageIsOneTile() = assertEquals(listOf(IntRect(0, 0, 800, 1000)), TileDetection.tiles(800, 1000))

    @Test fun overlapCopiesMergeIntoTheWholeOne() {
        val t1 = IntRect(0, 0, 800, 1200)
        val t2 = IntRect(0, 840, 800, 2040)
        // One bubble at y 900..1100: whole in both tiles.
        val results = listOf(
            TileDetection.TileResult(t1, listOf(box(100, 900, 300, 1100)), listOf(line(120, 920, 280, 1080, "こんにちは"))),
            TileDetection.TileResult(t2, listOf(box(102, 901, 301, 1099)), listOf(line(121, 921, 279, 1079, "こんにちは"))),
        )
        assertEquals(1, TileDetection.mergeBoxes(results, 2040).size)
        assertEquals(1, TileDetection.mergeLines(results, 2040).size)
    }

    @Test fun theCopyATileSawWholeWins() {
        val t1 = IntRect(0, 0, 800, 1200)
        val t2 = IntRect(0, 840, 800, 2040)
        // A bubble at y 1100..1300: cut by tile 1's bottom edge, whole in tile 2.
        val results = listOf(
            TileDetection.TileResult(t1, listOf(box(100, 1100, 300, 1200)), listOf(line(120, 1110, 280, 1200, "こん"))),
            TileDetection.TileResult(t2, listOf(box(100, 1100, 300, 1300)), listOf(line(120, 1110, 280, 1290, "こんにちは"))),
        )
        val boxes = TileDetection.mergeBoxes(results, 2040)
        assertEquals(listOf(IntRect(100, 1100, 300, 1300)), boxes.map { it.bbox })
        assertEquals(listOf("こんにちは"), TileDetection.mergeLines(results, 2040).map { it.text })
    }

    @Test fun aBoxCutInEveryTileIsTheirUnion() {
        val t1 = IntRect(0, 0, 800, 1200)
        val t2 = IntRect(0, 840, 800, 2040)
        // Taller than the overlap: tile 1 sees 700..1200, tile 2 sees 840..1500.
        val results = listOf(
            TileDetection.TileResult(t1, listOf(box(100, 700, 300, 1200)), emptyList()),
            TileDetection.TileResult(t2, listOf(box(100, 840, 300, 1500)), emptyList()),
        )
        assertEquals(listOf(IntRect(100, 700, 300, 1500)), TileDetection.mergeBoxes(results, 2040).map { it.bbox })
    }

    @Test fun differentKindsAndSeparateBubblesStayApart() {
        val t = IntRect(0, 0, 800, 1200)
        val results = listOf(
            TileDetection.TileResult(
                t,
                listOf(box(100, 100, 400, 400, BoxKind.BUBBLE), box(120, 120, 380, 380, BoxKind.TEXT_BUBBLE), box(500, 100, 700, 300)),
                listOf(line(130, 130, 370, 370, "A"), line(510, 110, 690, 290, "B")),
            ),
        )
        assertEquals(3, TileDetection.mergeBoxes(results, 1200).size)
        assertEquals(2, TileDetection.mergeLines(results, 1200).size)
    }

    @Test fun pageEdgesDoNotCountAsCuts() {
        val first = IntRect(0, 0, 800, 1200)
        val last = IntRect(0, 18800, 800, 20000)
        assertFalse(TileDetection.cutByTile(IntRect(0, 0, 100, 100), first, 20000))
        assertFalse(TileDetection.cutByTile(IntRect(0, 19900, 100, 20000), last, 20000))
        assertTrue(TileDetection.cutByTile(IntRect(0, 1100, 100, 1200), first, 20000))
        assertTrue(TileDetection.cutByTile(IntRect(0, 18800, 100, 18900), last, 20000))
    }
}
