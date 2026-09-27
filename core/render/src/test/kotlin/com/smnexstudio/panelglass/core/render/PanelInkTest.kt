package com.smnexstudio.panelglass.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [RegionRenderer.panelInk]: the translation on an opaque mask must always read against it. */
class PanelInkTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun lightInkOnAWhiteMaskTurnsBlack() {
        // A grey title (the source's light outline was sampled as its colour) on free text's white mask.
        assertEquals(black, RegionRenderer.panelInk(0xFFCBCBCB.toInt(), white))
    }

    @Test
    fun darkInkOnADarkMaskTurnsWhite() {
        assertEquals(white, RegionRenderer.panelInk(0xFF202020.toInt(), 0xFF080808.toInt()))
    }

    @Test
    fun contrastingInkIsKept() {
        val red = 0xFFB00020.toInt()
        assertEquals(red, RegionRenderer.panelInk(red, white))
        assertEquals(black, RegionRenderer.panelInk(black, white))
    }
}
