package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The reanchor: where a patch goes when the page moves under it.
 *
 * The bug this pins: the patch was **moved** by its old CSS offset but never **resized**, while the restore
 * resized it with the image. The two rules disagreed, and the disagreement showed as a bubble that was suddenly
 * the wrong size and no longer on its text — a leftward shift on a page that was laid out again.
 *
 * Every case here is a page that moved under the patch: a scroll, a lazy image that loaded above, a zoom, a
 * rotation, a responsive width.
 */
class ReanchorTest {
    private fun fakeImage(w: Int = 200, h: Int = 100): ImageBitmap = Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(ImageBitmap::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getWidth" -> w
            "getHeight" -> h
            else -> null
        }
    } as ImageBitmap

    /** The image: 1000 CSS px wide, 2000 tall, at the page's corner. */
    private val image = IntRect(0, 0, 1000, 2000)

    /**
     * A patch on the image: 100 px from its left, 200 px from its top, 200 x 100 big.
     *
     * Its fraction of the image: 0.1 across, 0.1 down, 0.2 wide, 0.05 tall.
     */
    private fun anchorAt(occurrence: Int = 0, img: IntRect = image): Anchor {
        val scale = 1f
        val imgW = img.width / scale
        val imgH = img.height / scale
        return Anchor(
            "p.jpg", 100f, 200f, imgW, imgH, occurrence,
            nw = 200f / imgW, nh = 100f / imgH, nx = 100f / imgW, ny = 200f / imgH,
        )
    }

    @Test
    fun aPatchStaysOnItsArtWhenThePageScrolls() {
        val now = ImageLayout(listOf(image), listOf("p.jpg"), 0, 500, 1f)
        val rect = anchoredRect(anchorAt(), now)!!
        assertEquals(100f, rect.leftCss, 1e-3f)
        assertEquals(200f + 500f, rect.topCss, 1e-3f)
        assertEquals(200f, rect.widthCss, 1e-3f)
        assertEquals(100f, rect.heightCss, 1e-3f)
    }

    /**
     * A page that is laid out at another width: the bubble is a fraction of the image, so it is a fraction of it
     * again. This is the case the old code got wrong: the patch was moved but never resized.
     */
    @Test
    fun aPatchIsResizedWithItsImageWhenTheLayoutChanges() {
        // The same picture, now half as wide: the art is 500 CSS px wide instead of 1000.
        val narrower = IntRect(0, 0, 500, 1000)
        val now = ImageLayout(listOf(narrower), listOf("p.jpg"), 0, 0, 1f)
        val rect = anchoredRect(anchorAt(img = image), now)!!
        // The place is a fraction of the image, so it halves with it.
        assertEquals(50f, rect.leftCss, 1e-3f)
        assertEquals(100f, rect.topCss, 1e-3f)
        // And the size halves with it: the old code kept 200 x 100 here.
        assertEquals(100f, rect.widthCss, 1e-3f, "the bubble was not resized with its image")
        assertEquals(50f, rect.heightCss, 1e-3f, "the bubble was not resized with its image")
    }

    /**
     * A zoom: the CSS size does not change, so neither does the bubble.
     */
    @Test
    fun aZoomDoesNotChangeTheBubble() {
        val now = ImageLayout(listOf(IntRect(0, 0, 2000, 4000)), listOf("p.jpg"), 0, 0, 2f)
        val rect = anchoredRect(anchorAt(), now)!!
        assertEquals(100f, rect.leftCss, 1e-3f)
        assertEquals(200f, rect.topCss, 1e-3f)
        assertEquals(200f, rect.widthCss, 1e-3f)
        assertEquals(100f, rect.heightCss, 1e-3f)
    }

    /**
     * The same picture twice on one page: the patch belongs to the one it was found on.
     *
     * The old code always took the **first** match, so a patch on the second picture jumped to the first one —
     * a leftward shift of the whole bubble.
     */
    @Test
    fun aPatchStaysOnItsOwnPictureWhenTheSameOneIsTwice() {
        val first = IntRect(0, 0, 1000, 2000)
        val second = IntRect(0, 2000, 1000, 4000)
        val now = ImageLayout(listOf(first, second), listOf("p.jpg", "p.jpg"), 0, 0, 1f)
        // The patch was found on the SECOND picture: 200 px from that picture's own top.
        val rect = anchoredRect(anchorAt(occurrence = 1, img = second), now)!!
        assertEquals(2000f + 200f, rect.topCss, 1e-3f, "the patch jumped to the other picture")
        // And it is not the first picture's place: that would be 200 px from the top of the page.
        assertTrue(rect.topCss > 2000f, "the patch jumped to the other picture")
    }

    /**
     * A patch whose anchor has no fraction (an old one) still follows its art by the CSS offset.
     */
    @Test
    fun anAnchorWithoutAFractionStillFollowsItsArt() {
        val old = Anchor("p.jpg", 100f, 200f, 1000f, 2000f)
        val now = ImageLayout(listOf(image), listOf("p.jpg"), 0, 500, 1f)
        val rect = anchoredRect(old, now)!!
        assertEquals(100f, rect.leftCss, 1e-3f)
        assertEquals(200f + 500f, rect.topCss, 1e-3f)
    }

    /**
     * Twenty reanchors at the same layout change nothing: the box is the same every time.
     */
    @Test
    fun twentyReanchorsDoNotDrift() {
        val now = ImageLayout(listOf(image), listOf("p.jpg"), 0, 300, 1f)
        var first: Geometry.PageRect? = null
        repeat(20) {
            val rect = anchoredRect(anchorAt(), now)!!
            if (first == null) {
                first = rect
            } else {
                assertEquals(first!!.leftCss, rect.leftCss, 1e-3f, "the box drifted")
                assertEquals(first!!.topCss, rect.topCss, 1e-3f, "the box drifted")
                assertEquals(first!!.widthCss, rect.widthCss, 1e-3f, "the bubble shrank")
                assertEquals(first!!.heightCss, rect.heightCss, 1e-3f, "the bubble shrank")
            }
        }
    }

    /**
     * The anchor the live placement builds and the one the restore builds must agree.
     *
     * They are built by different code — one from the capture's pixels, one from the disk record — and the
     * bubble must not move between them.
     */
    @Test
    fun theLiveAnchorAndTheRestoredOneAgree() {
        val scale = 2.60625f
        val geo = Geometry.CaptureGeometry(1200, 2160, 137, 4209, scale)
        val img = IntRect(240, 310, 1140, 1910)
        val view = Geometry.viewRect(31f, 25f, 20f, 12f, geo)!!
        val live = anchorFor(view.left, view.top, view.width, view.height, listOf(img), listOf("p.jpg"), scale)!!
        val norm = Geometry.normalizedRect(view, img, geo)!!
        val restored = Anchor(
            "p.jpg", norm.nx * (img.width / scale), norm.ny * (img.height / scale),
            img.width / scale, img.height / scale, 0, norm.nw, norm.nh, norm.nx, norm.ny,
        )
        val layout = ImageLayout(listOf(img), listOf("p.jpg"), 137, 4209, scale)
        val a = anchoredRect(live, layout)!!
        val b = anchoredRect(restored, layout)!!
        assertEquals(a.leftCss, b.leftCss, 1e-3f, "the live and the restored anchors disagree")
        assertEquals(a.topCss, b.topCss, 1e-3f, "the live and the restored anchors disagree")
        assertEquals(a.widthCss, b.widthCss, 1e-3f, "the live and the restored anchors disagree")
        assertEquals(a.heightCss, b.heightCss, 1e-3f, "the live and the restored anchors disagree")
    }

    /**
     * The anchor finds the image the bubble is really on, and says which one it is.
     */
    @Test
    fun theAnchorSaysWhichPictureItIsOn() {
        val first = IntRect(0, 0, 1000, 2000)
        val second = IntRect(0, 2000, 1000, 4000)
        // A bubble at y=2100 is on the second picture.
        val a = anchorFor(100, 2100, 200, 100, listOf(first, second), listOf("p.jpg", "p.jpg"))!!
        assertEquals(1, a.occurrence)
        assertNotNull(a)
        assertTrue(a.nw > 0f)
    }
}
