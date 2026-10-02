package com.smnexstudio.panelglass.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.time.Duration

class ComicInfoTest {
    private val full = ComicInfo(
        series = "Blue Box", number = "12.5", volume = 2, title = "Match point", summary = "Line one\nLine two",
        writer = "Kouji Miura", penciller = "Kouji Miura", genre = "Romance, Sports", languageIso = "en", pageCount = 20,
        year = 2026, month = 9, day = 30, notes = "Translated with Panelglass", manga = "YesAndRightToLeft",
    )

    @Test fun roundTrip() = assertEquals(full, ComicInfo.parse(full.toXml()))

    @Test fun blankFieldsAreLeftOut() {
        val xml = ComicInfo(series = "A").toXml()
        assertTrue("<Series>A</Series>" in xml)
        assertFalse("<Title>" in xml)
        assertFalse("<Volume>" in xml)
    }

    @Test fun specialCharactersAreEscapedAndRestored() {
        val info = ComicInfo(series = "Tom & Jerry <\"vs\"> 'Spike'", summary = "a < b && c > d")
        val xml = info.toXml()
        assertTrue("Tom &amp; Jerry &lt;&quot;vs&quot;&gt; &apos;Spike&apos;" in xml)
        assertEquals(info, ComicInfo.parse(xml))
    }

    @Test fun controlCharactersAreDropped() =
        assertEquals("ab", ComicInfo.parse(ComicInfo(title = "a\u0001b").toXml()).title)

    @Test fun externalEntityIsNotResolved() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE ComicInfo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> <!ENTITY big "AAAA"> ]>
            <ComicInfo><Series>&xxe;</Series><Title>&big;</Title><Writer>&#72;&#x69;</Writer></ComicInfo>
        """.trimIndent()
        val info = ComicInfo.parse(xml)
        assertEquals("&xxe;", info.series)
        assertEquals("&big;", info.title)
        assertEquals("Hi", info.writer)
    }

    @Test fun readsRealWorldFiles() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!-- written by some tool -->
            <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <Series><![CDATA[Chainsaw <Man>]]></Series>
              <Number>97</Number>
              <Volume>x</Volume>
              <Summary/>
              <Pages><Page Image="0" Type="FrontCover"/><Page Image="1"/></Pages>
              <LanguageISO>ja</LanguageISO>
            </ComicInfo>
        """.trimIndent()
        val info = ComicInfo.parse(xml)
        assertEquals("Chainsaw <Man>", info.series)
        assertEquals("97", info.number)
        assertEquals(null, info.volume)
        assertEquals("ja", info.languageIso)
        assertEquals("", info.summary)
    }

    @Test fun garbageGivesEmpty() {
        assertEquals(ComicInfo(), ComicInfo.parse(""))
        assertEquals(ComicInfo(), ComicInfo.parse("not xml at all <Series>x"))
    }

    @Test fun firstCompleteElementWinsAndAttributesAndSpacesAreAllowed() {
        val xml = """
            <ComicInfo>
              <Title>
              <Series lang="en">One</Series ><Series>Two</Series>
              <Number>3</Number>
              <Summary/><Summary>Later</Summary>
              <SeriesGroup>Not the series</SeriesGroup>
            </ComicInfo>
        """.trimIndent()
        val info = ComicInfo.parse(xml)
        assertEquals("One", info.series)
        assertEquals("3", info.number)
        assertEquals("", info.title, "an unclosed element is no value")
        assertEquals("Later", info.summary, "a self-closing element leaves the field to a later one")
    }

    @Test fun anUnclosedCommentOrCdataDoesNotBreakTheRest() {
        assertEquals("A", ComicInfo.parse("<ComicInfo><Series>A</Series><Title><![CDATA[x</Title></ComicInfo>").series)
        assertEquals(ComicInfo(), ComicInfo.parse("<ComicInfo><!-- <Series>A</Series></ComicInfo>"))
    }

    /** Inputs that made the old lazy regexes rescan to the end from every start: quadratic, minutes at this size. */
    @Test fun hostileInputsAreParsedInLinearTime() {
        fun fill(unit: String) = unit.repeat(ComicInfo.MAX_BYTES / unit.length)
        val hostile = listOf(
            "<ComicInfo>" + fill("<Title>") + "</ComicInfo>",
            "<ComicInfo>" + fill("<a>") + "</ComicInfo>",
            "<ComicInfo>" + fill("<Title ") + "></ComicInfo>",
            "<ComicInfo>" + fill("</Title") + "</ComicInfo>",
            "<ComicInfo><Title>" + fill("<![CDATA[") + "</Title></ComicInfo>",
            fill("<!--x") + "<ComicInfo/>",
            fill("<!DOCTYPE [") + "<ComicInfo></ComicInfo>",
            fill("<ComicInfo") + "</ComicInfo>",
            "<ComicInfo>" + fill("</ComicInfo"),
            "<ComicInfo><Writer>" + fill("&aaaaaaa") + "</Writer></ComicInfo>",
        )
        for (xml in hostile) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), Executable { ComicInfo.parse(xml) }) { "${xml.take(24)}…" }
        }
    }
}
