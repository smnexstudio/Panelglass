package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import android.graphics.Color
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lettering on a real (Robolectric native) canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioRendererTest {
    private val renderer = StudioRenderer()
    private val poly = listOf(Pt(100f, 100f), Pt(300f, 100f), Pt(300f, 220f), Pt(100f, 220f))
    private fun page() = Bitmap.createBitmap(400, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 180, 160)) }
    private fun bubble(style: BubbleStyle = BubbleStyle(), text: String = "Wait! I'm coming too.") =
        Bubble(id = 1, pageId = 1, order = 0, kind = RegionKind.ENCLOSED, polygon = poly, translatedText = text, style = style)

    private fun differs(a: Bitmap, b: Bitmap, x: Int, y: Int) = a.getPixel(x, y) != b.getPixel(x, y)

    @Test fun aTransparentFillKeepsEveryPixelOutsideThePolygon() {
        val clean = page()
        val out = renderer.render(clean, listOf(bubble()), Lang.EN)
        var changedInside = 0
        for (y in 0 until 320) for (x in 0 until 400) {
            val inside = PolygonFit.contains(poly, x + 0.5f, y + 0.5f)
            if (!inside) assertEquals("($x, $y)", clean.getPixel(x, y), out.getPixel(x, y))
            else if (differs(clean, out, x, y)) changedInside++
        }
        assertTrue("the text was drawn", changedInside > 50)
    }

    @Test fun aBorderIsDrawnAlongTheShapeInItsColourAndNowhereElse() {
        val clean = page()
        val out = renderer.render(clean, listOf(bubble(BubbleStyle(borderWidthPx = 6f, borderColor = Color.RED), text = "x")), Lang.EN)
        // On the left edge, inside and outside the shape (the stroke is centred on it): red.
        for (x in listOf(98, 100, 102)) assertEquals("($x, 160)", Color.RED, out.getPixel(x, 160))
        // Well away from the edge: the art as it was.
        assertEquals(clean.getPixel(60, 160), out.getPixel(60, 160))
        val none = renderer.render(clean, listOf(bubble(text = "x")), Lang.EN)
        assertEquals("no border by default", clean.getPixel(98, 160), none.getPixel(98, 160))
    }

    @Test fun roundCornersLeaveTheCornerOutAndKeepTheEdges() {
        val clean = page()
        val blue = BubbleStyle(fillColor = Color.BLUE)
        val sharp = renderer.render(clean, listOf(bubble(blue, text = "x")), Lang.EN)
        val round = renderer.render(clean, listOf(bubble(blue.copy(cornerRoundness = 1f), text = "x")), Lang.EN)
        assertEquals("a sharp corner is filled", Color.BLUE, sharp.getPixel(102, 102))
        assertEquals("a round corner leaves the corner itself", clean.getPixel(102, 102), round.getPixel(102, 102))
        // The middle of an edge and the middle of the shape are filled either way.
        assertEquals(Color.BLUE, round.getPixel(200, 103))
        assertEquals(Color.BLUE, round.getPixel(103, 160))
    }

    @Test fun textInAnOvalBalloonShapedAsItsBoxStaysOnThePaper() {
        // Art all over, a white oval with a black outline inside the 200 × 120 box at (100, 100).
        val clean = page()
        val c = android.graphics.Canvas(clean)
        val oval = android.graphics.RectF(100f, 100f, 300f, 220f)
        c.drawOval(oval, android.graphics.Paint().apply { color = Color.WHITE })
        c.drawOval(oval, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f; color = Color.BLACK })
        val b = bubble(text = "Wait! I'm coming too, all the way to the very end of this long line.")
        val box = renderer.textBox(b, clean)!!
        fun onPaper(x: Float, y: Float): Boolean {
            val dx = (x - 200f) / 100f
            val dy = (y - 160f) / 60f
            return dx * dx + dy * dy < 1f
        }
        assertTrue("the text box's corners are inside the oval: $box", onPaper(box.left, box.top) && onPaper(box.right, box.bottom) && onPaper(box.right, box.top) && onPaper(box.left, box.bottom))
        // And nothing is drawn on the art in the box's corners, outside the oval.
        val out = renderer.render(clean, listOf(b), Lang.EN)
        for ((x, y) in listOf(104 to 104, 296 to 104, 104 to 216, 296 to 216)) assertEquals("($x, $y)", clean.getPixel(x, y), out.getPixel(x, y))
    }

    @Test fun aFilledBubbleUsesItsShapeAsDrawn() {
        val clean = page()
        val filled = bubble(BubbleStyle(fillColor = Color.WHITE)).copy(polygon = listOf(Pt(100f, 100f), Pt(300f, 100f), Pt(320f, 220f), Pt(100f, 220f)))
        assertEquals(PolygonFit.innerRect(filled.polygon), renderer.textBox(filled, clean))
    }

    @Test fun aColouredFillWithAlphaBlendsOverTheArt() {
        val clean = page()
        val style = BubbleStyle(fillColor = Color.argb(128, 0, 0, 255))
        val out = renderer.render(clean, listOf(bubble(style, text = "x")), Lang.EN)
        // A corner of the polygon, away from the text: half blue over the beige.
        val p = out.getPixel(104, 104)
        assertTrue(Color.blue(p) > 160 && Color.red(p) in 80..130)
    }

    @Test fun theTextStaysInsideTheInnerRectangle() {
        val clean = page()
        val out = renderer.render(clean, listOf(bubble(BubbleStyle(fillColor = Color.WHITE))), Lang.EN)
        val inner = PolygonFit.innerRect(poly)!!
        // Text is dark on the white fill: no dark pixel outside the inner rectangle (2 px of anti-aliasing allowed).
        for (y in 100 until 220) for (x in 100 until 300) {
            val dark = Color.red(out.getPixel(x, y)) < 100
            if (dark) assertTrue("($x, $y)", x >= inner.left - 2 && x <= inner.right + 2 && y >= inner.top - 2 && y <= inner.bottom + 2)
        }
    }

    @Test fun dismissedAndEmptyBubblesAreNotLettered() {
        val clean = page()
        val out = renderer.render(clean, listOf(bubble().copy(ignored = true), bubble(text = "  ")), Lang.EN)
        for (y in 0 until 320 step 7) for (x in 0 until 400 step 7) assertEquals(clean.getPixel(x, y), out.getPixel(x, y))
    }

    @Test fun aFixedSizeIsUsedAsGiven() {
        val clean = page()
        val small = renderer.render(clean, listOf(bubble(BubbleStyle(sizePx = 10f, fillColor = Color.WHITE), "Hi")), Lang.EN)
        val big = renderer.render(clean, listOf(bubble(BubbleStyle(sizePx = 60f, fillColor = Color.WHITE), "Hi")), Lang.EN)
        fun ink(b: Bitmap) = (100 until 220).sumOf { y -> (100 until 300).count { x -> Color.red(b.getPixel(x, y)) < 100 } }
        assertTrue(ink(big) > ink(small) * 4)
    }
}
