package com.smnexstudio.panelglass.feature.studio.export

import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.MangaStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExportNamesTest {
    @Test fun sanitize() {
        assertEquals("A B C", ExportNames.sanitize("A/B\\C", "x"))
        assertEquals("What", ExportNames.sanitize("What?*:\"<>|", "x"))
        assertEquals("Name", ExportNames.sanitize("  ..Name.. ", "x"))
        assertEquals("x", ExportNames.sanitize("...", "x"))
        assertEquals("_CON", ExportNames.sanitize("CON", "x"))
        assertEquals("_com1.txt", ExportNames.sanitize("com1.txt", "x"))
        assertEquals("line one", ExportNames.sanitize("line\none", "x"))
        assertEquals(ExportNames.MAX_NAME, ExportNames.sanitize("a".repeat(200), "x").length)
        // A title in any script is kept as it is.
        assertEquals("星屑ベーカリー", ExportNames.sanitize("星屑ベーカリー", "x"))
    }

    @Test fun chapterFolders() {
        val c = Chapter(mangaId = 1)
        assertEquals("Ch 012 - Bread", ExportNames.chapterFolder(c.copy(number = 12f, title = "Bread"), 1))
        assertEquals("Ch 012.5", ExportNames.chapterFolder(c.copy(number = 12.5f), 1))
        assertEquals("Ch 1234", ExportNames.chapterFolder(c.copy(number = 1234f), 1))
        assertEquals("Extra", ExportNames.chapterFolder(c.copy(title = "Extra"), 3))
        assertEquals("Chapter 3", ExportNames.chapterFolder(c, 3))
        assertEquals("Ch 001 - A B", ExportNames.chapterFolder(c.copy(number = 1f, title = "A/B"), 1))
        assertEquals("Ch 012 - Bread.cbz", ExportNames.archiveFile(c.copy(number = 12f, title = "Bread"), 1, ExportFormat.CBZ))
        assertEquals("Chapter 2.zip", ExportNames.archiveFile(c, 2, ExportFormat.ZIP))
    }

    /** A clean export's chapter ends in " (Clean)", never cut off, so it never replaces the translated one. */
    @Test fun cleanExportsHaveTheirOwnNames() {
        val c = Chapter(mangaId = 1, number = 12f, title = "Bread")
        assertEquals("Ch 012 - Bread (Clean)", ExportNames.chapterFolder(c, 1, clean = true))
        assertEquals("Ch 012 - Bread (Clean).cbz", ExportNames.archiveFile(c, 1, ExportFormat.CBZ, clean = true))
        assertEquals("Ch 012 - Bread.cbz", ExportNames.archiveFile(c, 1, ExportFormat.CBZ))
        val long = ExportNames.chapterFolder(c.copy(title = "x".repeat(200)), 1, clean = true)
        assertTrue(long.endsWith(ExportNames.CLEAN_SUFFIX))
        assertTrue(long.length <= ExportNames.MAX_NAME)
    }

    @Test fun pageFiles() {
        assertEquals("001.png", ExportNames.pageFile(0, 8))
        assertEquals("120.png", ExportNames.pageFile(119, 120))
        assertEquals("0001.png", ExportNames.pageFile(0, 1200))
        assertEquals("002.jpg", ExportNames.pageFile(1, 8, "jpg"))
        assertTrue(ExportNames.isPageFile("007.png"))
        assertTrue(ExportNames.isPageFile("007.jpg"), "a JPEG export's page is a page too")
        assertFalse(ExportNames.isPageFile("cover.png"))
        assertFalse(ExportNames.isPageFile("ComicInfo.xml"))
    }

    @Test fun comicInfo() {
        val m = Manga(title = "Stardust", author = "A", artist = "B", summary = "S", genres = "Slice of life", srcLang = Lang.JA, tgtLang = Lang.EN)
        val c = Chapter(mangaId = 1, number = 12f, volume = 2, title = "Bread", releaseDate = "2026-09-30", notes = "N")
        val info = ExportNames.comicInfo(m, c, 8)
        assertEquals("Stardust", info.series)
        assertEquals("12", info.number)
        assertEquals(2, info.volume)
        assertEquals("Bread", info.title)
        assertEquals("A", info.writer)
        assertEquals("B", info.penciller)
        assertEquals("en", info.languageIso)
        assertEquals(8, info.pageCount)
        assertEquals(2026, info.year)
        assertEquals(9, info.month)
        assertEquals(30, info.day)
        assertEquals("YesAndRightToLeft", info.manga)
        // The chapter's own language wins; a Korean source reads left to right; a loose date is left out.
        val ko = ExportNames.comicInfo(m.copy(srcLang = Lang.KO), c.copy(language = Lang.FR, releaseDate = "Sept 2026"), 1)
        assertEquals("fr", ko.languageIso)
        assertEquals("", ko.manga)
        assertNull(ko.year)
        // Mihon's status field follows the manga; an unknown status writes none.
        assertEquals("", info.publishingStatus)
        assertEquals("Ongoing", ExportNames.comicInfo(m.copy(status = MangaStatus.ONGOING), c, 1).publishingStatus)
    }

    @Test fun seriesInfoHoldsTheSeriesAndNoChapter() {
        val m = Manga(title = "Stardust", altTitle = "Hoshikuzu", author = "A", artist = "B", summary = "S", genres = "Drama", status = MangaStatus.HIATUS, srcLang = Lang.JA, tgtLang = Lang.EN)
        val info = ExportNames.seriesInfo(m)
        assertEquals("Stardust", info.series)
        assertEquals("Hoshikuzu", info.localizedSeries)
        assertEquals("Hoshikuzu", ExportNames.comicInfo(m, Chapter(mangaId = 1, number = 1f), 1).localizedSeries)
        assertEquals("S", info.summary)
        assertEquals("A", info.writer)
        assertEquals("B", info.penciller)
        assertEquals("Drama", info.genre)
        assertEquals("On hiatus", info.publishingStatus)
        assertEquals("en", info.languageIso)
        assertEquals("YesAndRightToLeft", info.manga)
        assertEquals("", info.number)
        assertEquals("", info.title)
        assertNull(info.pageCount)
        assertEquals("", ExportNames.seriesInfo(m, clean = true).languageIso)
    }
}
