package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.ui.ScrollbarMath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrollbarTest {
    @Test fun noBarWhenEverythingFits() {
        assertNull(ScrollbarMath.forScroll(0, 0, 500))
        assertNull(ScrollbarMath.forList(0, 0f, 4, 4, atEnd = true))
    }

    @Test fun aListLongerThanThePaneShowsABarAtTheTop() {
        val t = ScrollbarMath.forList(0, 0f, 4, 12, atEnd = false)
        assertNotNull(t)
        t!!
        assertEquals(0f, t.offset, 1e-4f)
        assertEquals(4f / 12f, t.size, 1e-4f)
    }

    @Test fun theThumbReachesTheBottomAtTheEnd() {
        val t = ScrollbarMath.forList(8, 0f, 4, 12, atEnd = true)!!
        assertEquals(1f, t.offset + t.size, 1e-4f)
    }

    @Test fun theThumbMovesWithTheScroll() {
        val a = ScrollbarMath.forScroll(100, 1000, 500)!!
        val b = ScrollbarMath.forScroll(600, 1000, 500)!!
        assertTrue(b.offset > a.offset)
        assertEquals(500f / 1500f, a.size, 1e-4f)
    }

    @Test fun aVeryLongListStillHasAThumbToSee() =
        assertEquals(ScrollbarMath.MIN_SIZE, ScrollbarMath.forList(0, 0f, 2, 500, atEnd = false)!!.size, 1e-4f)
}
