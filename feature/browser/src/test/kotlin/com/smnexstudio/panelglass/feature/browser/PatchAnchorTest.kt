package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PatchAnchorTest {
    private val page1 = IntRect(0, -300, 1130, 1300)
    private val page2 = IntRect(0, 1320, 1130, 2920)

    @Test
    fun aPatchIsAnchoredToTheImageUnderItsCentre() {
        val a = anchorFor(400, 1500, 170, 240, listOf(page1, page2), listOf("p1.jpg", "p2.jpg"))!!
        assertEquals("p2.jpg", a.key)
        assertEquals(400, a.dx); assertEquals(180, a.dy)
    }

    @Test
    fun scrollAnchoringLeavesThePatchOnItsArt() {
        // Captured at scroll 1559; a lazy image above finished loading, the browser moved the scroll offset by 94 px
        // to keep the art where it was on screen. The image is at the same viewport place, the page offset changed.
        val a = anchorFor(400, 1500, 170, 240, listOf(page2), listOf("p2.jpg"))!!
        val now = ImageLayout(listOf(page2), listOf("p2.jpg"), 0, 1559 + 94)
        assertEquals(Pair(400, 1559 + 94 + 1500), anchoredPosition(a, now))
    }

    @Test
    fun aReaderMovingItsPageInItsOwnLayerIsFollowed() {
        // bilibili: no scroll, the canvas settled 11 px lower.
        val canvas = IntRect(0, 0, 1220, 2100)
        val a = anchorFor(300, 800, 150, 200, listOf(canvas), listOf("canvas#0"))!!
        val now = ImageLayout(listOf(canvas.offset(0, 11)), listOf("canvas#0"), 0, 0)
        assertEquals(Pair(300, 811), anchoredPosition(a, now))
    }

    @Test
    fun anImageOffScreenLeavesThePatchWhereItIs() {
        val a = anchorFor(400, 1500, 170, 240, listOf(page2), listOf("p2.jpg"))!!
        assertNull(anchoredPosition(a, ImageLayout(listOf(page1), listOf("p1.jpg"), 0, 0)))
    }

    @Test
    fun noKeyNoAnchor() {
        assertNull(anchorFor(400, 1500, 170, 240, listOf(page2), listOf("")))
        assertNull(anchorFor(400, 1500, 170, 240, listOf(page2), emptyList()))
    }
}
