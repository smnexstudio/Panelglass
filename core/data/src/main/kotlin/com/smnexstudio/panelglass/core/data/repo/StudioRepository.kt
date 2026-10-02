package com.smnexstudio.panelglass.core.data.repo

import androidx.room.withTransaction
import com.smnexstudio.panelglass.core.data.db.ChapterSummary
import com.smnexstudio.panelglass.core.data.db.MangaSummary
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where an import's pages go. */
sealed interface ImportTarget {
    /** A new manga with this chapter as its first. */
    data class NewManga(val manga: Manga, val chapter: Chapter) : ImportTarget
    /** A new chapter at the end of an existing manga. */
    data class NewChapter(val mangaId: Long, val chapter: Chapter) : ImportTarget
    /** More pages at the end of an existing chapter ("Add more images"). */
    data class AppendTo(val chapterId: Long) : ImportTarget
}

/** What one manga takes on the phone: its chapters, pages, and the bytes of their files (originals and layers). */
data class MangaStorage(val manga: Manga, val chapters: Int, val pages: Int, val bytes: Long)

/**
 * The Studio library: manga, their chapters and the chapters' pages, plus the page files. Every change that touches
 * several rows (an import, a reorder, a move) runs in one transaction; deleting rows also deletes their files.
 */
@Singleton
class StudioRepository @Inject constructor(private val db: PanelglassDb, val files: StudioFiles) {
    private val dao = db.studio()

    val mangas: Flow<List<MangaSummary>> = dao.observeMangas()
    fun manga(id: Long): Flow<Manga?> = dao.observeManga(id)
    fun chapters(mangaId: Long): Flow<List<ChapterSummary>> = dao.observeChapters(mangaId)
    fun chapter(id: Long): Flow<Chapter?> = dao.observeChapter(id)
    fun pages(chapterId: Long): Flow<List<StudioPage>> = dao.observePages(chapterId)

    suspend fun allMangas(): List<Manga> = dao.allMangas()
    suspend fun chapterList(mangaId: Long): List<Chapter> = dao.chapters(mangaId)
    suspend fun getManga(id: Long): Manga? = dao.manga(id)
    suspend fun getChapter(id: Long): Chapter? = dao.chapter(id)

    suspend fun createManga(manga: Manga): Long =
        dao.insertManga(manga.copy(createdAt = manga.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()))

    suspend fun updateManga(manga: Manga) = dao.updateManga(manga)
    suspend fun updateChapter(chapter: Chapter) = dao.updateChapter(chapter)

    /** Saves imported pages (their files are already in place) as [target] says; returns the chapter they are in. */
    suspend fun import(target: ImportTarget, pages: List<StudioPage>): Long {
        val now = System.currentTimeMillis()
        val numbered = pages.mapIndexed { i, p -> p.copy(index = i) }
        return try {
            when (target) {
                is ImportTarget.NewManga -> dao.insertMangaWithChapter(
                    target.manga.copy(createdAt = now),
                    target.chapter.copy(sortOrder = 0, createdAt = now),
                    numbered,
                )
                is ImportTarget.NewChapter -> db.withTransaction {
                    val order = dao.nextChapterOrder(target.mangaId)
                    dao.insertChapterWithPages(target.chapter.copy(mangaId = target.mangaId, sortOrder = order, createdAt = now), numbered)
                }
                is ImportTarget.AppendTo -> {
                    dao.appendPages(target.chapterId, numbered)
                    target.chapterId
                }
            }
        } finally {
            files.settle(pages.map { it.dir })
        }
    }

    /** Every manga with the storage it uses, largest first (Settings › Storage). Walks the page directories. */
    suspend fun storage(): List<MangaStorage> = withContext(Dispatchers.IO) {
        dao.allMangas().map { m ->
            val pages = dao.pagesOfManga(m.id)
            MangaStorage(m, dao.chapters(m.id).size, pages.size, pages.sumOf { files.pageBytes(it.dir) } + files.cover(m.id).length())
        }.sortedByDescending { it.bytes }
    }

    suspend fun deleteManga(manga: Manga) {
        val pages = dao.pagesOfManga(manga.id)
        dao.deleteManga(manga) // pages and bubbles go with it (foreign keys cascade)
        deleteFiles(pages)
        withContext(Dispatchers.IO) { files.deleteCover(manga.id) }
    }

    suspend fun deleteChapter(chapter: Chapter) {
        val pages = dao.pages(chapter.id)
        db.withTransaction {
            dao.deleteChapter(chapter)
            renumberChapters(chapter.mangaId)
        }
        deleteFiles(pages)
    }

    /** Deletes [pageIds] and closes the gaps they leave in their chapters. */
    suspend fun deletePages(pageIds: Collection<Long>) {
        if (pageIds.isEmpty()) return
        val pages = dao.pagesById(pageIds.toList())
        db.withTransaction {
            dao.deletePages(pages)
            pages.map { it.chapterId }.distinct().forEach { renumberPages(it) }
        }
        deleteFiles(pages)
    }

