package com.smnexstudio.panelglass.feature.studio.cover

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/** The 2 : 3 window a cover is cut from, and how the drag bias slides it. */
class CoverCropTest {
    @Test fun aWidePictureIsCutAcrossAndSlidesSideways() {
        // 1200 × 900: the window is 600 × 900, with 600 px of room to the side.
        assertArrayEquals(intArrayOf(0, 0, 600, 900), CoverCrop.window(1200, 900, -1f, 0f))
        assertArrayEquals(intArrayOf(300, 0, 900, 900), CoverCrop.window(1200, 900, 0f, 0f))
        assertArrayEquals(intArrayOf(600, 0, 1200, 900), CoverCrop.window(1200, 900, 1f, 1f))
    }

    @Test fun aLongStripIsCutDownwardsAndStartsAtTheTopWhenAsked() {
        // 800 × 4000: the window is 800 × 1200; a page's cover starts at its top.
        assertArrayEquals(intArrayOf(0, 0, 800, 1200), CoverCrop.window(800, 4000, 0f, -1f))
        assertArrayEquals(intArrayOf(0, 1400, 800, 2600), CoverCrop.window(800, 4000, 0f, 0f))
        assertArrayEquals(intArrayOf(0, 2800, 800, 4000), CoverCrop.window(800, 4000, 0f, 1f))
    }

    @Test fun anExactTwoByThreePictureIsKeptWholeAndBiasOutOfRangeIsClamped() {
        assertArrayEquals(intArrayOf(0, 0, 600, 900), CoverCrop.window(600, 900, 5f, -5f))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), CoverCrop.window(0, 900, 0f, 0f))
    }
}
