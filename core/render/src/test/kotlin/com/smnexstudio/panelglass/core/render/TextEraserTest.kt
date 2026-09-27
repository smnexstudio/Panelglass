package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TextEraserTest {
    private val eraser = TextEraser()

    private fun fill(r: Raster, rect: IntRect, color: Int) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) r.set(x, y, color)
    }

    private fun glyphs(r: Raster, rect: IntRect, ink: Int) {
        var x = rect.left + 4
        while (x < rect.right - 4) { fill(r, IntRect(x, rect.top + 4, x + 3, rect.bottom - 4), ink); x += 8 }
    }

    private fun noisy(w: Int, h: Int, seed: Int = 7): Raster {
        val rnd = Random(seed)
        return Raster(w, h, IntArray(w * h) { Raster.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) })
    }

    @Test
    fun enclosedBubbleGlyphsAreFilledWithBackground() {
        val white = Raster.rgb(255, 255, 255)
        val crop = Raster.solid(200, 100, white)
        val bbox = IntRect(30, 30, 170, 70)
        glyphs(crop, bbox, Raster.rgb(0, 0, 0))
        val region = TextRegion(bbox = bbox, kind = RegionKind.ENCLOSED, text = "x", bgColor = white, fgColor = Raster.rgb(0, 0, 0))
        val result = eraser.erase(crop, IntRect(0, 0, 200, 100), region)
        assertTrue(result.erased)
        assertEquals(EraseResult.Method.CONTAINER_FILL, result.method)
        var dark = 0
        for (y in bbox.top until bbox.bottom) for (x in bbox.left until bbox.right) if (Raster.lum(result.raster[x, y]) < 128) dark++
        assertEquals(0, dark)
    }

    /**
     * The failure seen on a real page: the text box fills the balloon, so a seed ring outside it lands on the
     * outline and the screentone beyond, the flood fills the outside, and the glyphs are never holes. The erase
     * must either remove the ink or say it did not — never report success over intact lettering.
     */
    @Test
    fun textBoxAsLargeAsTheBalloonStillErasesTheInk() {
        val white = Raster.rgb(255, 255, 255)
        val ink = Raster.rgb(0, 0, 0)
        val crop = noisy(220, 140, seed = 3) // busy art outside the balloon
        val balloon = IntRect(20, 14, 200, 126)
        fill(crop, balloon, ink)              // 4 px outline …
        fill(crop, balloon.inflate(-4), white) // … around a white interior
        val bbox = balloon.inflate(-6)         // the text box reaches to within 2 px of the outline
        glyphs(crop, bbox, ink)
        val region = TextRegion(bbox = bbox, kind = RegionKind.ENCLOSED, text = "x", bgColor = Raster.rgb(224, 224, 224), fgColor = ink)
        val result = eraser.erase(crop, IntRect(0, 0, 220, 140), region)
        var dark = 0
        for (y in bbox.top + 4 until bbox.bottom - 4) for (x in bbox.left + 4 until bbox.right - 4) if (Raster.lum(result.raster[x, y]) < 128) dark++
        if (result.erased) assertEquals(0, dark, "erased=true must mean the lettering is gone (${result.method})")
        else assertTrue(true) // the renderer will paint the mask instead
        assertTrue(result.erased, "interior seeds should let the container fill succeed here (${result.method})")
    }

    @Test
    fun freeTextWithBrightHaloUsesHaloFill() {
        val crop = noisy(200, 100)
        val white = Raster.rgb(250, 250, 250)
        fill(crop, IntRect(20, 20, 180, 80), white)          // the artist's flat halo
        val bbox = IntRect(40, 35, 160, 65)
        glyphs(crop, bbox, Raster.rgb(10, 10, 10))
        val region = TextRegion(bbox = bbox, kind = RegionKind.FREE, text = "x", bgColor = white, fgColor = Raster.rgb(10, 10, 10))
        val result = eraser.erase(crop, IntRect(0, 0, 200, 100), region)
        assertTrue(result.erased)
        assertEquals(EraseResult.Method.HALO_FILL, result.method)
        var dark = 0
        for (y in bbox.top until bbox.bottom) for (x in bbox.left until bbox.right) if (Raster.lum(result.raster[x, y]) < 128) dark++
        assertEquals(0, dark)
    }

    /** The gate is mandatory: on high-frequency art the ray inpainter smears, and the erase must be rejected. */
    @Test
    fun smearedInpaintingOverBusyArtIsRejected() {
        val crop = noisy(200, 100)
        val bbox = IntRect(40, 35, 160, 65)
        glyphs(crop, bbox, Raster.rgb(0, 0, 0))
        val region = TextRegion(bbox = bbox, kind = RegionKind.FREE, text = "x", bgColor = Raster.rgb(128, 128, 128), fgColor = Raster.rgb(0, 0, 0))
        val result = eraser.erase(crop, IntRect(0, 0, 200, 100), region)
        assertFalse(result.erased)
        assertEquals(EraseResult.Method.REJECTED_SMEAR, result.method)
        // Untouched pixels come back so the renderer can overlay with an outline instead.
        assertTrue(result.raster.argb.contentEquals(crop.argb))
    }

    @Test
    fun inpaintingOnSmoothGradientIsAccepted() {
        val crop = Raster(200, 100, IntArray(200 * 100) { i -> val x = i % 200; Raster.rgb(60 + x / 2, 80 + x / 3, 120 + x / 4) })
        val bbox = IntRect(40, 35, 160, 65)
        glyphs(crop, bbox, Raster.rgb(0, 0, 0))
        val region = TextRegion(bbox = bbox, kind = RegionKind.FREE, text = "x", bgColor = Raster.rgb(110, 110, 140), fgColor = Raster.rgb(0, 0, 0))
        val result = eraser.erase(crop, IntRect(0, 0, 200, 100), region)
        assertTrue(result.erased)
        assertEquals(EraseResult.Method.INPAINT, result.method)
        var dark = 0
        for (y in bbox.top until bbox.bottom) for (x in bbox.left until bbox.right) if (Raster.lum(result.raster[x, y]) < 40) dark++
        assertEquals(0, dark)
    }

    @Test
    fun maskGeometryRecoversRotation() {
        val m = com.smnexstudio.panelglass.core.model.GlyphMask.empty(0, 0, 200, 200)
        // A thick diagonal bar at 45°.
        for (t in 20 until 180) for (d in -6..6) { val x = t; val y = t + d; if (y in 0 until 200) m.set(x, y, true) }
        val g = MaskGeometry.of(m)!!
        assertTrue(kotlin.math.abs(g.angleDeg - 45f) < 3f, "angle was ${g.angleDeg}")
        assertTrue(g.length > g.thickness * 5)
        assertFalse(g.isCurved)
    }
}
