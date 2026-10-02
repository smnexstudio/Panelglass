package com.smnexstudio.panelglass.core.render

import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.Inpainter
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class PageCleanerTest {
    private val cleaner = PageCleaner(TextEraser(), Inpainter.NONE)
    private val white = Raster.rgb(255, 255, 255)
    private val black = Raster.rgb(0, 0, 0)

    /** A 300 × 300 page of hatched art with a white balloon (60..240) holding black "glyph" bars (100..200). */
    private fun page(): Raster {
        val rnd = Random(3)
        val r = Raster(300, 300, IntArray(300 * 300) { val v = 90 + rnd.nextInt(60); Raster.rgb(v, v, v) })
        for (y in 60 until 240) for (x in 60 until 240) r.set(x, y, white)
        // The balloon's outline.
        for (i in 60 until 240) { r.set(i, 60, black); r.set(i, 239, black); r.set(60, i, black); r.set(239, i, black) }
        var x = 104
        while (x < 196) { for (y in 110 until 190) for (k in 0 until 4) r.set(x + k, y, black); x += 12 }
        return r
    }

    private val textBox = IntRect(100, 105, 200, 195)
    private val region = TextRegion(
        bbox = textBox, kind = RegionKind.ENCLOSED, text = "テスト", container = IntRect(60, 60, 240, 240),
        bgColor = white, fgColor = black, lines = listOf(TextLine(textBox, "テスト", fontSizePx = 30f)),
    )
    private fun rect(l: Float, t: Float, r: Float, b: Float) = listOf(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))
    private fun bubble(poly: List<Pt> = rect(70f, 70f, 230f, 230f), ignored: Boolean = false, kind: RegionKind = RegionKind.ENCLOSED) =
        Bubble(id = 1, pageId = 1, order = 0, kind = kind, polygon = poly, sourceText = "テスト", translatedText = "Test", region = region.copy(kind = kind), ignored = ignored)

    private fun inkLeft(r: Raster, box: IntRect) = (box.top until box.bottom).sumOf { y -> (box.left until box.right).count { x -> Raster.lum(r[x, y]) < 80 } }

    @Test fun aFlatBalloonIsFilledWithItsOwnColour() {
        val original = page()
        val out = cleaner.clean(original, listOf(bubble()), emptyList())
        assertEquals(0, inkLeft(out, textBox))
        assertTrue(inkLeft(original, textBox) > 0)
    }

    @Test fun pixelsOutsideTheBubbleNeverChange() {
        val original = page()
        // A polygon covering only the left half of the text: the right half's glyphs stay.
        val out = cleaner.clean(original, listOf(bubble(rect(70f, 70f, 150f, 230f))), emptyList())
        for (y in 0 until 300) for (x in 0 until 300) {
            if (!PolygonFit.contains(rect(70f, 70f, 150f, 230f), x + 0.5f, y + 0.5f)) assertEquals(original[x, y], out[x, y], "($x, $y)")
        }
        assertTrue(inkLeft(out, IntRect(152, 105, 200, 195)) > 0)
        assertEquals(0, inkLeft(out, IntRect(100, 105, 148, 195)))
    }

    @Test fun dismissedBubblesAndUnreplacedSoundEffectsAreKept() {
        val original = page()
        assertTrue(cleaner.clean(original, listOf(bubble(ignored = true)), emptyList()).argb.contentEquals(original.argb))
        assertTrue(cleaner.clean(original, listOf(bubble(kind = RegionKind.SFX)), emptyList()).argb.contentEquals(original.argb))
    }

    @Test fun aCleanStrokeRemovesWhatItCoversOnly() {
        val original = page()
        // A black blob on the hatched art, at (20..40, 260..280), brushed out.
        for (y in 260 until 280) for (x in 20 until 40) original.set(x, y, black)
        val stroke = BrushStroke(BrushMode.CLEAN, 14f, listOf(Pt(25f, 270f), Pt(35f, 270f)))
        val out = cleaner.clean(original, emptyList(), listOf(stroke))
        assertEquals(0, inkLeft(out, IntRect(22, 262, 38, 278)))
        val mask = StrokeMask.of(stroke, 300, 300)!!
        for (y in 0 until 300) for (x in 0 until 300) {
            val inStroke = x in mask.bounds.left until mask.bounds.right && y in mask.bounds.top until mask.bounds.bottom &&
                mask[x - mask.bounds.left, y - mask.bounds.top]
            if (!inStroke) assertEquals(original[x, y], out[x, y])
        }
    }

    @Test fun aRestoreStrokeBringsTheOriginalBack() {
        val original = page()
        val cleaned = cleaner.clean(original, listOf(bubble()), emptyList())
        assertEquals(0, inkLeft(cleaned, IntRect(104, 110, 120, 190)))
        val restore = BrushStroke(BrushMode.RESTORE, 10f, listOf(Pt(110f, 130f), Pt(110f, 170f)))
        val out = cleaner.clean(original, listOf(bubble()), listOf(restore))
        assertTrue(inkLeft(out, IntRect(104, 125, 116, 175)) > 0)
        // Away from the stroke, the bubble stays clean.
        assertEquals(0, inkLeft(out, IntRect(150, 110, 200, 190)))
    }

    /** Paints every masked pixel pure red, so a test sees exactly what was handed to it. */
    private inner class RedInpainter : Inpainter {
        var calls = 0
        override val available = true
        override fun inpaint(crop: Raster, mask: com.smnexstudio.panelglass.core.model.GlyphMask): Boolean {
            calls++
            for (y in 0 until crop.height) for (x in 0 until crop.width) if (mask[x, y]) crop.set(x, y, red)
            return true
        }
    }

    private val red = Raster.rgb(255, 0, 0)

    @Test fun withLamaStrokesAndTextOverArtGoToItButAPlainBalloonKeepsItsFlatFill() {
        val lama = RedInpainter()
        val withLama = PageCleaner(TextEraser(), lama)
        val original = page()
        // A plain balloon: the flood fill is exact, LaMa is not asked.
        val balloon = withLama.clean(original, listOf(bubble()), emptyList())
        assertEquals(0, lama.calls)
        assertEquals(0, inkLeft(balloon, textBox))
        // A brush stroke: LaMa fills it, and nothing outside the stroke changes.
        val stroke = BrushStroke(BrushMode.CLEAN, 10f, listOf(Pt(25f, 270f), Pt(35f, 270f)))
        val brushed = withLama.clean(original, emptyList(), listOf(stroke))
        assertEquals(1, lama.calls)
        assertEquals(red, brushed[30, 270])
        assertEquals(original[5, 5], brushed[5, 5])
        // A replaced sound effect over art: LaMa fills inside its polygon only.
        val sfxPoly = rect(70f, 70f, 230f, 230f)
        val effect = bubble(sfxPoly, kind = RegionKind.SFX).copy(
            sfx = com.smnexstudio.panelglass.core.model.SfxEdit(
                mode = com.smnexstudio.panelglass.core.model.SfxEditMode.REPLACE,
                transform = com.smnexstudio.panelglass.core.model.SfxTransform(150f, 150f),
            ),
        )
        val replaced = withLama.clean(original, listOf(effect), emptyList())
        assertTrue(lama.calls >= 2)
        for (y in 0 until 300) for (x in 0 until 300) {
            if (!PolygonFit.contains(sfxPoly, x + 0.5f, y + 0.5f)) assertEquals(original[x, y], replaced[x, y], "($x, $y)")
        }
        assertTrue((105 until 195).any { y -> (100 until 200).any { x -> replaced[x, y] == red } }, "the text was handed to LaMa")
    }

    @Test fun theOriginalIsNeverModified() {
        val original = page()
        val copy = original.argb.copyOf()
        cleaner.clean(original, listOf(bubble()), listOf(BrushStroke(BrushMode.CLEAN, 8f, listOf(Pt(20f, 20f)))))
        assertTrue(original.argb.contentEquals(copy))
        assertFalse(copy.isEmpty())
    }
}
