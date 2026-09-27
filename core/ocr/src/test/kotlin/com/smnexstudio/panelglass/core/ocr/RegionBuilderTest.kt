package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionBuilderTest {
    private val builder = RegionBuilder()

    /** Columns too far apart for proximity clustering still form one sentence when the detector put them in one bubble. */
    @Test
    fun linesInsideOneTextBoxBecomeOneRegionReadRightToLeft() {
        val lines = listOf(
            TextLine(IntRect(300, 100, 330, 260), "こんにちは", vertical = true),
            TextLine(IntRect(200, 100, 230, 230), "元気です", vertical = true),   // gap of 70 px: the clusterer alone would split here
            TextLine(IntRect(100, 100, 130, 140), "か", vertical = true),
        )
        val box = TextBox(IntRect(80, 80, 350, 280), BoxKind.TEXT_BUBBLE, 0.9f)
        val out = builder.build(PageDetection(lines, listOf(box)), Lang.JA)
        assertEquals(1, out.size)
        assertEquals("こんにちは元気ですか", out[0].region.text)
        assertEquals(BoxKind.TEXT_BUBBLE, out[0].seed)
        // Region bounds are the lines' union, not the looser detector box.
        assertEquals(IntRect(100, 100, 330, 260), out[0].region.bbox)
    }

    @Test
    fun textBoxesClaimLinesBeforeTheBalloonAroundThem() {
        val inner = TextLine(IntRect(120, 120, 180, 140), "Hi")
        val balloon = TextBox(IntRect(100, 100, 300, 300), BoxKind.BUBBLE, 0.8f)
        val text = TextBox(IntRect(110, 110, 190, 150), BoxKind.TEXT_BUBBLE, 0.9f)
        val out = builder.build(PageDetection(listOf(inner), listOf(balloon, text)), Lang.EN)
        assertEquals(1, out.size)
        assertEquals("Hi", out[0].region.text)
    }

    @Test
    fun freeTextIsGroupedButLeftForThePixelClassifier() {
        val lines = listOf(TextLine(IntRect(10, 10, 60, 30), "ドド"), TextLine(IntRect(10, 34, 60, 54), "ン"))
        val box = TextBox(IntRect(0, 0, 70, 60), BoxKind.TEXT_FREE, 0.7f)
        val out = builder.build(PageDetection(lines, listOf(box)), Lang.JA)
        assertEquals(1, out.size)
        assertNull(out[0].seed)
        assertEquals("ドドン", out[0].region.text)
    }

    /** A logo read as dots used to join the caption under it and turn the pair into one page-wide free-text region. */
    @Test
    fun linesWithoutALetterAreNotText() {
        val lines = listOf(
            TextLine(IntRect(7, 1376, 1121, 1805), "......."),
            TextLine(IntRect(21, 1686, 665, 1794), "その声は福音か、凶報か"),
        )
        val box = TextBox(IntRect(0, 1370, 1130, 1810), BoxKind.TEXT_FREE, 0.7f)
        val out = builder.build(PageDetection(lines, listOf(box)), Lang.JA)
        assertEquals(1, out.size)
        assertEquals("その声は福音か、凶報か", out[0].region.text)
        assertEquals(IntRect(21, 1686, 665, 1794), out[0].region.bbox)
    }

    @Test
    fun linesOutsideEveryBoxFallBackToProximityClustering() {
        val lines = listOf(
            TextLine(IntRect(120, 200, 280, 224), "I never asked"),
            TextLine(IntRect(140, 228, 260, 252), "for this."),
            TextLine(IntRect(500, 500, 540, 520), "Far"),
        )
        val box = TextBox(IntRect(0, 0, 50, 50), BoxKind.TEXT_BUBBLE, 0.9f)   // contains nothing
        val out = builder.build(PageDetection(lines, listOf(box)), Lang.EN)
        assertEquals(2, out.size)
        assertTrue(out.all { it.seed == null })
        assertTrue(out.any { it.region.text == "I never asked for this." })
    }

    @Test
    fun seedOverridesKindButKeepsColours() {
        val classified = TextRegion(IntRect(0, 0, 10, 10), kind = RegionKind.FREE, text = "x", bgColor = 0x11223344, fgColor = 0x55667788)
        val seeded = RegionBuilder.seeded(classified, BoxKind.TEXT_BUBBLE)
        assertEquals(RegionKind.ENCLOSED, seeded.kind)
        assertEquals(0x11223344, seeded.bgColor)
        assertEquals(classified, RegionBuilder.seeded(classified, null))
    }
}
