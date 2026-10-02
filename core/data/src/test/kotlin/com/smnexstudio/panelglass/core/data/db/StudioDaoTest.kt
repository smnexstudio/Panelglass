package com.smnexstudio.panelglass.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.model.TextRegion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var files: StudioFiles
    private lateinit var repo: StudioRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        files = StudioFiles(File(context.filesDir, "studio-test").apply { deleteRecursively() })
        repo = StudioRepository(db, files)
    }

    @After fun tearDown() {
        db.close()
        files.root.deleteRecursively()
    }

    /** A page whose file exists, as an import leaves it before the rows are saved. */
    private fun newPage(name: String = "p"): StudioPage {
        val (rel, dir) = files.newPageDir()
        File(dir, "original.png").writeText(name)
        return StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 10, height = 20)
    }

    private suspend fun importManga(pages: Int): Long =
        repo.import(ImportTarget.NewManga(Manga(title = "M", srcLang = Lang.JA, tgtLang = Lang.EN), Chapter(mangaId = 0, number = 1f)), List(pages) { newPage("p$it") })

    @Test fun importNumbersPagesAndCountsThem() = runBlocking {
        val ch = importManga(3)
        val pages = db.studio().pages(ch)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        val summary = repo.mangas.first().single()
        assertEquals(1, summary.chapterCount)
        assertEquals(3, summary.pageCount)
        assertEquals(0, summary.reviewedCount)
        assertEquals(pages[0].file, summary.coverFile)
    }

    @Test fun appendGoesAfterTheLastPage() = runBlocking {
        val ch = importManga(2)
        repo.import(ImportTarget.AppendTo(ch), listOf(newPage("a"), newPage("b")))
        val pages = db.studio().pages(ch)
        assertEquals(listOf(0, 1, 2, 3), pages.map { it.index })
        assertEquals("b", files.file(pages[3].file).readText())
    }

    @Test fun newChapterGoesLast() = runBlocking {
        val ch1 = importManga(1)
        val mangaId = db.studio().chapter(ch1)!!.mangaId
        val ch2 = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0, number = 2f)), listOf(newPage()))
        assertEquals(listOf(ch1, ch2), db.studio().chapters(mangaId).map { it.id })
        assertEquals(listOf(0, 1), db.studio().chapters(mangaId).map { it.sortOrder })
    }

    @Test fun reviewedProgressIsCounted() = runBlocking {
        val ch = importManga(3)
        val p = db.studio().pages(ch)[1]
        db.studio().updatePage(p.copy(stages = setOf(StudioStage.TRANSLATED, StudioStage.REVIEWED)))
        val summary = repo.chapters(db.studio().chapter(ch)!!.mangaId).first().single()
        assertEquals(3, summary.pageCount)
        assertEquals(1, summary.reviewedCount)
        assertTrue(db.studio().page(p.id)!!.reviewed)
    }

    @Test fun reorderPagesSticks() = runBlocking {
        val ch = importManga(3)
        val ids = db.studio().pages(ch).map { it.id }
        repo.reorderPages(ch, listOf(ids[2], ids[0], ids[1]))
        assertEquals(listOf(ids[2], ids[0], ids[1]), db.studio().pages(ch).map { it.id })
        assertEquals(listOf(0, 1, 2), db.studio().pages(ch).map { it.index })
    }

    @Test fun movingPagesRenumbersBothChapters() = runBlocking {
        val ch1 = importManga(4)
        val mangaId = db.studio().chapter(ch1)!!.mangaId
        val ch2 = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0)), listOf(newPage()))
        val src = db.studio().pages(ch1)
        repo.movePages(listOf(src[1].id, src[3].id), ch2)
        assertEquals(listOf(src[0].id, src[2].id), db.studio().pages(ch1).map { it.id })
        assertEquals(listOf(0, 1), db.studio().pages(ch1).map { it.index })
        assertEquals(listOf(0, 1, 2), db.studio().pages(ch2).map { it.index })
        assertEquals(src[3].id, db.studio().pages(ch2).last().id)
        // The files do not move.
        assertEquals(src[1].file, db.studio().page(src[1].id)!!.file)
    }

    @Test fun deletingPagesRemovesFilesAndGaps() = runBlocking {
        val ch = importManga(3)
        val pages = db.studio().pages(ch)
        repo.deletePages(listOf(pages[1].id))
        assertEquals(listOf(pages[0].id, pages[2].id), db.studio().pages(ch).map { it.id })
        assertEquals(listOf(0, 1), db.studio().pages(ch).map { it.index })
        assertFalse(File(files.root, pages[1].dir).exists())
        assertTrue(File(files.root, pages[0].dir).exists())
    }

    @Test fun deletingAMangaCascadesToEverything() = runBlocking {
        val ch = importManga(2)
        val page = db.studio().pages(ch)[0]
        db.studio().insertBubbles(listOf(Bubble(pageId = page.id, order = 0, kind = RegionKind.ENCLOSED, polygon = listOf(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f)))))
        val manga = db.studio().manga(db.studio().chapter(ch)!!.mangaId)!!
        repo.deleteManga(manga)
        assertNull(db.studio().chapter(ch))
        assertTrue(db.studio().pages(ch).isEmpty())
        assertTrue(db.studio().bubbles(page.id).isEmpty())
        assertFalse(File(files.root, page.dir).exists())
    }

    @Test fun deletingAChapterRenumbersTheRest() = runBlocking {
        val ch1 = importManga(1)
        val mangaId = db.studio().chapter(ch1)!!.mangaId
        val ch2 = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0)), listOf(newPage()))
        repo.deleteChapter(db.studio().chapter(ch1)!!)
        assertEquals(listOf(ch2), db.studio().chapters(mangaId).map { it.id })
        assertEquals(0, db.studio().chapter(ch2)!!.sortOrder)
    }

    @Test fun chapterMovesToAnotherManga() = runBlocking {
        val ch1 = importManga(1)
        val ch2 = importManga(1)
        val m2 = db.studio().chapter(ch2)!!.mangaId
        repo.moveChapter(ch1, m2)
        assertEquals(listOf(ch2, ch1), db.studio().chapters(m2).map { it.id })
    }

    @Test fun reorderChaptersSticks() = runBlocking {
        val ch1 = importManga(1)
        val mangaId = db.studio().chapter(ch1)!!.mangaId
        val ch2 = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0)), listOf(newPage()))
        val ch3 = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0)), listOf(newPage()))
        repo.reorderChapters(mangaId, listOf(ch3, ch1, ch2))
        assertEquals(listOf(ch3, ch1, ch2), db.studio().chapters(mangaId).map { it.id })
    }

    @Test fun orphanSweepSparesSavedAndPendingPages() = runBlocking {
        importManga(1)
        val orphan = newPage() // written, never saved: the import was killed
        files.settle(listOf(orphan.dir))
        val inFlight = newPage() // an import still running
        assertEquals(1, repo.sweepOrphanFiles())
        assertFalse(File(files.root, orphan.dir).exists())
        assertTrue(File(files.root, inFlight.dir).exists())
        assertEquals(1, db.studio().allPageFiles().size)
    }

    @Test fun jsonColumnsRoundTrip() = runBlocking {
        val ch = importManga(1)
        val page = db.studio().pages(ch)[0]
        val bubble = Bubble(
            pageId = page.id, order = 0, kind = RegionKind.SFX,
            polygon = listOf(Pt(1f, 2f), Pt(3.5f, 4f), Pt(5f, 6f)),
            sourceText = "ドン", translatedText = "BOOM",
            style = BubbleStyle(fontId = "cat:bangers", bold = true, sizePx = 30f, fillColor = 0x80FFFFFF.toInt()),
            region = TextRegion(bbox = IntRect(1, 2, 3, 4), kind = RegionKind.SFX, text = "ドン"),
        )
        val id = db.studio().insertBubbles(listOf(bubble)).single()
        assertEquals(bubble.copy(id = id), db.studio().bubbles(page.id).single())
    }

    @Test fun pathsOutsideTheRootAreRefused() {
        val refused = runCatching { files.file("../../databases/panelglass.db") }.isFailure
        assertTrue(refused)
    }
}
