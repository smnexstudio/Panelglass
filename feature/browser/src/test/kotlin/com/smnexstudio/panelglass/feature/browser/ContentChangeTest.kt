package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContentChangeTest {
    private val gw = 4; private val gh = 4
    private fun thumb(f: (Int, Int) -> Int) = Thumb(IntArray(gw * gh) { f(it % gw, it / gw) }, gw, gh, 400, 400)

    @Test
    fun anotherPageIsAChange() {
        assertEquals(true, contentChanged(thumb { _, _ -> 240 }, thumb { x, _ -> if (x < 2) 20 else 240 }, emptyList()))
    }

    @Test
    fun ourOwnPatchesAreNotAChange() {
        // The same page with a patch over its top-left quarter: only masked cells differ.
        val before = thumb { _, _ -> 240 }
        val now = thumb { x, y -> if (x < 2 && y < 2) 30 else 240 }
        assertEquals(false, contentChanged(before, now, listOf(IntRect(0, 0, 200, 200))))
    }

    @Test
    fun aFadingControlIsNotAChange() {
        assertEquals(false, contentChanged(thumb { _, _ -> 200 }, thumb { x, y -> if (x == 3 && y == 0) 120 else 200 }, emptyList()))
    }

    @Test
    fun allPatchedCannotTell() {
        assertNull(contentChanged(thumb { _, _ -> 240 }, thumb { _, _ -> 0 }, listOf(IntRect(0, 0, 400, 400))))
    }
}
