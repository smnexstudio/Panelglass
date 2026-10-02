package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import android.graphics.Color
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.model.SfxMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.atan2

/** Sound effects on a real (Robolectric native) canvas: modes, transform, presets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SfxRendererTest {
    private val renderer = StudioRenderer()
    private val wide = listOf(Pt(100f, 100f), Pt(300f, 100f), Pt(300f, 160f), Pt(100f, 160f))
    private fun page() = Bitmap.createBitmap(400, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 180, 160)) }
    private fun sfx(poly: List<Pt> = wide, mode: SfxEditMode = SfxEditMode.OVERLAY): Bubble {
        val b = Bubble(id = 1, pageId = 1, order = 0, kind = RegionKind.SFX, polygon = poly, sourceText = "ドン", translatedText = "boom")
        return b.copy(sfx = SfxRenderer.initial(b, mode))
    }

    private fun changed(a: Bitmap, b: Bitmap): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) out += x to y
        return out
    }

    @Test fun anEffectStartsCentredOnItsBoxInTheSettingsMode() {
        val e = sfx(mode = SfxEditMode.REPLACE).sfx!!
        assertEquals(SfxEditMode.REPLACE, e.mode)
        assertEquals(200f, e.transform.cx, 0.01f)
        assertEquals(130f, e.transform.cy, 0.01f)
        assertEquals(0f, e.transform.rotationDeg, 0.01f)
        assertTrue(e.style.uppercase)
        // A tall effect starts turned, never on its side.
        val tall = sfx(listOf(Pt(10f, 10f), Pt(40f, 10f), Pt(40f, 200f), Pt(10f, 200f))).sfx!!
        assertTrue(abs(tall.transform.rotationDeg) in 45f..80f)
        assertEquals(SfxEditMode.KEEP, SfxRenderer.modeOf(SfxMode.SKIP))
        assertEquals(SfxEditMode.REPLACE, SfxRenderer.modeOf(SfxMode.REPLACE))
    }

    @Test fun keepDrawsNothingAndOverlayDrawsAroundTheCentre() {
        val clean = page()
        assertTrue(changed(clean, renderer.render(clean, listOf(sfx(mode = SfxEditMode.KEEP)), Lang.EN)).isEmpty())
        val drawn = changed(clean, renderer.render(clean, listOf(sfx()), Lang.EN))
        assertTrue("the effect was drawn", drawn.size > 100)
        val cx = drawn.map { it.first }.average()
        val cy = drawn.map { it.second }.average()
        assertTrue("centred near its box ($cx, $cy)", abs(cx - 200) < 25 && abs(cy - 130) < 25)
    }

    @Test fun moveAndRotateFollowTheTransform() {
        val clean = page()
        val b = sfx()
        val moved = b.copy(sfx = b.sfx!!.copy(transform = b.sfx!!.transform.copy(cx = 200f, cy = 250f)))
        val drawn = changed(clean, renderer.render(clean, listOf(moved), Lang.EN))
        assertTrue(abs(drawn.map { it.second }.average() - 250) < 25)
        val f = SfxRenderer.frame(b.copy(sfx = b.sfx!!.copy(transform = b.sfx!!.transform.copy(rotationDeg = 30f))), Lang.EN)!!
        // The box's top edge runs at the rotation's angle.
        val angle = Math.toDegrees(atan2((f[1].y - f[0].y).toDouble(), (f[1].x - f[0].x).toDouble()))
        assertEquals(30.0, angle, 0.5)
        assertEquals(200f, (f[0].x + f[2].x) / 2f, 0.5f)
    }

    @Test fun glossDrawsBesideTheEffectNotOnIt() {
        val clean = page()
        val drawn = changed(clean, renderer.render(clean, listOf(sfx(mode = SfxEditMode.GLOSS)), Lang.EN))
        assertTrue(drawn.isNotEmpty())
        assertTrue("below the box", drawn.all { it.second >= 155 })
        assertNull(SfxRenderer.frame(sfx(mode = SfxEditMode.GLOSS), Lang.EN))
    }

    @Test fun presetsSetTheLookAndKeepThePlace() {
        val b = sfx()
        val impact = SfxPreset.IMPACT.apply(b.sfx!!, b, Lang.EN)
        assertEquals(0xFFFFD21F.toInt(), impact.style.textColor)
        assertEquals(Color.BLACK, impact.style.outlineColor)
        assertEquals("IMPACT", impact.preset)
        assertEquals(b.sfx!!.transform.cx, impact.transform.cx, 0f)
        val horror = SfxPreset.HORROR.apply(impact, b, Lang.EN)
        assertEquals(Color.WHITE, horror.style.outerOutlineColor)
        assertTrue(horror.transform.skewX > 0f)
        // Back to a preset without one: the double outline goes.
        assertNull(SfxPreset.PUNCH.apply(horror, b, Lang.EN).style.outerOutlineColor)
    }
}
