package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.NaturalOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A CBZ read through [CbzReader] into [PageStager], as [StudioImporter.cbz] does, without the Android parts. */
class CbzImporterTest {
    @TempDir lateinit var tmp: File

    private fun run(zip: ByteArray, limits: ImportLimits = ImportLimits()): Triple<List<StagedPage>, List<PageStager.Result.Skipped>, com.smnexstudio.panelglass.core.model.ComicInfo?> {
        val files = StudioFiles(File(tmp, "studio"))
        val stager = PageStager(files, Fixtures.FakeTranscoder(), limits)
        val staged = mutableListOf<StagedPage>()
        val skipped = mutableListOf<PageStager.Result.Skipped>()
        val info = CbzReader(limits).read(zip.inputStream()) { name, body ->
            when (val r = stager.stage(name, body)) {
                is PageStager.Result.Staged -> staged += r.page
                is PageStager.Result.Skipped -> skipped += r
            }
        }
        return Triple(staged.sortedWith(compareBy(NaturalOrder) { it.name }), skipped, info)
    }

    private fun pageDirs() = File(tmp, "studio/pages").listFiles()?.map { it.name }.orEmpty()

    @Test fun pagesAreOrderedNaturally() {
        val zip = Fixtures.zip(listOf("10.png", "2.png", "1.png", "p3.jpg").map { it to Fixtures.png(100, 150) })
        val (pages, _, _) = run(zip)
        assertEquals(listOf("1.png", "2.png", "10.png", "p3.jpg"), pages.map { it.name })
        assertTrue(pages.all { it.page.width == 100 && it.page.height == 150 })
    }

    @Test fun macOsFilesHiddenFilesAndNonImagesAreSkipped() {
        val zip = Fixtures.zip(
            listOf(
                "ch1/001.png" to Fixtures.png(10, 10),
                "__MACOSX/ch1/._001.png" to ByteArray(20),
                "ch1/.DS_Store" to ByteArray(20),
                "ch1/Thumbs.db" to ByteArray(20),
                "ch1/readme.txt" to "hi".toByteArray(),
                "ch1/002.webp" to Fixtures.webpLossy(10, 10),
            ),
        )
        val (pages, skipped, _) = run(zip)
        assertEquals(listOf("ch1/001.png", "ch1/002.webp"), pages.map { it.name })
        assertTrue(skipped.isEmpty())
    }

    @Test fun entryNamesNeverBecomePaths() {
        val zip = Fixtures.zip(listOf("../../../../evil.png" to Fixtures.png(10, 10), "/abs/2.png" to Fixtures.png(10, 10)))
        val (pages, _, _) = run(zip)
        assertEquals(2, pages.size)
        pages.forEach { assertTrue(it.page.file.matches(Regex("pages/[0-9a-f]{32}/original\\.png")), it.page.file) }
        assertFalse(File(tmp, "evil.png").exists())
        assertFalse(File(tmp.parentFile, "evil.png").exists())
    }

    @Test fun comicInfoPrefills() {
        val xml = "<?xml version=\"1.0\"?><ComicInfo><Series>Blue Box</Series><Number>12</Number><Title>Match</Title></ComicInfo>"
        val zip = Fixtures.zip(listOf("ComicInfo.xml" to xml.toByteArray(), "1.png" to Fixtures.png(10, 10)))
        val (_, _, info) = run(zip)
        assertEquals("Blue Box", info!!.series)
        assertEquals("12", info.number)
        assertEquals("Match", info.title)
    }

    @Test fun oversizedComicInfoIsIgnored() {
        val big = "<ComicInfo><Series>" + "x".repeat(300_000) + "</Series></ComicInfo>"
        val (_, _, info) = run(Fixtures.zip(listOf("ComicInfo.xml" to big.toByteArray(), "1.png" to Fixtures.png(10, 10))))
        assertNull(info)
    }

