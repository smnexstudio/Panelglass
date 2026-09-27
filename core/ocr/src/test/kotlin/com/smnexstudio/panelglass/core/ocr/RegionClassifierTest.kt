package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import com.smnexstudio.panelglass.core.ocr.raster.RasterPixelSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Synthetic fixtures: a white bubble on busy art, a grey caption box, and text straight over noise. */
class RegionClassifierTest {
    private val classifier = RegionClassifier()

    private fun noisy(w: Int, h: Int, seed: Int = 1): Raster {
        val rnd = Random(seed)
        return Raster(w, h, IntArray(w * h) { val v = rnd.nextInt(256); Raster.rgb(v, rnd.nextInt(256), 255 - v) })
    }

    private fun fill(r: Raster, rect: IntRect, color: Int) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) r.set(x, y, color)
    }

    private fun glyphs(r: Raster, rect: IntRect, ink: Int) {
        var x = rect.left + 4
        while (x < rect.right - 4) { fill(r, IntRect(x, rect.top + 4, x + 3, rect.bottom - 4), ink); x += 8 }
    }

    private fun region(bbox: IntRect, text: String = "hello") =
        TextRegion(bbox = bbox, kind = RegionKind.FREE, text = text, lines = listOf(TextLine(bbox, text)))

    @Test
    fun whiteBubbleOnBusyArtIsEnclosed() {
        val page = noisy(400, 400)
        fill(page, IntRect(100, 100, 300, 220), Raster.rgb(255, 255, 255))
        val bbox = IntRect(130, 130, 270, 190)
        glyphs(page, bbox, Raster.rgb(0, 0, 0))
        val out = classifier.classify(RasterPixelSource(page), region(bbox))
        assertEquals(RegionKind.ENCLOSED, out.kind)
        assertTrue(Raster.lum(out.bgColor) > 240)
        assertTrue(Raster.lum(out.fgColor) < 40)
    }

    /** A spiky black burst with white lettering: the ring straddles its edge, the box interior says the truth. */
    @Test
    fun whiteLetteringOnABlackBurstReadsLightOnDark() {
        val page = noisy(400, 400)
        fill(page, IntRect(90, 90, 310, 230), Raster.rgb(0, 0, 0))
        val bbox = IntRect(100, 100, 300, 220) // the text box runs almost to the burst's edge
        glyphs(page, bbox, Raster.rgb(255, 255, 255))
        val out = classifier.classify(RasterPixelSource(page), region(bbox))
        assertTrue(Raster.lum(out.bgColor) < 40, "background should be the burst's black")
        assertTrue(Raster.lum(out.fgColor) > 215, "ink should be the white lettering")
    }

    @Test
    fun greyBoxIsCaption() {
        val page = noisy(400, 400)
        fill(page, IntRect(50, 50, 350, 150), Raster.rgb(110, 110, 110))
        val bbox = IntRect(80, 80, 320, 120)
        glyphs(page, bbox, Raster.rgb(255, 255, 255))
        val out = classifier.classify(RasterPixelSource(page), region(bbox))
        assertEquals(RegionKind.CAPTION, out.kind)
        assertTrue(Raster.lum(out.fgColor) > 200)
    }

    @Test
    fun textOverArtIsFree() {
        val page = noisy(400, 400)
        val bbox = IntRect(120, 120, 280, 170)
        val out = classifier.classify(RasterPixelSource(page), region(bbox))
        assertEquals(RegionKind.FREE, out.kind)
    }

    @Test
    fun bigKatakanaOverArtIsSfx() {
        val page = noisy(400, 400)
        val bbox = IntRect(60, 60, 340, 200)
        val out = classifier.classify(RasterPixelSource(page), region(bbox, "ドドドド"))
        assertEquals(RegionKind.SFX, out.kind)
    }

    @Test
    fun rotatedFreeTextIsInScene() {
        val page = noisy(400, 400)
        val bbox = IntRect(120, 120, 280, 170)
        val r = region(bbox).copy(angle = 20f)
        assertEquals(RegionKind.IN_SCENE, classifier.classify(RasterPixelSource(page), r).kind)
    }
}
