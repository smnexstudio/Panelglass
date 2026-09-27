package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BubbleFontTest {
    @Test fun replacedFontsMapToTheirSuccessor() {
        assertEquals(BubbleFont.COMING_SOON, BubbleFont.fromName("KOMIKA"))
        assertEquals(BubbleFont.LUCKIEST_GUY, BubbleFont.fromName("COPILME"))
    }

    @Test fun currentNamesRoundTrip() = BubbleFont.entries.forEach { assertEquals(it, BubbleFont.fromName(it.name)) }

    @Test fun unknownNameIsNull() = assertNull(BubbleFont.fromName("COMIC_SANS"))
}
