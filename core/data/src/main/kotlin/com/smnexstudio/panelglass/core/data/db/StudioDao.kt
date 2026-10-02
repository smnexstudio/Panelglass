package com.smnexstudio.panelglass.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.UserFont
import kotlinx.coroutines.flow.Flow

/** A manga with what its card shows. [coverFile] is its first page (Studio-root relative), null while it has none. */
data class MangaSummary(
    @Embedded val manga: Manga,
    val chapterCount: Int,
    val pageCount: Int,
    val reviewedCount: Int,
    val coverFile: String?,
)

/** A chapter with its progress (`12/20 reviewed`) and first page. */
data class ChapterSummary(
    @Embedded val chapter: Chapter,
    val pageCount: Int,
    val reviewedCount: Int,
    val coverFile: String?,
)

@Dao
interface StudioDao {
    // ---- manga -----------------------------------------------------------------------------------

    @Query(
        """
        SELECT m.*,
          (SELECT COUNT(*) FROM studio_chapter c WHERE c.mangaId = m.id) AS chapterCount,
          (SELECT COUNT(*) FROM studio_page p JOIN studio_chapter c ON p.chapterId = c.id WHERE c.mangaId = m.id) AS pageCount,
          (SELECT COUNT(*) FROM studio_page p JOIN studio_chapter c ON p.chapterId = c.id
             WHERE c.mangaId = m.id AND p.stages LIKE '%REVIEWED%') AS reviewedCount,
          (SELECT p.file FROM studio_page p JOIN studio_chapter c ON p.chapterId = c.id
             WHERE c.mangaId = m.id ORDER BY c.sortOrder, p.`index` LIMIT 1) AS coverFile
        FROM studio_manga m ORDER BY m.createdAt DESC, m.id DESC
        """,
    )
    fun observeMangas(): Flow<List<MangaSummary>>

    @Query("SELECT * FROM studio_manga ORDER BY title COLLATE NOCASE")
    suspend fun allMangas(): List<Manga>

    @Query("SELECT * FROM studio_manga WHERE id = :id")
    fun observeManga(id: Long): Flow<Manga?>

    @Query("SELECT * FROM studio_manga WHERE id = :id")
    suspend fun manga(id: Long): Manga?

    @Insert suspend fun insertManga(manga: Manga): Long
    @Update suspend fun updateManga(manga: Manga)
    @Delete suspend fun deleteManga(manga: Manga)

    // ---- chapters --------------------------------------------------------------------------------

    @Query(
        """
        SELECT c.*,
          (SELECT COUNT(*) FROM studio_page p WHERE p.chapterId = c.id) AS pageCount,
          (SELECT COUNT(*) FROM studio_page p WHERE p.chapterId = c.id AND p.stages LIKE '%REVIEWED%') AS reviewedCount,
          (SELECT p.file FROM studio_page p WHERE p.chapterId = c.id ORDER BY p.`index` LIMIT 1) AS coverFile
        FROM studio_chapter c WHERE c.mangaId = :mangaId ORDER BY c.sortOrder, c.id
        """,
    )
    fun observeChapters(mangaId: Long): Flow<List<ChapterSummary>>

    @Query("SELECT * FROM studio_chapter WHERE mangaId = :mangaId ORDER BY sortOrder, id")
    suspend fun chapters(mangaId: Long): List<Chapter>

    @Query("SELECT * FROM studio_chapter WHERE id = :id")
    fun observeChapter(id: Long): Flow<Chapter?>

    @Query("SELECT * FROM studio_chapter WHERE id = :id")
    suspend fun chapter(id: Long): Chapter?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM studio_chapter WHERE mangaId = :mangaId")
    suspend fun nextChapterOrder(mangaId: Long): Int

    @Insert suspend fun insertChapter(chapter: Chapter): Long
    @Update suspend fun updateChapter(chapter: Chapter)
    @Update suspend fun updateChapters(chapters: List<Chapter>)
    @Delete suspend fun deleteChapter(chapter: Chapter)

