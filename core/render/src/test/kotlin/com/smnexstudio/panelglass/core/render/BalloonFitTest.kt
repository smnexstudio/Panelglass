package com.smnexstudio.panelglass.core.render

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The text box inside a balloon found from its paper, when the stored shape is only the balloon's bounding box. */
class BalloonFitTest {
    private val ink = 0xFF000000.toInt()
    private val paper = 0xFFFFFFFF.toInt()
    private val art = 0xFF808080.toInt()

    /** A w × h box of grey art with a white oval (black outline) filling it, as a detected balloon looks. */
    private fun oval(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val dx = (x - w / 2f) / (w / 2f)
        val dy = (y - h / 2f) / (h / 2f)
        val d = dx * dx + dy * dy
        when {
            d < 0.85f -> paper
            d < 1f -> ink
            else -> art
        }
    }

    private fun insideOval(r: FRect, w: Int, h: Int): Boolean =
        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).all { (x, y) ->
            val dx = (x - w / 2f) / (w / 2f)
            val dy = (y - h / 2f) / (h / 2f)
            dx * dx + dy * dy < 0.85f
        }

    @Test fun theTextBoxStaysInsideAnOvalNotItsBoundingBox() {
        val w = 300
        val h = 200
        val r = BalloonFit.interior(oval(w, h), w, h)
        assertNotNull(r)
        r!!
        assertTrue(insideOval(r, w, h), "every corner on the paper: $r")
        // Still a generous box: the largest rectangle in an ellipse is about 70% of each axis.
        assertTrue(r.width > w * 0.5f && r.height > h * 0.5f, "not shrunk to nothing: $r")
        assertTrue(r.width < w * 0.8f, "not the whole box: $r")
    }

    @Test fun aSpeckOfLeftoverTextAtTheCentreDoesNotStopIt() {
        val w = 240
        val h = 240
        val px = oval(w, h)
        for (y in 115..125) for (x in 115..125) px[y * w + x] = ink
        assertNotNull(BalloonFit.interior(px, w, h))
    }

    @Test fun theOutlineFollowsTheOvalNotTheBoxEvenWithTextInIt() {
        val w = 300
        val h = 200
        val px = oval(w, h)
        // Vertical "text" in the middle, as on the page before cleaning.
        for (y in 60..140) for (x in 140..160) if ((y / 6) % 2 == 0) px[y * w + x] = ink
        val o = BalloonFit.outline(px, w, h)
        assertNotNull(o)
        o!!
        assertTrue(o.size == 32)
        for ((x, y) in o) {
            val dx = (x - w / 2f) / (w / 2f)
            val dy = (y - h / 2f) / (h / 2f)
            val d = dx * dx + dy * dy
            // On the balloon's edge (its paper ends at 0.85, its line at 1): never near the box's corners.
            assertTrue(d in 0.7f..1.05f, "($x, $y) d=$d")
        }
    }

    @Test fun noPaperOrTooLittleIsNoBalloon() {
        assertNull(BalloonFit.outline(IntArray(100 * 100) { art }, 100, 100), "dark art: no outline")
        assertNull(BalloonFit.interior(IntArray(100 * 100) { art }, 100, 100), "dark art: no balloon")
        val w = 100
        val h = 100
        // A small white patch in the middle of art: a fifth of the box at most.
        val px = IntArray(w * h) { i -> if (i % w in 45..55 && i / w in 45..55) paper else art }
        assertNull(BalloonFit.interior(px, w, h))
    }
}
