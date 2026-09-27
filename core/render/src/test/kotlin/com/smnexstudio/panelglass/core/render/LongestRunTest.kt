package com.smnexstudio.panelglass.core.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** [RegionRenderer.longestRun] with a width of one unit per character, so results read as character counts. */
class LongestRunTest {
    private fun run(text: String) = RegionRenderer.longestRun(text) { s, e -> (e - s).toFloat() }

    @Test
    fun englishIsItsLongestWordWithPunctuationAttached() {
        // Quotes and the full stop belong to the word: "Einrad." with its quotes is nine characters.
        assertEquals(9f, run("At the front of the feast, I said there was no problem, \"Einrad.\""))
        assertEquals(9f, run("Sales and selling of attack power is not permitted"))
    }

    @Test
    fun ellipsesAndQuotesDoNotJoinWords() {
        // "..." stands between spaces here; it is its own short run, not glued to its neighbours.
        assertEquals(9f, run("... What is the intention ..."))
    }

    @Test
    fun aHyphenIsABreakOpportunity() {
        assertEquals(4f, run("ba-dump"))
    }

    @Test
    fun japaneseAndChineseBreakBetweenAnyTwoCharacters() {
        assertEquals(1f, run("実は貴殿に売る気はないか"))
        assertEquals(1f, run("「バルカの魔法」"))
        // Latin inside Japanese is still one run.
        assertEquals(5f, run("これはHELLOです"))
    }

    @Test
    fun emptyAndBlankTextHaveNoRun() {
        assertEquals(0f, run(""))
        assertEquals(0f, run("   "))
    }
}
