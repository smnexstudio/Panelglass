package com.smnexstudio.panelglass.feature.studio

import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.feature.studio.importer.StagedPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** An archive's folders as chapters (a CBZ holding `Chapter 66/` and `Chapter 67/`). */
class FolderChaptersTest {
    private fun page(name: String) = StagedPage(name, StudioPage(chapterId = 0, index = 0, file = name, width = 1, height = 1))

    @Test fun twoChapterFoldersBecomeTwoNumberedChapters() {
        val pages = (1..3).map { page("Chapter 66/$it.jpg") } + (1..2).map { page("Chapter 67 - Water Wings/$it.jpg") }
        assertEquals(
            listOf(FolderChapter("Chapter 66", "66", "", 3), FolderChapter("Chapter 67 - Water Wings", "67", "Water Wings", 2)),
            FolderChapters.of(pages),
        )
    }

    @Test fun numbersComeFromAnyUsualFolderName() {
        val pages = listOf(page("Vol 2/c012/1.png"), page("Vol 2/c013/1.png"), page("第14話/1.png"), page("15/1.png"))
        assertEquals(listOf("12", "13", "14", "15"), FolderChapters.of(pages).map { it.number })
    }

    @Test fun foldersHoldingChapterFoldersAreSeries() {
        // Mihon's download layout: <source>/<series>/<chapter>.
        val pages = listOf(page("Src/Stars/Chapter 1/1.jpg"), page("Src/Stars/Chapter 2/1.jpg"), page("Src/Moons/Chapter 1/1.jpg"))
        val chapters = FolderChapters.of(pages)
        assertEquals(listOf("Stars", "Stars", "Moons"), chapters.map { it.series })
        assertEquals(listOf("Stars" to 2, "Moons" to 1), FolderChapters.series(chapters).map { it.title to it.chapters.size })
    }

    @Test fun comicInfoNamesTheSeriesAndFillsAMissingNumber() {
        val pages = listOf(page("a/x/1.jpg"), page("b/y/1.jpg"), page("c/z/1.jpg"))
        val infos = mapOf(
            "a/x" to ComicInfo(series = "Invented Stars", number = "4"),
            "b/y" to ComicInfo(series = "invented stars", number = "5"), // the same series, another case
            "c/z" to ComicInfo(series = "Paper Moons", number = "1"),
        )
        val chapters = FolderChapters.of(pages, infos)
        assertEquals(listOf("4", "5", "1"), chapters.map { it.number })
        val series = FolderChapters.series(chapters, infos)
        assertEquals(listOf("Invented Stars" to 2, "Paper Moons" to 1), series.map { it.title to it.chapters.size })
        assertEquals("Invented Stars", series.first().info?.series)
    }

    @Test fun volumesChaptersAtTwoDepthsAndOneSeriesAreNotSeveralSeries() {
        fun series(vararg names: String) = FolderChapters.series(FolderChapters.of(names.map { page(it) }))
        assertEquals(emptyList<SeriesGroup>(), series("Vol 1/c001/1.png", "Vol 2/c010/1.png"), "volumes of one series")
        assertEquals(emptyList<SeriesGroup>(), series("v01/c001/1.png", "v02/c010/1.png"))
        assertEquals(emptyList<SeriesGroup>(), series("1/c001/1.png", "2/c010/1.png"), "numbered parts")
        assertEquals(emptyList<SeriesGroup>(), series("S/c001/1.png", "S/Extra/c002/1.png"), "chapters at two depths")
        assertEquals(emptyList<SeriesGroup>(), series("Stars/c001/1.png", "Stars/c002/1.png"), "one series")
        assertEquals(emptyList<SeriesGroup>(), series("Chapter 1/1.png", "Chapter 2/1.png"), "no folder above the chapters")
        assertEquals(2, series("Band of Stars/c001/1.png", "Vinland Moons/c001/1.png").size, "names that only start like a volume")
    }

    @Test fun oneFolderOrLoosePagesStayOneChapter() {
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("Chapter 66/1.jpg"), page("Chapter 66/2.jpg"))))
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("1.jpg"), page("2.jpg"))))
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("cover.jpg"), page("Chapter 1/1.jpg"), page("Chapter 2/1.jpg"))), "loose pages beside a folder: no split")
    }
}
