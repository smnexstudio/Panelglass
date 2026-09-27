package com.smnexstudio.panelglass.core.ocr

import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionClustererTest {
    private val clusterer = RegionClusterer()

    /** Vertical Japanese arrives as one-character-wide columns; they must merge right-to-left into one string. */
    @Test
    fun verticalColumnsMergeRightToLeft() {
        // Three columns of a bubble, laid out right → left: 「こんにちは」「元気です」「か」
        val lines = listOf(
            TextLine(IntRect(300, 100, 330, 260), "こんにちは", vertical = true),
            TextLine(IntRect(262, 100, 292, 230), "元気です", vertical = true),
            TextLine(IntRect(224, 100, 254, 140), "か", vertical = true),
        )
        val regions = clusterer.cluster(lines, Lang.JA)
        assertEquals(1, regions.size)
        assertEquals("こんにちは元気ですか", regions[0].text)
        assertTrue(regions[0].vertical)
        assertEquals(IntRect(224, 100, 330, 260), regions[0].bbox)
    }

    /** A caption read by two passes arrives twice with the same boxes; Korean lines join with a space. */
    @Test
    fun koreanCaptionReadTwiceIsKeptOnceWithWordSpaces() {
        val a = TextLine(IntRect(159, 242, 943, 357), "예로부터 중원은")
        val b = TextLine(IntRect(84, 386, 1001, 511), "싸움이 끊이지 않는")
        val c = TextLine(IntRect(315, 521, 756, 655), "곳이었다.")
        val regions = clusterer.cluster(listOf(a, a, b, b, c, c), Lang.KO)
        assertEquals(1, regions.size)
        assertEquals("예로부터 중원은 싸움이 끊이지 않는 곳이었다.", regions[0].text)
        assertEquals(3, regions[0].lines.size)
        assertEquals("예로부터 중원은 싸움이 끊이지 않는 곳이었다.", clusterer.build(listOf(a, a, b, b, c, c), Lang.KO).text)
    }

    @Test
    fun horizontalCenteredLinesMergeTopToBottom() {
        val lines = listOf(
            TextLine(IntRect(120, 200, 280, 224), "I never asked"),
            TextLine(IntRect(140, 228, 260, 252), "for this."),
        )
        val regions = clusterer.cluster(lines, Lang.EN)
        assertEquals(1, regions.size)
        assertEquals("I never asked for this.", regions[0].text)
    }

    @Test
    fun distantBubblesStaySeparate() {
        val lines = listOf(
            TextLine(IntRect(50, 50, 200, 80), "Left bubble"),
            TextLine(IntRect(600, 700, 760, 730), "Far away"),
        )
        assertEquals(2, clusterer.cluster(lines, Lang.EN).size)
    }

    @Test
    fun fontSizeMismatchPreventsMerge() {
        val lines = listOf(
            TextLine(IntRect(100, 100, 300, 120), "small caption"),
            TextLine(IntRect(100, 124, 300, 200), "BIG"),
        )
        assertEquals(2, clusterer.cluster(lines, Lang.EN).size)
    }

    @Test
    fun verticalAndHorizontalNeverMerge() {
        val lines = listOf(
            TextLine(IntRect(100, 100, 130, 300), "縦書き", vertical = true),
            TextLine(IntRect(100, 100, 300, 130), "横書き", vertical = false),
        )
        assertEquals(2, clusterer.cluster(lines, Lang.JA).size)
    }

    @Test
    fun japaneseReadingOrderIsTopRightFirst() {
        val lines = listOf(
            TextLine(IntRect(50, 50, 80, 200), "左", vertical = true),
            TextLine(IntRect(500, 50, 530, 200), "右", vertical = true),
            TextLine(IntRect(500, 600, 530, 700), "下", vertical = true),
        )
        val regions = clusterer.cluster(lines, Lang.JA)
        assertEquals(listOf("右", "左", "下"), regions.map { it.text })
    }

    @Test
    fun englishReadingOrderIsTopLeftFirst() {
        val lines = listOf(
            TextLine(IntRect(500, 50, 700, 80), "right"),
            TextLine(IntRect(50, 50, 200, 80), "left"),
            TextLine(IntRect(50, 600, 200, 630), "bottom"),
        )
        assertEquals(listOf("left", "right", "bottom"), clusterer.cluster(lines, Lang.EN).map { it.text })
    }
}
