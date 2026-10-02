package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.NaturalOrder
import com.smnexstudio.panelglass.feature.studio.ImportGuess
import com.smnexstudio.panelglass.feature.studio.moved
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Picked images through [PageStager], ordered as [StudioImporter.images] orders them, and the preview's reorder. */
class ImageImporterTest {
    @TempDir lateinit var tmp: File

    private val files by lazy { StudioFiles(File(tmp, "studio")) }

    private fun stager(limits: ImportLimits = ImportLimits(), transcoder: Transcoder = Fixtures.FakeTranscoder()) =
        PageStager(files, transcoder, limits)

    @Test fun manyFilesKeepTheNaturalOrder() {
        val names = (1..20).map { "page_$it.jpg" }.shuffled(java.util.Random(3))
        val staged = names.sortedWith(NaturalOrder).map { n ->
            (stager().stage(n, Fixtures.jpeg(800, 1200, exifBytes = 10).inputStream()) as PageStager.Result.Staged).page
        }
        assertEquals((1..20).map { "page_$it.jpg" }, staged.map { it.name })
        assertTrue(staged.all { it.page.file.endsWith("/original.jpg") && it.page.width == 800 && it.page.height == 1200 })
        // The bytes are kept as they were: a JPEG is never re-encoded.
        assertTrue(files.file(staged[0].page.file).readBytes().contentEquals(Fixtures.jpeg(800, 1200, exifBytes = 10)))
    }

    @Test fun aReorderInThePreviewSticks() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "a", "b", "d"), list.moved(2, 0))
        assertEquals(listOf("b", "c", "a", "d"), list.moved(0, 2))
        assertEquals(list, list.moved(1, 1))
        assertEquals(list, list.moved(0, 9))
    }

    @Test fun overCapImagesAreRefusedAndLeaveNoFile() {
        val s = stager(ImportLimits(maxPixels = 10_000_000, maxFileBytes = 1_000_000))
        assertEquals(SkipReason.TOO_MANY_PIXELS, (s.stage("strip.png", Fixtures.png(1000, 20_000).inputStream()) as PageStager.Result.Skipped).reason)
        assertEquals(SkipReason.TOO_LARGE, (s.stage("big.png", Fixtures.png(10, 10, filler = 2_000_000).inputStream()) as PageStager.Result.Skipped).reason)
        assertEquals(SkipReason.NOT_AN_IMAGE, (s.stage("photo.heic", Fixtures.heic().inputStream()) as PageStager.Result.Skipped).reason)
        assertTrue(File(tmp, "studio/pages").listFiles().orEmpty().isEmpty())
    }

    @Test fun convertedImagesHaveTheirOwnCap() {
        val s = stager(ImportLimits(maxDecodePixels = 1_000_000), Fixtures.FakeTranscoder(2000 to 2000))
        assertEquals(SkipReason.TOO_MANY_PIXELS, (s.stage("x.avif", Fixtures.avif().inputStream()) as PageStager.Result.Skipped).reason)
        assertEquals(SkipReason.TOO_MANY_PIXELS, (s.stage("x.gif", Fixtures.gif(2000, 2000).inputStream()) as PageStager.Result.Skipped).reason)
    }

    @Test fun undecodableConversionIsSkipped() {
        val s = stager(transcoder = Fixtures.FakeTranscoder(null))
        assertEquals(SkipReason.UNREADABLE, (s.stage("x.avif", Fixtures.avif().inputStream()) as PageStager.Result.Skipped).reason)
    }

    @Test fun discardRemovesStagedPages() {
        val s = stager()
        val page = (s.stage("1.png", Fixtures.png(10, 10).inputStream()) as PageStager.Result.Staged).page
        assertTrue(files.file(page.page.file).exists())
        s.discard(listOf(page))
        assertFalse(File(files.root, page.page.dir).exists())
    }

    @Test fun titleAndNumberAreGuessedFromTheFileName() {
        assertEquals(ImportGuess.Guess("Blue Box", "12", "2"), ImportGuess.fromName("Blue Box v02 c012"))
        assertEquals(ImportGuess.Guess("", "12.5", ""), ImportGuess.fromName("Chapter 12.5 - The match"))
        assertEquals(ImportGuess.Guess("One Piece", "1100", ""), ImportGuess.fromName("One_Piece_ch1100"))
        assertEquals(ImportGuess.Guess("Dandadan", "150", ""), ImportGuess.fromName("Dandadan 150"))
        assertEquals(ImportGuess.Guess("薬屋のひとりごと", "40", ""), ImportGuess.fromName("薬屋のひとりごと 第40話"))
        assertEquals(ImportGuess.Guess("Artbook", "", ""), ImportGuess.fromName("Artbook"))
    }

    @Test fun pdfPagesAreRenderedAtTwiceTheirSizeCappedAt2000Wide() {
        assertEquals(1190 to 1684, PdfSizing.renderSize(595, 842, 40_000_000)) // A4 at 2x
        assertEquals(2000 to 2830, PdfSizing.renderSize(1190, 1684, 40_000_000)) // capped width
        val (w, h) = PdfSizing.renderSize(800, 60_000, 40_000_000) // a long strip: pixel cap
        assertTrue(w.toLong() * h <= 40_000_000 + 60_000)
        assertEquals(800.0 / 60_000, w.toDouble() / h, 0.001)
    }
}