    @Test fun oversizedEntryRefusesTheArchiveAndLeavesNothing() {
        val limits = ImportLimits(maxFileBytes = 10_000)
        val zip = Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "2.png" to Fixtures.png(10, 10, filler = 50_000)))
        val e = assertThrows<ImportRefused> { run(zip, limits) }
        assertEquals(ImportRefused.Reason.TOO_LARGE, e.reason)
        // The refused entry's directory is gone (the importer discards the pages staged before it).
        assertEquals(1, pageDirs().size)
    }

    @Test fun oversizedStoredEntryIsRefusedFromItsHeader() {
        val limits = ImportLimits(maxFileBytes = 10_000)
        val zip = Fixtures.zip(listOf("big.png" to Fixtures.png(10, 10, filler = 50_000)), stored = true)
        assertEquals(ImportRefused.Reason.TOO_LARGE, assertThrows<ImportRefused> { run(zip, limits) }.reason)
    }

    @Test fun bombInASkippedEntryStopsAtTheTotalCap() {
        // 64 MB of zeros compresses to ~64 KB; it is not an image, but inflating it still counts.
        val limits = ImportLimits(maxFileBytes = 1L shl 40, maxTotalBytes = 8L * 1024 * 1024)
        val zip = Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "padding.bin" to ByteArray(64 * 1024 * 1024)))
        assertTrue(zip.size < 1024 * 1024)
        assertEquals(ImportRefused.Reason.TOO_LARGE, assertThrows<ImportRefused> { run(zip, limits) }.reason)
    }

    @Test fun tooManyEntriesIsRefused() {
        val limits = ImportLimits(maxEntries = 5)
        val zip = Fixtures.zip((1..6).map { "$it.txt" to ByteArray(1) })
        assertEquals(ImportRefused.Reason.TOO_MANY_ENTRIES, assertThrows<ImportRefused> { run(zip, limits) }.reason)
    }

    @Test fun tooManyPixelsIsSkippedNotImported() {
        val limits = ImportLimits(maxPixels = 1_000_000)
        val zip = Fixtures.zip(listOf("ok.png" to Fixtures.png(100, 100), "huge.png" to Fixtures.png(2000, 2000)))
        val (pages, skipped, _) = run(zip, limits)
        assertEquals(listOf("ok.png"), pages.map { it.name })
        assertEquals(listOf(SkipReason.TOO_MANY_PIXELS), skipped.map { it.reason })
        assertEquals(1, pageDirs().size)
    }

    @Test fun animatedAndAvifPagesAreConverted() {
        val zip = Fixtures.zip(listOf("a.gif" to Fixtures.gif(20, 30), "b.avif" to Fixtures.avif(), "c.webp" to Fixtures.webpExtended(20, 30, true)))
        val (pages, _, _) = run(zip)
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.page.file.endsWith("/original.png") })
    }

    @Test fun anImageNamedEntryThatIsNotAnImageIsSkipped() {
        val (pages, skipped, _) = run(Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "2.jpg" to "not a jpeg".toByteArray())))
        assertEquals(1, pages.size)
        assertEquals(SkipReason.NOT_AN_IMAGE, skipped.single().reason)
    }

    @Test fun notAZipIsRefused() {
        val e = assertThrows<ImportRefused> { run("this is not a zip file".toByteArray()) }
        assertEquals(ImportRefused.Reason.NOT_A_ZIP, e.reason)
    }

    @Test fun onlyCbzNamesAreTaken() {
        assertTrue(CbzReader.isCbzName("Blue Box c012.cbz"))
        assertTrue(CbzReader.isCbzName("X.CBZ"))
        assertFalse(CbzReader.isCbzName("chapter.zip"))
        assertFalse(CbzReader.isCbzName("chapter.cbr"))
        assertFalse(CbzReader.isCbzName(null))
    }

    @Test fun readAtMostReadsAcrossShortReadsAndStopsAtTheEnd() {
        // A stream that hands out 3 bytes per read, as a zip entry's inflater may.
        fun trickle(size: Int) = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < size) i++ and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= size) return -1
                val n = minOf(3, len, size - i)
                repeat(n) { b[off + it] = (i++ and 0xFF).toByte() }
                return n
            }
        }
        assertEquals((0 until 10).map { it.toByte() }, trickle(100).readAtMost(10).toList(), "exactly n when there is more")
        assertEquals(7, trickle(7).readAtMost(10).size, "what there is when the stream is shorter")
        assertEquals(0, trickle(0).readAtMost(4).size)
    }
}
