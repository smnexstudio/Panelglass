package com.smnexstudio.panelglass.feature.studio.export

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.feature.studio.StorageSpace
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
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile

/** Exports into plain directories (the MediaStore / SAF seam replaced): layout, archives, overwrite, space and failures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StudioExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PanelglassDb
    private lateinit var repo: StudioRepository
    private lateinit var settings: SettingsRepository
    private lateinit var root: File
    private lateinit var downloads: File
    private lateinit var picked: File
    private val targets = DirTargets()
    private val composer = FakeComposer { repo.files.file(it.file).readText() }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        root = File(context.filesDir, "export-test").apply { deleteRecursively(); mkdirs() }
        downloads = File(root, "Download/Panelglass").apply { mkdirs() }
        picked = File(root, "picked").apply { mkdirs() }
        repo = StudioRepository(db, StudioFiles(File(root, "studio")))
        settings = SettingsRepository(PreferenceDataStoreFactory.create { File(root, "settings.preferences_pb") })
        targets.dirs[null] = downloads
        targets.dirs[PICKED] = picked
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun exporter() = StudioExporter(repo, targets, composer, settings)

    private fun pages(n: Int, tag: String) = (1..n).map { i ->
        val (rel, dir) = repo.files.newPageDir()
        File(dir, "original.png").writeText("$tag$i")
        StudioPage(chapterId = 0, index = 0, file = "$rel/original.png", width = 100, height = 150)
    }

    /** A manga "Stardust" with a chapter 12 "Bread" of [n] pages; returns (mangaId, chapterId). */
    private suspend fun manga(n: Int): Pair<Long, Long> {
        val ch = repo.import(
            ImportTarget.NewManga(Manga(title = "Stardust", srcLang = Lang.JA, tgtLang = Lang.EN), Chapter(mangaId = 0, number = 12f, title = "Bread")),
            pages(n, "a"),
        )
        return repo.getChapter(ch)!!.mangaId to ch
    }

    @Test fun byDefaultPagesAndComicInfoGoToDownloads() = runBlocking {
        val (m, ch) = manga(3)
        val e = exporter()
        e.export(m, listOf(ch), e.folderFor(m), ExportFormat.IMAGES)
        val dir = File(downloads, "Stardust/Ch 012 - Bread")
        assertEquals(listOf("001.png", "002.png", "003.png", "ComicInfo.xml"), dir.list()!!.sorted())
        assertEquals("page:a2:EN", File(dir, "002.png").readText())
        val info = ComicInfo.parse(File(dir, "ComicInfo.xml").readText())
        assertEquals("Stardust", info.series)
        assertEquals("12", info.number)
        assertEquals(3, info.pageCount)
        val run = e.run.value!!
        assertEquals(ExportPhase.DONE, run.phase)
        assertEquals(3, run.written)
        assertEquals("Panelglass/Stardust/Ch 012 - Bread", run.where)
    }

    @Test fun aCbzHoldsThePagesAndComicInfo() = runBlocking {
        val (m, ch) = manga(2)
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.CBZ)
        val cbz = File(downloads, "Stardust/Ch 012 - Bread.cbz")
        assertTrue(cbz.isFile)
        ZipFile(cbz).use { z ->
            assertEquals(listOf("001.png", "002.png", "ComicInfo.xml"), z.entries().toList().map { it.name })
            assertEquals("page:a2:EN", z.getInputStream(z.getEntry("002.png")).readBytes().decodeToString())
        }
        assertEquals(1, e.existing(m, listOf(ch), null, ExportFormat.CBZ))
        assertEquals("a ZIP is another file", 0, e.existing(m, listOf(ch), null, ExportFormat.ZIP))
    }

    @Test fun aSecondExportReplacesFilesAndDropsPagesBeyondTheEnd() = runBlocking {
        val (m, ch) = manga(2)
        val dir = File(downloads, "Stardust/Ch 012 - Bread").apply { mkdirs() }
        (1..5).forEach { File(dir, "%03d.png".format(it)).writeText("old") }
        File(dir, "notes.txt").writeText("mine")
        val e = exporter()
        assertEquals(1, e.existing(m, listOf(ch), null, ExportFormat.IMAGES))
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        assertEquals(listOf("001.png", "002.png", "ComicInfo.xml", "notes.txt"), dir.list()!!.sorted())
        assertEquals("page:a1:EN", File(dir, "001.png").readText())
        assertEquals("a file that is not a page is kept", "mine", File(dir, "notes.txt").readText())
    }

    @Test fun nothingExistsBeforeTheFirstExport() = runBlocking {
        val (m, ch) = manga(1)
        assertEquals(0, exporter().existing(m, listOf(ch), null, ExportFormat.IMAGES))
    }

    @Test fun chaptersAreWrittenInOrderAndAChapterWithoutANumberIsNamedByPosition() = runBlocking {
        val (m, ch1) = manga(1)
        val ch2 = repo.import(ImportTarget.NewChapter(m, Chapter(mangaId = m, language = Lang.FR)), pages(2, "b"))
        val e = exporter()
        e.export(m, listOf(ch1, ch2), null, ExportFormat.IMAGES)
        assertEquals(listOf("Ch 012 - Bread", "Chapter 2"), File(downloads, "Stardust").list()!!.sorted())
        assertEquals("the chapter's own language letters it", "page:b1:FR", File(downloads, "Stardust/Chapter 2/001.png").readText())
        assertEquals(3, e.run.value!!.written)
        assertEquals("several chapters: the manga's folder is named", "Panelglass/Stardust", e.run.value!!.where)
    }

    @Test fun aPickedFolderIsUsedForItsMangaOnlyAndCanBeDropped() = runBlocking {
        val (m, ch) = manga(1)
        val other = repo.getChapter(repo.import(ImportTarget.NewManga(Manga(title = "Other"), Chapter(mangaId = 0)), pages(1, "c")))!!.mangaId
        val e = exporter()
        assertNull("Downloads until a folder is picked", e.folderFor(m))
        assertTrue(e.choose(m, PICKED))
        assertEquals(PICKED, e.folderFor(m))
        assertNull("another manga still exports to Downloads", e.folderFor(other))
        e.export(m, listOf(ch), e.folderFor(m), ExportFormat.IMAGES)
        assertTrue(File(picked, "Stardust/Ch 012 - Bread/001.png").isFile)
        assertTrue(e.choose(m, null))
        assertNull(e.folderFor(m))
    }

    @Test fun aFolderWithoutPermissionFailsBeforeWriting() = runBlocking {
        val (m, ch) = manga(1)
        targets.dirs.remove(PICKED)
        val e = exporter()
        e.export(m, listOf(ch), PICKED, ExportFormat.IMAGES)
        assertEquals(ExportError.FOLDER_GONE, e.run.value!!.error)
        assertEquals(ExportPhase.FAILED, e.run.value!!.phase)
        assertTrue(picked.list()!!.isEmpty())
    }

    @Test fun notEnoughSpaceIsRefusedBeforeWriting() = runBlocking {
        val (m, ch) = manga(3)
        composer.bytesPerPage = 10L * 1024 * 1024
        targets.free = StorageSpace.RESERVE + 20L * 1024 * 1024 // room for two pages, not three
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        val run = e.run.value!!
        assertEquals(ExportError.NO_SPACE, run.error)
        assertEquals(30L * 1024 * 1024, run.needBytes)
        assertEquals(targets.free, run.freeBytes)
        assertTrue(downloads.list()!!.isEmpty())
    }

    @Test fun aFullStorageWhileWritingIsReportedAsSuch() = runBlocking {
        val (m, ch) = manga(1)
        targets.failWrites = IOException("write failed: ENOSPC (No space left on device)")
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        assertEquals(ExportError.NO_SPACE, e.run.value!!.error)
        targets.failWrites = IOException("refused")
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        assertEquals(ExportError.WRITE_FAILED, e.run.value!!.error)
    }

    @Test fun anUnreadablePageStopsTheExportAndLeavesNoHalfArchive() = runBlocking {
        val (m, ch) = manga(3)
        composer.failOn = "a2"
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        assertEquals(ExportError.PAGE_UNREADABLE, e.run.value!!.error)
        assertEquals(1, e.run.value!!.written)
        assertFalse(File(downloads, "Stardust/Ch 012 - Bread/ComicInfo.xml").exists())
        e.export(m, listOf(ch), null, ExportFormat.CBZ)
        assertEquals(ExportError.PAGE_UNREADABLE, e.run.value!!.error)
        assertFalse(File(downloads, "Stardust/Ch 012 - Bread.cbz").exists())
    }

    @Test fun theFormatAndQualityAreRemembered() = runBlocking {
        val e = exporter()
        assertEquals(ExportFormat.IMAGES, e.format())
        assertEquals(PageQuality.PNG, e.quality())
        e.setFormat(ExportFormat.CBZ)
        e.setQuality(PageQuality.JPEG)
        assertEquals(ExportFormat.CBZ, exporter().format())
        assertEquals(PageQuality.JPEG, exporter().quality())
    }

    @Test fun jpegPagesReplaceThePngOnesAndEachFolderIsListedWithItsSize() = runBlocking {
        val (m, ch) = manga(2)
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        e.export(m, listOf(ch), null, ExportFormat.IMAGES, PageQuality.JPEG)
        val dir = File(downloads, "Stardust/Ch 012 - Bread")
        assertEquals("the PNG pages of the first export are gone", listOf("001.jpg", "002.jpg", "ComicInfo.xml"), dir.list()!!.sorted())
        val f = e.run.value!!.files.single()
        assertEquals(ExportedFile(ch, "Ch 012 - Bread/", 2, dir.listFiles()!!.sumOf { it.length() }), f)
        assertEquals(f.bytes, e.run.value!!.bytes)
    }

    @Test fun anArchiveIsListedWithItsSizeOnDisk() = runBlocking {
        val (m, ch) = manga(2)
        val e = exporter()
        e.export(m, listOf(ch), null, ExportFormat.ZIP, PageQuality.JPEG)
        val zip = File(downloads, "Stardust/Ch 012 - Bread.zip")
        ZipFile(zip).use { z -> assertEquals(listOf("001.jpg", "002.jpg", "ComicInfo.xml"), z.entries().toList().map { it.name }) }
        assertEquals(ExportedFile(ch, "Ch 012 - Bread.zip", 2, zip.length()), e.run.value!!.files.single())
    }

    @Test fun thePlanNamesTheFilesTheSizeAndWhatWillBeReplaced() = runBlocking {
        val (m, ch1) = manga(3)
        val ch2 = repo.import(ImportTarget.NewChapter(m, Chapter(mangaId = m, number = 13f)), pages(2, "b"))
        repo.pageList(ch1).first().let { repo.updatePage(it.copy(stages = setOf(StudioStage.REVIEWED))) }
        val e = exporter()
        e.export(m, listOf(ch1), null, ExportFormat.CBZ)
        targets.free = 12_345
        val plan = e.plan(m, listOf(ch1, ch2), null, ExportFormat.CBZ, PageQuality.PNG)!!
        assertEquals("Panelglass", plan.folder)
        assertEquals("Stardust", plan.mangaFolder)
        assertEquals(listOf("Ch 012 - Bread.cbz", "Ch 013.cbz"), plan.files)
        assertEquals(5, plan.pages)
        assertEquals(50L, plan.bytes)
        assertEquals(12_345L, plan.freeBytes)
        assertEquals(listOf("Ch 012 - Bread.cbz"), plan.existing)
        assertEquals(4, plan.unreviewed)
        val folders = e.plan(m, listOf(ch2), null, ExportFormat.IMAGES, PageQuality.PNG)!!
        assertEquals(listOf("Ch 013/"), folders.files)
        assertTrue("a CBZ is not the folder", folders.existing.isEmpty())
    }

    @Test fun theRunKnowsWhenItStartedAndEndedAndHowLongIsLeft() = runBlocking {
        val (m, ch) = manga(4)
        var clock = 1_000L
        val e = exporter().apply { now = { clock.also { clock += 500 } } }
        e.export(m, listOf(ch), null, ExportFormat.IMAGES)
        val run = e.run.value!!
        assertEquals(1_000L, run.startedAt)
        assertTrue(run.finishedAt > run.startedAt)
        assertEquals(4, run.total)
        // Half the pages written in 10 s: about 10 s left.
        assertEquals(10_000L, run.copy(written = 2, startedAt = 0).remainingMs(10_000))
        assertNull("nothing written yet: no guess", run.copy(written = 0).remainingMs(10_000))
    }

    /** Writes `page:<the page file's text>:<language>`; [failOn] names a page it cannot read. */
    private class FakeComposer(private val read: (StudioPage) -> String) : PageComposer {
        var failOn: String? = null
        var bytesPerPage = 10L
        override suspend fun write(page: StudioPage, tgt: Lang, out: OutputStream, quality: PageQuality): Boolean {
            val tag = read(page)
            if (tag == failOn) return false
            out.write("page:$tag:${tgt.name}".toByteArray())
            return true
        }
        override fun estimateBytes(page: StudioPage, quality: PageQuality) = bytesPerPage
    }

    /** Destinations by URI (null: Downloads), each a plain directory; "permission" = being in [dirs]. */
    private class DirTargets : ExportTargets {
        val dirs = HashMap<String?, File>()
        var failWrites: IOException? = null
        var free: Long? = null
        override fun adopt(uri: String) = dirs.containsKey(uri)
        override fun open(uri: String?): ExportTarget? = dirs[uri]?.let { DirTarget(it) }
        override fun label(uri: String?) = dirs[uri]?.name
        override fun freeBytes(uri: String?) = free

        inner class DirTarget(base: File) : ExportTarget {
            override val root: String = base.path
            override fun list(dir: String) = File(dir).listFiles().orEmpty().map { ExportEntry(it.path, it.name, it.isDirectory) }
            override fun folder(dir: String, name: String): String = File(dir, name).apply { mkdirs() }.path
            override suspend fun write(dir: String, name: String, mime: String, body: suspend (OutputStream) -> Unit) {
                failWrites?.let { throw it }
                val f = File(dir, name)
                val existed = f.exists()
                try {
                    f.outputStream().use { body(it) }
                } catch (e: Throwable) {
                    if (!existed) f.delete()
                    throw e
                }
            }
            override fun delete(entry: ExportEntry) { File(entry.id).delete() }
        }
    }

    private companion object {
        const val PICKED = "content://tree/picked"
    }
}
