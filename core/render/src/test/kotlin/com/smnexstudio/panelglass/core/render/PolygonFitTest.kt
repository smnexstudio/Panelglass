package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.Pt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolygonFitTest {
    private fun inside(p: List<Pt>, r: FRect) =
        listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom, r.centerX to r.centerY)
            .all { (x, y) -> PolygonFit.contains(p, x, y) }

    @Test fun aRectangleKeepsAlmostAllOfItself() {
        val p = PolygonFit.rectangle(FRect(100f, 100f, 300f, 200f))
        val r = PolygonFit.innerRect(p, insetShare = 0f)!!
        assertEquals(200f, r.width, 1f)
        assertEquals(100f, r.height, 1f)
        val inset = PolygonFit.innerRect(p)!!
        assertTrue(inset.width < r.width && inside(p, inset))
    }

    @Test fun anEllipseGetsTheCentredInscribedRectangle() {
        val p = PolygonFit.ellipse(FRect(0f, 0f, 400f, 200f), n = 64)
        val r = PolygonFit.innerRect(p, insetShare = 0f)!!
        // An axis-aligned rectangle with the ellipse's proportions fits at 1/√2 of its axes.
        assertEquals(400f / kotlin.math.sqrt(2f), r.width, 8f)
        assertEquals(200f / kotlin.math.sqrt(2f), r.height, 4f)
        assertEquals(200f, r.centerX, 1f)
        assertEquals(100f, r.centerY, 1f)
        assertTrue(inside(p, r))
    }

    @Test fun aConcaveLStaysOutOfItsNotch() {
        // An L whose bounding-box centre (50, 50) is on the inner corner.
        val l = listOf(Pt(0f, 0f), Pt(40f, 0f), Pt(40f, 60f), Pt(100f, 60f), Pt(100f, 100f), Pt(0f, 100f))
        val r = PolygonFit.innerRect(l, insetShare = 0f)
        assertNotNull(r)
        assertTrue(inside(l, r!!))
        // Nothing of it in the notch (x > 40, y < 60).
        assertTrue(!(r.right > 40f && r.top < 60f))
    }

    @Test fun degenerateShapesGiveNothing() {
        assertNull(PolygonFit.innerRect(listOf(Pt(0f, 0f), Pt(10f, 10f))))
        assertNull(PolygonFit.innerRect(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(20f, 0f))))
    }
}
