package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PatchAnchorTest {
    private val page1 = IntRect(0, -300, 1130, 1300)
    private val page2 = IntRect(0, 1320, 1130, 2920)

    @Test
    fun aPatchIsAnchoredToTheImageUnderItsCentre() {
        val a = anchorFor(400, 1500, 170, 240, listOf(page1, page2), listOf("p1.jpg", "p2.jpg"))!!
        assertEquals("p2.jpg", a.key)
        assertEquals(400f, a.dx); assertEquals(180f, a.dy)
    }

    @Test
    fun scrollAnchoringLeavesThePatchOnItsArt() {
        // Captured at scroll 1559; a lazy image above finished loading, the browser moved the scroll offset by 94 px
        // to keep the art where it was on screen. The image is at the same viewport place, the page offset changed.
        val a = anchorFor(400, 1500, 170, 240, listOf(page2), listOf("p2.jpg"))!!
        val now = ImageLayout(listOf(page2), listOf("p2.jpg"), 0, 1559 + 94)
        assertEquals(Pair(400f, 1559f + 94 + 1500), anchoredPosition(a, now))
    }

    @Test
    fun aReaderMovingItsPageInItsOwnLayerIsFollowed() {
        // bilibili: no scroll, the canvas settled 11 px lower.
        val canvas = IntRect(0, 0, 1220, 2100)
        val a = anchorFor(300, 800, 150, 200, listOf(canvas), listOf("canvas#0"))!!
        val now = ImageLayout(listOf(canvas.offset(0, 11)), listOf("canvas#0"), 0, 0)
        assertEquals(Pair(300f, 811f), anchoredPosition(a, now))
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

    @Test
    fun anAnchorIsTheSamePlaceOnTheArtAtAnyZoom() {
        // Made at zoom 2.625 (a 1080 px phone showing a 411 CSS px page): the image at view 0,100, the patch 262 px
        // right and 525 px down from its corner (99.8,200 CSS px). Zoomed to 5.25 with the page scrolled to 300,900,
        // the image's corner is at view -300,-700: the patch keeps its CSS offset from that corner.
        val img = IntRect(0, 100, 1080, 2000)
        val a = anchorFor(262, 625, 200, 300, listOf(img), listOf("p.jpg"), scale = 2.625f)!!
        assertEquals(262f / 2.625f, a.dx, 1e-3f); assertEquals(525f / 2.625f, a.dy, 1e-3f)
        val now = ImageLayout(listOf(IntRect(-300, -700, 1860, 3100)), listOf("p.jpg"), 300, 900, scale = 5.25f)
        val (x, y) = anchoredPosition(a, now)!!
        assertEquals(0f / 5.25f + 262f / 2.625f, x, 1e-3f)
        assertEquals(200f / 5.25f + 525f / 2.625f, y, 1e-3f)
    }

    @Test
    fun aPatchScalesWithTheZoom() {
        // Made at zoom 2 over page px 400,1000 200x100: 200,500 100x50 in CSS px.
        val p = ScreenPatch(fakeImage, 200f, 500f, 100f, 50f)
        assertEquals(IntRect(400, 1000, 600, 1100), p.rectAt(2f))
        assertEquals(IntRect(800, 2000, 1200, 2200), p.rectAt(4f))
        assertEquals(IntRect(200, 500, 300, 550), p.rectAt(1f))
    }

    @Test
    fun theSameBubbleSeenAtTwoZoomsIsADuplicate() {
        // One bubble captured at zoom 2 and again at zoom 3: CSS rects match within rounding.
        val atTwo = ScreenPatch(fakeImage, 400 / 2f, 1000 / 2f, 200 / 2f, 100 / 2f)
        val atThree = ScreenPatch(fakeImage, 601 / 3f, 1499 / 3f, 300 / 3f, 151 / 3f)
        assertTrue(atThree.duplicates(atTwo, 0.6f))
    }

    // Placement never draws the bitmap.
    private val fakeImage = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageBitmap::class.java)) { _, _, _ -> null } as ImageBitmap
}