    /** Chapter order as the user dragged it: [orderedIds] are the manga's chapter ids, first to last. */
    suspend fun reorderChapters(mangaId: Long, orderedIds: List<Long>) = db.withTransaction {
        val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
        val chapters = dao.chapters(mangaId).sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.sortOrder }))
        dao.updateChapters(chapters.mapIndexed { i, c -> c.copy(sortOrder = i) }.filterIndexed { i, c -> chapters[i].sortOrder != c.sortOrder })
    }

    /** Page order as the user dragged it: [orderedIds] are the chapter's page ids, first to last. */
    suspend fun reorderPages(chapterId: Long, orderedIds: List<Long>) = db.withTransaction {
        val rank = orderedIds.withIndex().associate { (i, id) -> id to i }
        val pages = dao.pages(chapterId).sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.index }))
        dao.updatePages(pages.mapIndexed { i, p -> p.copy(index = i) }.filterIndexed { i, p -> pages[i].index != p.index })
    }

    /** Moves pages to the end of another chapter, in their current order; the chapters they left are renumbered. */
    suspend fun movePages(pageIds: Collection<Long>, toChapterId: Long) = db.withTransaction {
        val pages = dao.pagesById(pageIds.toList()).filter { it.chapterId != toChapterId }
            .sortedWith(compareBy({ it.chapterId }, { it.index }))
        if (pages.isEmpty()) return@withTransaction
        val start = dao.nextPageIndex(toChapterId)
        dao.updatePages(pages.mapIndexed { i, p -> p.copy(chapterId = toChapterId, index = start + i) })
        pages.map { it.chapterId }.distinct().forEach { renumberPages(it) }
    }

    /** Moves a chapter to the end of another manga. */
    suspend fun moveChapter(chapterId: Long, toMangaId: Long) = db.withTransaction {
        val chapter = dao.chapter(chapterId) ?: return@withTransaction
        if (chapter.mangaId == toMangaId) return@withTransaction
        dao.updateChapter(chapter.copy(mangaId = toMangaId, sortOrder = dao.nextChapterOrder(toMangaId)))
        renumberChapters(chapter.mangaId)
    }

    suspend fun pageList(chapterId: Long): List<StudioPage> = dao.pages(chapterId)
    suspend fun getPage(id: Long): StudioPage? = dao.page(id)
    fun bubbles(pageId: Long): Flow<List<Bubble>> = dao.observeBubbles(pageId)
    suspend fun bubbleList(pageId: Long): List<Bubble> = dao.bubbles(pageId)
    suspend fun updatePage(page: StudioPage) = dao.updatePage(page)
    suspend fun updateBubble(bubble: Bubble) = dao.updateBubble(bubble)
    suspend fun addBubble(bubble: Bubble): Long = dao.insertBubbles(listOf(bubble.copy(id = 0))).single()
    suspend fun deleteBubble(bubble: Bubble) = dao.deleteBubble(bubble)

    /**
     * A page's new detection: its bubbles are replaced by [bubbles], except the ones the user dismissed (kept, so a
     * re-detect does not bring a false detection back), and the page row saved, in one transaction.
     */
    suspend fun saveDetection(page: StudioPage, bubbles: List<Bubble>) = db.withTransaction {
        val ignored = dao.bubbles(page.id).filter { it.ignored }
        dao.deleteBubbles(page.id)
        dao.insertBubbles(ignored.map { it.copy(id = 0) } + bubbles.map { it.copy(id = 0, pageId = page.id) })
        dao.updatePage(page)
    }

    /** A page's translation: its bubbles' new texts and the page row, in one transaction. */
    suspend fun saveTranslation(page: StudioPage, bubbles: List<Bubble>) = db.withTransaction {
        bubbles.forEach { dao.updateBubble(it) }
        dao.updatePage(page)
    }

    /** Deletes page directories left by an import that never finished (the app was killed mid-way). */
    suspend fun sweepOrphanFiles(): Int {
        val known = dao.allPageFiles().map { it.substringBeforeLast('/') }.toSet()
        return withContext(Dispatchers.IO) { files.sweepOrphans(known) }
    }

    private suspend fun renumberPages(chapterId: Long) {
        val pages = dao.pages(chapterId)
        dao.updatePages(pages.mapIndexedNotNull { i, p -> if (p.index != i) p.copy(index = i) else null })
    }

    private suspend fun renumberChapters(mangaId: Long) {
        val chapters = dao.chapters(mangaId)
        dao.updateChapters(chapters.mapIndexedNotNull { i, c -> if (c.sortOrder != i) c.copy(sortOrder = i) else null })
    }

    private suspend fun deleteFiles(pages: List<StudioPage>) = withContext(Dispatchers.IO) {
        pages.forEach { files.deletePageDir(it.dir) }
    }
}
