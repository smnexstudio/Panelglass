package com.smnexstudio.panelglass.feature.studio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.feature.studio.importer.Fixtures
import com.smnexstudio.panelglass.feature.studio.importer.ImportRefused
import com.smnexstudio.panelglass.feature.studio.importer.StudioImporter
import com.smnexstudio.panelglass.feature.studio.importer.StudioImporterTest.PickedFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One import from pick to save, through the real importer and a real database: what it pre-fills, where the pages go
 * (a new manga, an existing one, the chapter being added to, one chapter per folder), and that pages are never left
 * behind when the import is abandoned. Picked files come from a fake documents provider. Robolectric (JUnit 4 via
 * vintage); the main dispatcher is unconfined.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var db: PanelglassDb
    private lateinit var repo: StudioRepository
    private lateinit var settings: SettingsRepository
    private lateinit var importer: StudioImporter
    private val pagesDir get() = File(root, StudioFiles.PAGES)
    private val models = TestViewModels()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        root = File(context.filesDir, "import-vm-test").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        val files = StudioFiles(root)
        repo = StudioRepository(db, files)
        settings = SettingsRepository(MemoryDataStore())
        importer = StudioImporter(context, files)
        // Robolectric's storage reports nothing free; the import checks free space first. Plenty, here.
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
        PickedFiles.files.clear()
        Robolectric.setupContentProvider(PickedFiles::class.java, PickedFiles.AUTHORITY)
    }

    @After fun tearDown() {
        models.clear()
        db.close()
        root.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun serve(name: String, bytes: ByteArray): Uri {
        PickedFiles.files[name] = File(root, "picked-$name").apply { writeBytes(bytes) }
        return Uri.parse("content://${PickedFiles.AUTHORITY}/$name")
    }

    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun vm(vararg args: Pair<String, String>) = models.keep(ImportViewModel(importer, repo, settings, SavedStateHandle(args.toMap())))

    private suspend fun reviewed(vm: ImportViewModel) = withTimeout(5_000) { vm.ui.first { it !is ImportUi.Reading && it != ImportUi.Picking } }
    private suspend fun done(vm: ImportViewModel) = (withTimeout(5_000) { vm.ui.first { it is ImportUi.Done } } as ImportUi.Done).chapterId
    private fun leftovers(): List<String> = pagesDir.list()?.toList().orEmpty()

    @Test fun pickedImagesBecomeANewMangaInTheOrderShown() = runBlocking<Unit> {
        val vm = vm("source" to "IMAGES")
        vm.onPicked(listOf("p2.png", "p1.png", "p10.png").map { serve(it, Fixtures.png(40, 60)) })
        assertEquals(ImportUi.Review, reviewed(vm))
        assertEquals(listOf("p1.png", "p2.png", "p10.png"), vm.pages.value.map { it.name })
        assertFalse("a new manga needs a title", vm.canSave(vm.destManga.value, vm.chapter.value, vm.newManga.value))
        vm.newManga.value = vm.newManga.value.copy(title = "  Stardust ")
        vm.chapter.value = vm.chapter.value.copy(number = "4")
        assertTrue(vm.canSave(vm.destManga.value, vm.chapter.value, vm.newManga.value))
        vm.save()
        val chapter = repo.getChapter(done(vm))!!
        assertEquals(4f, chapter.number)
        assertEquals("Stardust", repo.getManga(chapter.mangaId)!!.title)
        assertEquals(3, repo.pageList(chapter.id).size)
    }

    /** Settings' languages start the new manga; the order can be changed before saving. */
    @Test fun pagesCanBeReorderedAndRemovedBeforeSaving() = runBlocking<Unit> {
        settings.setDefaultSourceLang(Lang.KO)
        val vm = vm()
        vm.onPicked(listOf("a.png", "b.png", "c.png").map { serve(it, Fixtures.png(40, 60)) })
        reviewed(vm)
        assertEquals(Lang.KO, vm.newManga.value.srcLang)
        val (a, b, c) = vm.pages.value
        vm.move(c.page.file, a.page.file)
        assertEquals(listOf("c.png", "a.png", "b.png"), vm.pages.value.map { it.name })
        vm.remove(b)
        assertEquals(listOf("c.png", "a.png"), vm.pages.value.map { it.name })
        assertFalse("a removed page's directory is deleted", File(root, b.page.dir).exists())
        vm.newManga.value = vm.newManga.value.copy(title = "Stardust")
        vm.save()
        val pages = repo.pageList(done(vm))
        assertEquals(listOf(c.page.file, a.page.file), pages.map { it.file })
    }

    /** ComicInfo names the series and chapter; a manga with that title already in the Studio is chosen. */
    @Test fun aCbzWithComicInfoJoinsTheMangaOfThatTitle() = runBlocking<Unit> {
        repo.createManga(Manga(title = "Invented Series"))
        val first = repo.import(ImportTarget.NewChapter(repo.allMangas().single().id, Chapter(mangaId = 0, number = 2f)), emptyList())
        val info = "<ComicInfo><Series>invented series</Series><Number>3</Number><Title>Night</Title></ComicInfo>"
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("x.cbz", cbz("01.png" to Fixtures.png(40, 60), "ComicInfo.xml" to info.toByteArray()))))
        reviewed(vm)
        assertEquals("Invented Series", vm.destManga.value?.title)
        assertEquals("3", vm.chapter.value.number)
        assertEquals("Night", vm.chapter.value.title)
        vm.save()
        val chapter = repo.getChapter(done(vm))!!
        assertEquals(repo.getChapter(first)!!.mangaId, chapter.mangaId)
        assertEquals("Night", chapter.title)
    }

    /** What a reader app writes about the series (Mihon: the description and its own status field) fills the new manga. */
    @Test fun anArchivesSummaryAltTitleAndStatusFillTheNewManga() = runBlocking<Unit> {
        val info = "<ComicInfo><Series>Invented Rivers</Series><LocalizedSeries>Kawa no Hanashi</LocalizedSeries>" +
            "<Summary>An invented summary.</Summary><Number>1</Number>" +
            "<ty:PublishingStatusTachiyomi xmlns:ty=\"http://www.w3.org/2001/XMLSchema\">Ongoing</ty:PublishingStatusTachiyomi></ComicInfo>"
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("x.cbz", cbz("01.png" to Fixtures.png(40, 60), "ComicInfo.xml" to info.toByteArray()))))
        reviewed(vm)
        assertEquals("An invented summary.", vm.newManga.value.summary)
        assertEquals("Kawa no Hanashi", vm.newManga.value.altTitle)
        assertEquals(com.smnexstudio.panelglass.core.model.MangaStatus.ONGOING, vm.newManga.value.status)
        vm.save()
        val saved = repo.getManga(repo.getChapter(done(vm))!!.mangaId)!!
        assertEquals("An invented summary.", saved.summary)
        assertEquals("Kawa no Hanashi", saved.altTitle)
        assertEquals(com.smnexstudio.panelglass.core.model.MangaStatus.ONGOING, saved.status)
    }

    /** Without a number of its own, a chapter for an existing manga is numbered after its last. */
    @Test fun anExistingMangaSuggestsTheNextChapterNumber() = runBlocking<Unit> {
        val mangaId = repo.createManga(Manga(title = "Stardust"))
        repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0, number = 7.5f)), emptyList())
        val vm = vm()
        vm.onPicked(listOf(serve("a.png", Fixtures.png(40, 60))))
        reviewed(vm)
        vm.pickDestination(repo.getManga(mangaId))
        withTimeout(5_000) { vm.chapter.first { it.number.isNotBlank() } }
        assertEquals("8", vm.chapter.value.number)
    }

    @Test fun anArchiveWithAFolderPerChapterBecomesOneChapterEach() = runBlocking<Unit> {
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("pack.cbz", cbz(
            "Chapter 66/01.png" to Fixtures.png(40, 60), "Chapter 66/02.png" to Fixtures.png(40, 60),
            "Chapter 67 - Rain/01.png" to Fixtures.png(40, 60),
        ))))
        reviewed(vm)
        assertEquals(listOf("66", "67"), vm.folders.value.map { it.number })
        assertTrue(vm.splitting())
        vm.newManga.value = vm.newManga.value.copy(title = "Stardust")
        vm.save()
        val firstId = done(vm)
        val chapters = repo.chapterList(repo.getChapter(firstId)!!.mangaId)
        assertEquals(listOf(66f, 67f), chapters.map { it.number })
        assertEquals("Rain", chapters[1].title)
        assertEquals(listOf(2, 1), chapters.map { repo.pageList(it.id).size })
    }

    /**
     * A reader app's download folder zipped whole (`<source>/<series>/<chapter>.cbz`): each series becomes its own
     * manga, titled from its ComicInfo, and one already in the Studio gets its chapters.
     */
    @Test fun aPackOfSeveralSeriesBecomesOneMangaEach() = runBlocking<Unit> {
        val earlier = repo.createManga(Manga(title = "Invented Stars"))
        fun chapter(series: String, n: Int, pages: Int) = cbz(
            *((1..pages).map { "%02d.png".format(it) to Fixtures.png(40, 60) } +
                ("ComicInfo.xml" to "<ComicInfo><Series>$series</Series><Number>$n</Number><Writer>Someone</Writer><Summary>About $series.</Summary></ComicInfo>".toByteArray())).toTypedArray(),
        )
        val pack = cbz(
            "Source/Invented Stars/Chapter 1.cbz" to chapter("Invented Stars", 1, 2),
            "Source/Invented Stars/Chapter 2.cbz" to chapter("Invented Stars", 2, 1),
            "Source/Paper Moons/Chapter 1.cbz" to chapter("Paper Moons", 1, 3),
        )
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("pack.zip", pack)))
        reviewed(vm)
        assertEquals(listOf("Invented Stars" to 2, "Paper Moons" to 1), vm.series.value.map { it.title to it.chapters.size })
        assertTrue(vm.bySeries())
        assertTrue("no title to type: each series has its own", vm.canSave(null, vm.chapter.value, Manga(title = "")))
        vm.save()
        done(vm)
        val all = repo.allMangas().associateBy { it.title }
        assertEquals(setOf("Invented Stars", "Paper Moons"), all.keys)
        assertEquals(earlier, all.getValue("Invented Stars").id)
        assertEquals(listOf(1f, 2f), repo.chapterList(earlier).map { it.number })
        val moons = all.getValue("Paper Moons")
        assertEquals("Someone", moons.author)
        assertEquals("About Paper Moons.", moons.summary)
        assertEquals(listOf(3), repo.chapterList(moons.id).map { repo.pageList(it.id).size })
    }

    @Test fun aPackOfSeveralSeriesCanStillGoToOneManga() = runBlocking<Unit> {
        val pack = cbz(
            "Source/Invented Stars/Chapter 1/01.png" to Fixtures.png(40, 60),
            "Source/Paper Moons/Chapter 1/01.png" to Fixtures.png(40, 60),
        )
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("pack.zip", pack)))
        reviewed(vm)
        assertEquals(2, vm.series.value.size) // told apart by the folders holding the chapters
        vm.perSeries.value = false
        assertFalse(vm.bySeries())
        vm.newManga.value = vm.newManga.value.copy(title = "Everything")
        vm.save()
        done(vm)
        assertEquals(listOf("Everything"), repo.allMangas().map { it.title })
    }

    @Test fun pagesAddedToAChapterJoinIt() = runBlocking<Unit> {
        val mangaId = repo.createManga(Manga(title = "Stardust"))
        val chapterId = repo.import(ImportTarget.NewChapter(mangaId, Chapter(mangaId = 0, number = 1f)), emptyList())
        val vm = vm("chapter" to chapterId.toString())
        vm.onPicked(listOf(serve("a.png", Fixtures.png(40, 60)), serve("b.png", Fixtures.png(40, 60))))
        reviewed(vm)
        assertEquals(chapterId, vm.appendChapter.value?.id)
        assertTrue("no title needed to add pages", vm.canSave(null, vm.chapter.value, Manga(title = "")))
        vm.save()
        assertEquals(chapterId, done(vm))
        assertEquals(2, repo.pageList(chapterId).size)
    }

    @Test fun aRefusedPickSaysWhyAndStagesNothing() = runBlocking<Unit> {
        val vm = vm("source" to "ARCHIVE")
        vm.onPicked(listOf(serve("pages.cbz", "not an archive".toByteArray())))
        assertEquals(ImportUi.Failed(ImportRefused.Reason.NOT_AN_ARCHIVE), reviewed(vm))
        assertTrue(leftovers().isEmpty())
    }

    /** A rotation or a process death rebuilds the view model from its saved state: the picker is not opened twice. */
    @Test fun thePickerIsNotOpenedAgainWhenTheScreenIsRebuilt() {
        val saved = SavedStateHandle(mapOf("source" to "ARCHIVE"))
        val first = models.keep(ImportViewModel(importer, repo, settings, saved))
        assertFalse(first.pickerLaunched)
        first.pickerLaunched = true
        // What survives is the handle's bundle-able state, nothing of the first instance.
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        val again = models.keep(ImportViewModel(importer, repo, settings, restored))
        assertTrue(again.pickerLaunched)
        assertEquals(com.smnexstudio.panelglass.feature.studio.importer.ImportSource.ARCHIVE, again.source)
    }

    @Test fun anEmptyPickDoesNothing() {
        val vm = vm()
        vm.onPicked(emptyList())
        assertEquals(ImportUi.Picking, vm.ui.value)
    }

    /** Leaving the import screen before saving deletes every staged page; nothing is written to the database. */
    @Test fun leavingBeforeSavingDeletesTheStagedPages() = runBlocking<Unit> {
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ImportViewModel(importer, repo, settings, SavedStateHandle()) as T
        }
        val vm = ViewModelProvider(store, factory)[ImportViewModel::class.java]
        vm.onPicked(listOf(serve("a.png", Fixtures.png(40, 60)), serve("b.png", Fixtures.png(40, 60))))
        reviewed(vm)
        assertEquals(2, leftovers().size)
        store.clear()
        // The discard runs on its own thread; delay (not a sleep) so the timeout can end the wait.
        withTimeout(5_000) { while (leftovers().isNotEmpty()) delay(20) }
        assertTrue(repo.allMangas().isEmpty())
    }

    @Test fun savingTwiceSavesOnce() = runBlocking<Unit> {
        val vm = vm()
        vm.onPicked(listOf(serve("a.png", Fixtures.png(40, 60))))
        reviewed(vm)
        vm.newManga.value = vm.newManga.value.copy(title = "Stardust")
        vm.save()
        vm.save()
        done(vm)
        assertEquals(1, repo.allMangas().size)
        assertNull("a second save is ignored", (vm.ui.value as? ImportUi.Failed))
    }
}
