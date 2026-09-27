package com.smnexstudio.panelglass.core.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UprightTiltTest {
    @Test
    fun aVerticalColumnIsUpright() {
        assertEquals(0f, uprightTilt(90f))
        assertEquals(0f, uprightTilt(-90f))
        assertEquals(3f, uprightTilt(93f), 1e-4f)
        assertEquals(-4f, uprightTilt(-94f), 1e-4f)
    }

    @Test
    fun aTiltedLineKeepsItsTilt() {
        assertEquals(12f, uprightTilt(12f))
        assertEquals(-20f, uprightTilt(-20f))
    }

    @Test
    fun upsideDownTextIsUpright() {
        assertEquals(0f, uprightTilt(180f))
        assertEquals(5f, uprightTilt(185f), 1e-4f)
    }
}
