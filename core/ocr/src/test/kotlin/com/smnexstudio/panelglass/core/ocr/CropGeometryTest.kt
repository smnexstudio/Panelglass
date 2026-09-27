package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CropGeometryTest {
    // Upright crop 100 wide × 300 tall; a column occupies x 40..60, y 20..280.
    private val column = IntRect(40, 20, 60, 280)

    /** Rotating 90° clockwise sends (x, y) to (H − y, x): the column becomes a row near the top. */
    @Test
    fun clockwiseRowMapsBackToTheColumn() {
        val rotated = IntRect(300 - 280, 40, 300 - 20, 60)  // left = H − bottom, top = left, right = H − top, bottom = right
        assertEquals(column, RotationGeometry.fromClockwise(rotated, 100, 300))
    }

    /** Rotating 90° counter-clockwise sends (x, y) to (y, W − x). */
    @Test
    fun counterClockwiseRowMapsBackToTheColumn() {
        val rotated = IntRect(20, 100 - 60, 280, 100 - 40)  // left = top, top = W − right, right = bottom, bottom = W − left
        assertEquals(column, RotationGeometry.fromCounterClockwise(rotated, 100, 300))
    }

    @Test
    fun cornersRoundTrip() {
        val topLeft = IntRect(0, 0, 10, 10)
        // Clockwise: the upright top-left corner lands at the rotated top-right.
        assertEquals(topLeft, RotationGeometry.fromClockwise(IntRect(290, 0, 300, 10), 100, 300))
        // Counter-clockwise: it lands at the rotated bottom-left.
        assertEquals(topLeft, RotationGeometry.fromCounterClockwise(IntRect(0, 90, 10, 100), 100, 300))
    }

    @Test
    fun smallCropsGrowUpToThreeTimesLargeOnesShrinkToTheLongSideCap() {
        assertEquals(3f, CropScalePlan.scaleFor(200, 300))            // 200 → 600, capped by 3×
        assertEquals(2f, CropScalePlan.scaleFor(600, 900))            // 600 → 1200 exactly
        assertEquals(1f, CropScalePlan.scaleFor(1300, 2000))          // already large enough
        assertEquals(0.75f, CropScalePlan.scaleFor(1000, 4000))       // 4000 → 3000
        // Growing the short side may not push the long side past the cap.
        assertEquals(1.5f, CropScalePlan.scaleFor(400, 2000))
    }
}