    // ---- pages -----------------------------------------------------------------------------------

    @Query("SELECT * FROM studio_page WHERE chapterId = :chapterId ORDER BY `index`, id")
    fun observePages(chapterId: Long): Flow<List<StudioPage>>

    @Query("SELECT * FROM studio_page WHERE chapterId = :chapterId ORDER BY `index`, id")
    suspend fun pages(chapterId: Long): List<StudioPage>

    @Query("SELECT p.* FROM studio_page p JOIN studio_chapter c ON p.chapterId = c.id WHERE c.mangaId = :mangaId")
    suspend fun pagesOfManga(mangaId: Long): List<StudioPage>

    @Query("SELECT * FROM studio_page WHERE id = :id")
    suspend fun page(id: Long): StudioPage?

    @Query("SELECT * FROM studio_page WHERE id IN (:ids)")
    suspend fun pagesById(ids: List<Long>): List<StudioPage>

    @Query("SELECT file FROM studio_page")
    suspend fun allPageFiles(): List<String>

    @Query("SELECT COALESCE(MAX(`index`), -1) + 1 FROM studio_page WHERE chapterId = :chapterId")
    suspend fun nextPageIndex(chapterId: Long): Int

    @Insert suspend fun insertPages(pages: List<StudioPage>): List<Long>
    @Update suspend fun updatePage(page: StudioPage)
    @Update suspend fun updatePages(pages: List<StudioPage>)
    @Delete suspend fun deletePages(pages: List<StudioPage>)

    /** A new chapter and its pages in one transaction, so an interrupted import leaves no half chapter. */
    @Transaction
    suspend fun insertChapterWithPages(chapter: Chapter, pages: List<StudioPage>): Long {
        val id = insertChapter(chapter)
        insertPages(pages.map { it.copy(chapterId = id) })
        return id
    }

    /** A new manga, its first chapter and the chapter's pages, all or nothing. */
    @Transaction
    suspend fun insertMangaWithChapter(manga: Manga, chapter: Chapter, pages: List<StudioPage>): Long {
        val mangaId = insertManga(manga)
        return insertChapterWithPages(chapter.copy(mangaId = mangaId), pages)
    }

    /** Pages appended to an existing chapter, numbered after the last one. */
    @Transaction
    suspend fun appendPages(chapterId: Long, pages: List<StudioPage>) {
        val start = nextPageIndex(chapterId)
        insertPages(pages.mapIndexed { i, p -> p.copy(chapterId = chapterId, index = start + i) })
    }

    // ---- bubbles ---------------------------------------------------------------------------------

    @Query("SELECT * FROM studio_bubble WHERE pageId = :pageId ORDER BY `order`, id")
    fun observeBubbles(pageId: Long): Flow<List<Bubble>>

    @Query("SELECT * FROM studio_bubble WHERE pageId = :pageId ORDER BY `order`, id")
    suspend fun bubbles(pageId: Long): List<Bubble>

    @Insert suspend fun insertBubbles(bubbles: List<Bubble>): List<Long>
    @Update suspend fun updateBubble(bubble: Bubble)
    @Delete suspend fun deleteBubble(bubble: Bubble)

    @Query("DELETE FROM studio_bubble WHERE pageId = :pageId")
    suspend fun deleteBubbles(pageId: Long)
}

@Dao
interface UserFontDao {
    /** Bubbles whose style names the font [quotedId] (`"user:…"` with its quotes, as the style JSON holds it). */
    @Query("SELECT COUNT(*) FROM studio_bubble WHERE style LIKE '%' || :quotedId || '%'")
    suspend fun bubblesUsing(quotedId: String): Int

    @Query("SELECT * FROM user_font ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<UserFont>>

    @Query("SELECT * FROM user_font")
    suspend fun all(): List<UserFont>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(font: UserFont)

    @Delete
    suspend fun delete(font: UserFont)
}
