package com.smnexstudio.panelglass.feature.studio

import com.smnexstudio.panelglass.core.data.db.MangaSummary
import com.smnexstudio.panelglass.core.model.Manga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The Studio library's search (title or alternative title) and its two orders. */
class LibraryOrderTest {
    private fun tile(id: Long, title: String, alt: String = "", created: Long = id) =
        MangaTile(MangaSummary(Manga(id = id, title = title, altTitle = alt, createdAt = created), 0, 0, 0, null), null, false)

    private val list = listOf(tile(1, "stardust Bakery"), tile(2, "Night Train", alt = "夜行列車"), tile(3, "Apple"))

    @Test fun searchMatchesTitleOrAltTitleIgnoringCase() {
        assertEquals(listOf(1L), StudioHomeViewModel.filtered(list, " BAKERY ").map { it.summary.manga.id })
        assertEquals(listOf(2L), StudioHomeViewModel.filtered(list, "夜行").map { it.summary.manga.id })
        assertEquals(list, StudioHomeViewModel.filtered(list, "  "))
    }

    @Test fun newestFirstOrTitleAToZ() {
        assertEquals(listOf(3L, 2L, 1L), StudioHomeViewModel.sorted(list, MangaSort.NEWEST).map { it.summary.manga.id })
        assertEquals(listOf(3L, 2L, 1L), StudioHomeViewModel.sorted(list, MangaSort.TITLE).map { it.summary.manga.id })
        val reordered = listOf(tile(1, "b", created = 5), tile(2, "A", created = 9), tile(3, "c", created = 1))
        assertEquals(listOf(2L, 1L, 3L), StudioHomeViewModel.sorted(reordered, MangaSort.NEWEST).map { it.summary.manga.id })
        assertEquals(listOf(2L, 1L, 3L), StudioHomeViewModel.sorted(reordered, MangaSort.TITLE).map { it.summary.manga.id })
    }
}
