package com.smnexstudio.panelglass.core.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaOcrRepeatTest {
    private val sentence = "そう構えずともリードの魔法で事務効率が上がったと報告を受けているよ"

    @Test
    fun aLoopedSentenceCollapsesToOne() {
        assertEquals(sentence, MangaOcrRecognizer.collapseRepeats(sentence + sentence))
        assertEquals(sentence, MangaOcrRecognizer.collapseRepeats(sentence + sentence + sentence))
    }

    @Test
    fun aPartialRepeatCutByTheTokenCapIsDroppedOnlyWhenTruncated() {
        val cut = sentence + sentence.take(9)
        assertEquals(sentence, MangaOcrRecognizer.collapseRepeats(cut, truncated = true))
        assertEquals(cut, MangaOcrRecognizer.collapseRepeats(cut, truncated = false))
    }

    @Test
    fun shortRealRepeatsAreKept() {
        assertEquals("ハハハッ", MangaOcrRecognizer.collapseRepeats("ハハハッ"))
        assertEquals("そうそう、それだ", MangaOcrRecognizer.collapseRepeats("そうそう、それだ"))
        assertEquals("まさかまさか", MangaOcrRecognizer.collapseRepeats("まさかまさか"))
    }
}
