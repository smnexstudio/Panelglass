package com.smnexstudio.panelglass.feature.studio

import com.smnexstudio.panelglass.core.ui.WidthClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The phone / tablet / laptop breakpoints every Studio layout switches on. */
class WidthClassTest {
    @Test fun breakpointsFollowTheWindowWidth() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(411)) // a phone
        assertEquals(WidthClass.COMPACT, WidthClass.of(599))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600)) // a tablet in portrait, an unfolded foldable
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840)) // a laptop, a Chromebook window, a tablet on its side
        assertEquals(WidthClass.EXPANDED, WidthClass.of(1706))
        assertFalse(WidthClass.COMPACT.wide)
        assertTrue(WidthClass.MEDIUM.wide)
        assertTrue(WidthClass.EXPANDED.wide)
    }
}
