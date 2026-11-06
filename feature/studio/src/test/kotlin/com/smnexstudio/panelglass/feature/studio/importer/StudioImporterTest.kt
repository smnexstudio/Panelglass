package com.smnexstudio.panelglass.feature.studio.importer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * The import orchestrator over picked files (SAF content URIs, served by Robolectric's content resolver): what is
 * staged, in which order, and that a refused, failed or cancelled import leaves no page directory behind.
 * Robolectric (JUnit 4 via vintage) for the content resolver.
 */
@RunWith(RobolectricTestRunner::class)
class StudioImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var importer: StudioImporter
    private val pagesDir get() = File(root, StudioFiles.PAGES)

    @Before fun setUp() {
        root = File(context.filesDir, "importer-test").apply { deleteRecursively(); mkdirs() }
        importer = StudioImporter(context, StudioFiles(root))
        // Robolectric's storage reports nothing free; the import checks free space first. Plenty, here.
        ShadowStatFs.registerStats(root, 1_000_000, 1_000_000, 1_000_000)
        PickedFiles.files.clear()
        Robolectric.setupContentProvider(PickedFiles::class.java, PickedFiles.AUTHORITY)
    }

    @After fun tearDown() { root.deleteRecursively() }

    /** A file the system picker would hand over: a content URI with its display name and size. */
    private fun serve(name: String, bytes: ByteArray): Uri {
        PickedFiles.files[name] = File(root, "picked-$name").apply { writeBytes(bytes) }
        return Uri.parse("content://${PickedFiles.AUTHORITY}/$name")
    }

    /** Stands in for the documents provider behind the system file picker. */
    class PickedFiles : ContentProvider() {
        companion object {
            const val AUTHORITY = "picker.test"
            val files = HashMap<String, File>()
        }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val name = uri.lastPathSegment!!
            val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            return MatrixCursor(cols).apply {
                addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) name else if (it == OpenableColumns.SIZE) files[name]?.length() else null })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(files[uri.lastPathSegment] ?: throw FileNotFoundException(uri.toString()), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    /** Page directories left on disk (staged pages not yet saved, or leftovers). */
    private fun leftovers(): List<String> = pagesDir.list()?.toList().orEmpty()

    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    private fun refusal(block: suspend () -> Unit): ImportRefused.Reason {
        try { runBlocking { block() } } catch (e: ImportRefused) { return e.reason }
        fail("expected the import to be refused")
        error("unreachable")
    }

    @Test fun pickedImagesAreStagedInNaturalOrderWithProgress() = runBlocking<Unit> {
        val uris = listOf("p10.png", "p2.png", "p1.png").map { serve(it, Fixtures.png(40, 60)) }
        val progress = mutableListOf<Pair<Int, Int>>()
        val staging = importer.images(uris) { d, t -> progress += d to t }
        assertEquals(listOf("p1.png", "p2.png", "p10.png"), staging.pages.map { it.name })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        staging.pages.forEach { assertTrue(File(root, it.page.file).isFile) }
        assertEquals(3, leftovers().size)
    }

    /** One unreadable file (a revoked permission, a moved file) is skipped; the rest of the pick is imported. */
    @Test fun anUnreadableFileIsSkippedAndTheRestImported() = runBlocking<Unit> {
        val good = serve("a.png", Fixtures.png(40, 60))
        val missing = Uri.parse("content://picker.test/gone.png")
        val junk = serve("b.png", "not an image at all".toByteArray())
        val staging = importer.images(listOf(good, missing, junk)) { _, _ -> }
        assertEquals(listOf("a.png"), staging.pages.map { it.name })
        assertEquals(setOf("gone.png", "b.png"), staging.skipped.map { it.first }.toSet())
        assertEquals("skipped files leave no directory", 1, leftovers().size)
    }

    @Test fun aPickWithNoImageIsRefusedAndLeavesNothing() {
        val uris = listOf(serve("a.png", "text".toByteArray()), serve("b.jpg", ByteArray(10)))
        assertEquals(ImportRefused.Reason.NO_PAGES, refusal { importer.images(uris) { _, _ -> } })
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun tooManyPagesIsRefusedBeforeAnythingIsStaged() {
        val uris = (0..importer.limits.maxPages).map { Uri.parse("content://picker.test/p$it.png") }
        assertEquals(ImportRefused.Reason.TOO_MANY_PAGES, refusal { importer.images(uris) { _, _ -> } })
        assertEquals(emptyList<String>(), leftovers())
    }

    /** Leaving the import screen mid-way: what was staged so far is deleted. */
    @Test fun aCancelledImportLeavesNoPageBehind() = runBlocking<Unit> {
        val uris = (1..5).map { serve("p$it.png", Fixtures.png(40, 60)) }
        val firstDone = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            importer.images(uris) { done, _ ->
                if (done == 1) { firstDone.complete(Unit); runBlocking { proceed.await() } }
            }
        }
        withTimeout(10_000) { firstDone.await() }
        job.cancel()
        proceed.complete(Unit)
        try { job.await(); fail("the import should have been cancelled") } catch (_: CancellationException) {}
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun aCbzIsStagedWithItsComicInfoAndTitle() = runBlocking<Unit> {
        val info = "<ComicInfo><Series>Invented Series</Series><Number>3</Number></ComicInfo>"
        val bytes = cbz("02.png" to Fixtures.png(40, 60), "01.png" to Fixtures.png(40, 60), "ComicInfo.xml" to info.toByteArray())
        val staging = importer.archive(serve("Invented Series 03.cbz", bytes)) { _, _ -> }
        assertEquals(listOf("01.png", "02.png"), staging.pages.map { it.name })
        assertEquals("Invented Series", staging.comicInfo?.series)
        assertEquals("Invented Series 03", staging.sourceName)
    }

    /** The name doesn't decide: a plain `.zip`, a `.7z`, and a 7z named `.cbz` all import. */
    @Test fun zipAnd7zImportWhateverTheirName() = runBlocking<Unit> {
        val zip = importer.archive(serve("pages.zip", cbz("01.png" to Fixtures.png(40, 60)))) { _, _ -> }
        assertEquals(listOf("01.png"), zip.pages.map { it.name })
        val seven = Fixtures.sevenZ(listOf("02.png" to Fixtures.png(40, 60), "01.png" to Fixtures.png(40, 60)))
        assertEquals(listOf("01.png", "02.png"), importer.archive(serve("pages.cb7", seven)) { _, _ -> }.pages.map { it.name })
        assertEquals(2, importer.archive(serve("disguised.cbz", seven)) { _, _ -> }.pages.size)
        assertEquals(emptyList<String>(), File(context.cacheDir, "import").list()?.toList().orEmpty())
    }

    @Test fun anInnerArchiveSkippedForAReasonIsListed() = runBlocking<Unit> {
        val pack = cbz("Chapter 1.cbz" to cbz("01.png" to Fixtures.png(40, 60)), "Chapter 2.cbr" to Fixtures.rar())
        val staging = importer.archive(serve("pack.zip", pack)) { _, _ -> }
        assertEquals(listOf("Chapter 1/01.png"), staging.pages.map { it.name })
        assertEquals(listOf("Chapter 2.cbr" to SkipReason.UNSUPPORTED_FORMAT), staging.skipped)
    }

    @Test fun aRarOrSomethingElseIsRefusedAndLeavesNothing() {
        assertEquals(ImportRefused.Reason.RAR, refusal { importer.archive(serve("chapter.cbr", Fixtures.rar())) { _, _ -> } })
        assertEquals(ImportRefused.Reason.NOT_AN_ARCHIVE, refusal { importer.archive(serve("notes.cbz", "invented".toByteArray())) { _, _ -> } })
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun anArchiveWithoutPagesIsRefusedAndLeavesNothing() {
        val uri = serve("empty.cbz", cbz("readme.txt" to "invented".toByteArray()))
        assertEquals(ImportRefused.Reason.NO_PAGES, refusal { importer.archive(uri) { _, _ -> } })
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun discardDeletesStagedPages() = runBlocking<Unit> {
        val staging = importer.images(listOf(serve("a.png", Fixtures.png(40, 60)), serve("b.png", Fixtures.png(40, 60)))) { _, _ -> }
        importer.discard(staging.pages)
        assertEquals(emptyList<String>(), leftovers())
    }
}
