package com.smnexstudio.panelglass.feature.studio.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasTransformTest {
    // A 1200 × 1700 page in a 1000 × 1000 canvas: the fit is height-bound.
    private val t = CanvasTransform(1200f, 1700f, 1000f, 1000f)
    private val eps = 0.01f

    @Test fun theFitShowsTheWholePageCentred() {
        val v = t.fitView()
        assertEquals(1000f / 1700f, v.scale, 1e-5f)
        assertEquals(1f, t.zoom(v), 1e-5f)
        val (l, top) = t.toScreen(v, 0f, 0f)
        val (r, b) = t.toScreen(v, 1200f, 1700f)
        assertEquals(1000f - r, l, 0.5f) // centred horizontally
        assertEquals(0f, top, eps); assertEquals(1000f, b, eps)
    }

    @Test fun aTouchMapsToTheSamePagePixelAtEveryZoomAndPan() {
        val page = 700f to 900f
        for (zoom in listOf(1f, 3f, 8f)) {
            var v = t.transform(t.fitView(), 500f, 500f, zoom, 0f, 0f)
            v = t.pan(v, -37f, 61f)
            val (sx, sy) = t.toScreen(v, page.first, page.second)
            val (px, py) = t.toPage(v, sx, sy)
            assertEquals(page.first, px, 0.01f)
            assertEquals(page.second, py, 0.01f)
        }
    }

    @Test fun pinchKeepsThePointUnderTheFingers() {
        val v0 = t.fitView()
        val focus = 420f to 610f
        val before = t.toPage(v0, focus.first, focus.second)
        val v1 = t.transform(v0, focus.first, focus.second, 2f, 0f, 0f)
        val after = t.toPage(v1, focus.first, focus.second)
        assertEquals(before.first, after.first, 0.5f)
        assertEquals(before.second, after.second, 0.5f)
    }

    @Test fun zoomStaysWithinOneToEight() {
        val out = t.transform(t.fitView(), 500f, 500f, 0.1f, 0f, 0f)
        assertEquals(1f, t.zoom(out), 1e-4f)
        val far = t.transform(t.fitView(), 500f, 500f, 50f, 0f, 0f)
        assertEquals(8f, t.zoom(far), 1e-4f)
    }

    @Test fun panNeverShowsSpaceBeyondThePage() {
        val v = t.pan(t.transform(t.fitView(), 500f, 500f, 4f, 0f, 0f), 100000f, 100000f)
        val (l, top) = t.toScreen(v, 0f, 0f)
        assertEquals(0f, l, eps); assertEquals(0f, top, eps)
        val w = t.pan(v, -100000f, -100000f)
        val (r, b) = t.toScreen(w, 1200f, 1700f)
        assertEquals(1000f, r, eps); assertEquals(1000f, b, eps)
    }

    @Test fun doubleTapOnABubbleFitsIt() {
        val bubble = Box(600f, 200f, 800f, 500f)
        val v = t.doubleTap(t.fitView(), 0f, 0f, bubble, 40f)
        val s = t.toScreen(v, bubble)
        assertTrue(s.left >= 40f - eps && s.right <= 960f + eps && s.top >= 40f - eps && s.bottom <= 960f + eps)
        // As large as fits: one axis touches the margins.
        assertTrue(kotlin.math.abs(s.height - 920f) < 1f || kotlin.math.abs(s.width - 920f) < 1f)
    }

    @Test fun doubleTapOnArtTogglesBetweenFitAndTwoAndAHalf() {
        val z = t.doubleTap(t.fitView(), 500f, 500f, null, 40f)
        assertEquals(2.5f, t.zoom(z), 1e-3f)
        assertEquals(1f, t.zoom(t.doubleTap(z, 500f, 500f, null, 40f)), 1e-3f)
    }

    @Test fun followingKeepsTheZoomAndPansTheBubbleIntoView() {
        val v = t.transform(t.fitView(), 500f, 500f, 3f, 0f, 0f)
        val bubble = Box(1000f, 1500f, 1150f, 1650f) // bottom right, off screen at 3×
        val f = t.follow(v, bubble, 24f)
        assertEquals(t.zoom(v), t.zoom(f), 1e-4f)
        val s = t.toScreen(f, bubble)
        assertTrue(s.left >= 24f - eps && s.right <= 976f + eps && s.top >= 24f - eps && s.bottom <= 976f + eps)
    }

    @Test fun followingZoomsOutOnlyForABubbleThatCannotFit() {
        val v = t.transform(t.fitView(), 500f, 500f, 8f, 0f, 0f)
        val big = Box(100f, 100f, 900f, 900f)
        val f = t.follow(v, big, 24f)
        assertTrue(t.zoom(f) < 8f)
        val s = t.toScreen(f, big)
        assertTrue(s.width <= 1000f && s.height <= 1000f)
    }

    @Test fun aResizedCanvasKeepsTheSameCentreAndScale() {
        // Zoomed on the middle of the page, then the keyboard takes 40% of the canvas.
        val v = t.transform(t.fitView(), 500f, 500f, 3f, 0f, 0f)
        val centre = t.toPage(v, 500f, 500f)
        val small = CanvasTransform(1200f, 1700f, 1000f, 600f)
        val r = small.resized(v, t)
        assertEquals(v.scale, r.scale, 1e-4f) // the text keeps its size on screen
        val c = small.toPage(r, 500f, 300f)
        assertEquals(centre.first, c.first, 1f)
        assertEquals(centre.second, c.second, 1f)
    }
}
