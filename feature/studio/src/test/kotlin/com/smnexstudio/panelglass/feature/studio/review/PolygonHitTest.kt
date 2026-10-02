package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.model.Pt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolygonHitTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) = listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))

    // An L: the notch (top right) is outside.
    private val l = listOf(Pt(0f, 0f), Pt(50f, 0f), Pt(50f, 50f), Pt(100f, 50f), Pt(100f, 100f), Pt(0f, 100f))

    @Test fun rectangle() {
        val r = rect(10f, 10f, 20f, 30f)
        assertTrue(PolygonHit.contains(r, 15f, 20f))
        assertFalse(PolygonHit.contains(r, 25f, 20f))
        assertFalse(PolygonHit.contains(r, 15f, 35f))
    }

    @Test fun concaveShape() {
        assertTrue(PolygonHit.contains(l, 25f, 25f))
        assertTrue(PolygonHit.contains(l, 75f, 75f))
        assertFalse(PolygonHit.contains(l, 75f, 25f))
    }

    @Test fun ellipseLikePolygon() {
        val e = (0 until 32).map { i ->
            val a = i / 32.0 * 2 * Math.PI
            Pt((100 + 60 * Math.cos(a)).toFloat(), (100 + 30 * Math.sin(a)).toFloat())
        }
        assertTrue(PolygonHit.contains(e, 100f, 100f))
        assertTrue(PolygonHit.contains(e, 150f, 100f))
        assertFalse(PolygonHit.contains(e, 155f, 125f)) // inside the box, outside the ellipse
    }

    @Test fun theSmallestContainingShapeWins() {
        val balloon = rect(0f, 0f, 200f, 200f)
        val text = rect(50f, 50f, 150f, 150f)
        assertEquals(1, PolygonHit.pick(listOf(balloon, text), 100f, 100f, 10f))
        assertEquals(0, PolygonHit.pick(listOf(balloon, text), 20f, 20f, 10f))
    }

    @Test fun aTapJustOutsideStillPicksWithinTheSlop() {
        val r = rect(10f, 10f, 20f, 20f)
        assertEquals(0, PolygonHit.pick(listOf(r), 23f, 15f, 5f))
        assertNull(PolygonHit.pick(listOf(r), 40f, 15f, 5f))
    }

    @Test fun degeneratePolygonsNeverContain() = assertFalse(PolygonHit.contains(listOf(Pt(0f, 0f), Pt(10f, 10f)), 5f, 5f))
}
