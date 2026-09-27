package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.PixelSource
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanelCutterTest {

    private fun createSyntheticPageWithGutters(width: Int = 800, height: Int = 1200): Raster {
        // Create 2x2 panel layout:
        // Top panels: [50, 50, 380, 550], [420, 50, 750, 550]
        // Gutter H: 550..650 (100px)
        // Gutter V: 380..420 (40px)
        // Bottom panels: [50, 650, 380, 1150], [420, 650, 750, 1150]
        val white = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF
        val black = (0xFF shl 24)
        val argb = IntArray(width * height) { white }
        val raster = Raster(width, height, argb)

        fun fillDark(rect: IntRect) {
            for (y in rect.top until rect.bottom) {
                for (x in rect.left until rect.right) {
                    raster.set(x, y, black)
                }
            }
        }

        fillDark(IntRect(80, 80, 350, 520))
        fillDark(IntRect(450, 80, 720, 520))
        fillDark(IntRect(80, 680, 350, 1120))
        fillDark(IntRect(450, 680, 720, 1120))

        return raster
    }

    @Test
    fun `detects synthetic panels and respects RTL reading order`() {
        val raster = createSyntheticPageWithGutters(800, 1200)
        val pixelSource = object : PixelSource {
            override val width = raster.width
            override val height = raster.height
            override fun crop(rect: IntRect): Raster {
                val r = rect.clamp(width, height)
                val out = IntArray(r.width * r.height)
                for (y in 0 until r.height) {
                    System.arraycopy(raster.argb, (r.top + y) * width + r.left, out, y * r.width, r.width)
                }
                return Raster(r.width, r.height, out)
            }
        }

        val cutter = PanelCutter()
        val panelsRtl = cutter.segment(pixelSource, rtl = true)

        assertTrue(panelsRtl.size >= 2, "Should segment into multiple panels, got ${panelsRtl.size}")

        // For RTL manga: top-right panel should come before top-left panel
        val topRight = panelsRtl.find { it.bounds.centerX > 400 && it.bounds.centerY < 600 }
        val topLeft = panelsRtl.find { it.bounds.centerX < 400 && it.bounds.centerY < 600 }
        if (topRight != null && topLeft != null) {
            assertTrue(topRight.index < topLeft.index, "Top-right panel should precede top-left panel in RTL reading order")
        }
    }

    @Test
    fun `orders regions according to panel order`() {
        val panels = listOf(
            PanelCutter.Panel(IntRect(400, 0, 800, 500), index = 0), // Top Right (Panel 0 in RTL)
            PanelCutter.Panel(IntRect(0, 0, 400, 500), index = 1),   // Top Left (Panel 1 in RTL)
            PanelCutter.Panel(IntRect(0, 500, 800, 1000), index = 2) // Bottom Full (Panel 2)
        )

        fun regionAt(x: Int, y: Int, text: String) = TextRegion(
            bbox = IntRect(x, y, x + 50, y + 50),
            kind = RegionKind.ENCLOSED,
            angle = 0f,
            vertical = false,
            text = text,
            lines = listOf(TextLine(IntRect(x, y, x + 50, y + 50), text, false, 0f, 1f))
        )

        val r1 = regionAt(100, 100, "Top Left Bubble")
        val r2 = regionAt(600, 100, "Top Right Bubble")
        val r3 = regionAt(200, 700, "Bottom Bubble")

        val cutter = PanelCutter()
        val ordered = cutter.orderRegions(listOf(r1, r2, r3), panels, rtl = true)

        assertEquals("Top Right Bubble", ordered[0].text)
        assertEquals("Top Left Bubble", ordered[1].text)
        assertEquals("Bottom Bubble", ordered[2].text)
    }
}
