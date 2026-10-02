package com.smnexstudio.panelglass.feature.studio

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

    @Test fun oneFolderOrLoosePagesStayOneChapter() {
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("Chapter 66/1.jpg"), page("Chapter 66/2.jpg"))))
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("1.jpg"), page("2.jpg"))))
        assertEquals(emptyList<FolderChapter>(), FolderChapters.of(listOf(page("cover.jpg"), page("Chapter 1/1.jpg"), page("Chapter 2/1.jpg"))), "loose pages beside a folder: no split")
    }
}
