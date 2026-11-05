package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.NaturalOrder
import com.smnexstudio.panelglass.feature.studio.FolderChapters
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/**
 * Archives read through [ArchiveReader] into [PageStager], as [StudioImporter.archive] does, without the Android
 * parts. Every archive is built in the test from invented bytes.
 */
class ArchiveImporterTest {
    /** Not @TempDir: on Windows an antivirus scan can hold a staged page for a moment and fail JUnit's cleanup. */
    private val tmp: File = kotlin.io.path.createTempDirectory("archive-importer").toFile()
    private val scratch = File(tmp, "scratch")

    @AfterEach fun cleanUp() { tmp.deleteRecursively() }

    private class Read(val pages: List<StagedPage>, val skipped: List<Pair<String, SkipReason>>, val info: ComicInfo?)

    private fun run(archive: ByteArray, limits: ImportLimits = ImportLimits()): Read {
        val files = StudioFiles(File(tmp, "studio"))
        val stager = PageStager(files, Fixtures.FakeTranscoder(), limits)
        val staged = mutableListOf<StagedPage>()
        val skipped = mutableListOf<Pair<String, SkipReason>>()
        val info = ArchiveReader(limits, scratch).read(
            SeekableInMemoryByteChannel(archive),
            onImage = { name, body ->
                when (val r = stager.stage(name, body)) {
                    is PageStager.Result.Staged -> staged += r.page
                    is PageStager.Result.Skipped -> skipped += r.name to r.reason
                }
            },
            onSkip = { name, reason -> skipped += name to reason },
        )
        return Read(staged.sortedWith(compareBy(NaturalOrder) { it.name }), skipped, info)
    }

    private fun refused(archive: ByteArray, limits: ImportLimits = ImportLimits()) =
        assertThrows<ImportRefused> { run(archive, limits) }.reason

    private fun pageDirs() = File(tmp, "studio/pages").listFiles()?.map { it.name }.orEmpty()

    // --- zip, as before ---

    @Test fun pagesAreOrderedNaturally() {
        val zip = Fixtures.zip(listOf("10.png", "2.png", "1.png", "p3.jpg").map { it to Fixtures.png(100, 150) })
        val r = run(zip)
        assertEquals(listOf("1.png", "2.png", "10.png", "p3.jpg"), r.pages.map { it.name })
        assertTrue(r.pages.all { it.page.width == 100 && it.page.height == 150 })
    }

    @Test fun macOsFilesHiddenFilesAndKnownNonPagesAreLeftOutSilently() {
        val zip = Fixtures.zip(
            listOf(
                "ch1/001.png" to Fixtures.png(10, 10),
                "__MACOSX/ch1/._001.png" to ByteArray(20),
                "ch1/.DS_Store" to ByteArray(20),
                "ch1/Thumbs.db" to ByteArray(20),
                "ch1/readme.txt" to "hi".toByteArray(),
                "ch1/release.nfo" to "hi".toByteArray(),
                "ch1/003.jpg.tmp" to ByteArray(20), // an unfinished download a reader app left behind
                "ch1/002.webp" to Fixtures.webpLossy(10, 10),
            ),
        )
        val r = run(zip)
        assertEquals(listOf("ch1/001.png", "ch1/002.webp"), r.pages.map { it.name })
        assertTrue(r.skipped.isEmpty(), r.skipped.toString())
    }

    @Test fun entryNamesNeverBecomePaths() {
        val zip = Fixtures.zip(listOf("../../../../evil.png" to Fixtures.png(10, 10), "/abs/2.png" to Fixtures.png(10, 10)))
        val r = run(zip)
        assertEquals(2, r.pages.size)
        r.pages.forEach { assertTrue(it.page.file.matches(Regex("pages/[0-9a-f]{32}/original\\.png")), it.page.file) }
        assertFalse(File(tmp, "evil.png").exists())
        assertFalse(File(tmp.parentFile, "evil.png").exists())
    }

    @Test fun comicInfoPrefills() {
        val xml = "<?xml version=\"1.0\"?><ComicInfo><Series>Blue Box</Series><Number>12</Number><Title>Match</Title></ComicInfo>"
        val info = run(Fixtures.zip(listOf("ComicInfo.xml" to xml.toByteArray(), "1.png" to Fixtures.png(10, 10)))).info!!
        assertEquals("Blue Box", info.series)
        assertEquals("12", info.number)
        assertEquals("Match", info.title)
    }

    @Test fun oversizedComicInfoIsIgnored() {
        val big = "<ComicInfo><Series>" + "x".repeat(300_000) + "</Series></ComicInfo>"
        assertNull(run(Fixtures.zip(listOf("ComicInfo.xml" to big.toByteArray(), "1.png" to Fixtures.png(10, 10)))).info)
    }

    @Test fun oversizedEntryRefusesTheArchiveAndLeavesOnlyWhatCameBefore() {
        val limits = ImportLimits(maxFileBytes = 10_000)
        val zip = Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "2.png" to Fixtures.png(10, 10, filler = 50_000)))
        assertEquals(ImportRefused.Reason.TOO_LARGE, refused(zip, limits))
        // The importer discards the pages staged before it.
        assertEquals(1, pageDirs().size)
    }

    @Test fun oversizedStoredEntryIsRefusedFromItsHeader() {
        val limits = ImportLimits(maxFileBytes = 10_000)
        val zip = Fixtures.zip(listOf("big.png" to Fixtures.png(10, 10, filler = 50_000)), stored = true)
        assertEquals(ImportRefused.Reason.TOO_LARGE, refused(zip, limits))
    }

    @Test fun aBombOfferedAsAPageStopsAtTheTotalCap() {
        // 64 MB of zeros compresses to ~64 KB; offered as a page (unknown extension), unpacking it still counts.
        val limits = ImportLimits(maxFileBytes = 1L shl 40, maxTotalBytes = 8L * 1024 * 1024)
        val zip = Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "padding.bin" to ByteArray(64 * 1024 * 1024)))
        assertTrue(zip.size < 1024 * 1024)
        assertEquals(ImportRefused.Reason.TOO_LARGE, refused(zip, limits))
    }

    @Test fun aBombInALeftOutEntryIsNeverUnpacked() {
        val limits = ImportLimits(maxFileBytes = 1L shl 40, maxTotalBytes = 8L * 1024 * 1024)
        val zip = Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "notes.txt" to ByteArray(64 * 1024 * 1024)))
        assertEquals(1, run(zip, limits).pages.size)
    }

    @Test fun tooManyEntriesIsRefused() {
        val limits = ImportLimits(maxEntries = 5)
        assertEquals(ImportRefused.Reason.TOO_MANY_ENTRIES, refused(Fixtures.zip((1..6).map { "$it.txt" to ByteArray(1) }), limits))
    }

    @Test fun tooManyPixelsIsSkippedNotImported() {
        val limits = ImportLimits(maxPixels = 1_000_000)
        val r = run(Fixtures.zip(listOf("ok.png" to Fixtures.png(100, 100), "huge.png" to Fixtures.png(2000, 2000))), limits)
        assertEquals(listOf("ok.png"), r.pages.map { it.name })
        assertEquals(listOf(SkipReason.TOO_MANY_PIXELS), r.skipped.map { it.second })
        assertEquals(1, pageDirs().size)
    }

    @Test fun animatedAndAvifPagesAreConverted() {
        val zip = Fixtures.zip(listOf("a.gif" to Fixtures.gif(20, 30), "b.avif" to Fixtures.avif(), "c.webp" to Fixtures.webpExtended(20, 30, true)))
        val r = run(zip)
        assertEquals(3, r.pages.size)
        assertTrue(r.pages.all { it.page.file.endsWith("/original.png") })
    }

    @Test fun anImageNamedEntryThatIsNotAnImageIsSkipped() {
        val r = run(Fixtures.zip(listOf("1.png" to Fixtures.png(10, 10), "2.jpg" to "not a jpeg".toByteArray())))
        assertEquals(1, r.pages.size)
        assertEquals(SkipReason.NOT_AN_IMAGE, r.skipped.single().second)
    }

    @Test fun somethingThatIsNoArchiveIsRefused() {
        assertEquals(ImportRefused.Reason.NOT_AN_ARCHIVE, refused("this is not an archive at all".toByteArray()))
        assertEquals(ImportRefused.Reason.NOT_AN_ARCHIVE, refused(ByteArray(0)))
    }

    // --- what the stream reader could not open ---

    @Test fun storedEntriesWithTheirSizesAfterTheDataOpen() {
        // The layout streaming zip writers produce for already-compressed images.
        val zip = Fixtures.rawZip(
            listOf(
                Fixtures.RawEntry("002.png".toByteArray(), Fixtures.png(30, 40)),
                Fixtures.RawEntry("001.png".toByteArray(), Fixtures.png(30, 40)),
            ),
        )
        assertEquals(listOf("001.png", "002.png"), run(zip).pages.map { it.name })
    }

    @Test fun shiftJisNamesWithoutTheUtf8FlagAreReadAsShiftJis() {
        val folder = "テスト"
        val zip = Fixtures.rawZip(
            listOf(
                Fixtures.RawEntry("$folder/01.png".toByteArray(charset("Shift_JIS")), Fixtures.png(10, 10)),
                Fixtures.RawEntry("$folder/02.png".toByteArray(Charsets.UTF_8), Fixtures.png(10, 10), utf8Flag = true),
            ),
        )
        assertEquals(listOf("$folder/01.png", "$folder/02.png"), run(zip).pages.map { it.name })
    }

    @Test fun utf8NamesWithoutTheFlagStayUtf8() {
        val raw = "テスト/01.png".toByteArray(Charsets.UTF_8)
        assertEquals("テスト/01.png", ArchiveReader.decodeName(raw))
        assertEquals("plain/01.png", ArchiveReader.decodeName("plain/01.png".toByteArray()))
    }

    @Test fun pagesAreRecognisedByTheirBytesNotTheirExtension() {
        val zip = Fixtures.zip(
            listOf(
                "001" to Fixtures.png(10, 10), // no extension
                "002.jfif" to Fixtures.jpeg(10, 10, exifBytes = 0),
                "003.dat" to Fixtures.webpLossy(10, 10),
                "004.mif" to Fixtures.ftyp("mif1", "mif1", "avif"), // AVIF behind a generic main type
            ),
        )
        val r = run(zip)
        assertEquals(listOf("001", "002.jfif", "003.dat", "004.mif"), r.pages.map { it.name })
        assertTrue(r.skipped.isEmpty())
    }

    @Test fun imagesTheStudioCannotDecodeAreSkippedAsUnsupported() {
        val zip = Fixtures.zip(
            listOf(
                "1.png" to Fixtures.png(10, 10),
                "2.heic" to Fixtures.ftyp("mif1", "mif1", "heic"),
                "3.jxl" to Fixtures.jxlCodestream(),
                "4.jxl" to Fixtures.jxlContainer(),
                "5.bmp" to Fixtures.bmp(),
            ),
        )
        val r = run(zip)
        assertEquals(listOf("1.png"), r.pages.map { it.name })
        assertEquals(List(4) { SkipReason.UNSUPPORTED_FORMAT }, r.skipped.map { it.second })
    }

    @Test fun anEncryptedZipIsRefused() {
        val zip = Fixtures.rawZip(listOf(Fixtures.RawEntry("1.png".toByteArray(), Fixtures.png(10, 10), encrypted = true)))
        assertEquals(ImportRefused.Reason.ENCRYPTED_ARCHIVE, refused(zip))
    }

    @Test fun anEntryPackedWithAMethodThatCannotBeReadIsSkipped() {
        val zip = Fixtures.rawZip(
            listOf(
                Fixtures.RawEntry("1.png".toByteArray(), Fixtures.png(10, 10)),
                Fixtures.RawEntry("2.png".toByteArray(), Fixtures.png(10, 10), method = 98), // PPMd
            ),
        )
        val r = run(zip)
        assertEquals(listOf("1.png"), r.pages.map { it.name })
        assertEquals(listOf("2.png" to SkipReason.UNSUPPORTED_COMPRESSION), r.skipped)
    }

    // --- 7z and RAR ---

    @Test fun aSevenZipArchiveIsReadWithItsComicInfo() {
        val xml = "<ComicInfo><Series>Invented</Series><Number>4</Number></ComicInfo>"
        val archive = Fixtures.sevenZ(
            listOf(
                "ch/10.png" to Fixtures.png(20, 30),
                "ch/2.png" to Fixtures.png(20, 30),
                "ch/notes.txt" to "invented".toByteArray(),
                "ComicInfo.xml" to xml.toByteArray(),
            ),
        )
        val r = run(archive)
        assertEquals(listOf("ch/2.png", "ch/10.png"), r.pages.map { it.name })
        assertEquals("Invented", r.info?.series)
    }

    @Test fun formatsAreToldApartByTheirFirstBytes() {
        fun format(b: ByteArray) = ArchiveReader.formatOf(SeekableInMemoryByteChannel(b))
        assertEquals(ArchiveReader.Format.ZIP, format(Fixtures.zip(listOf("1.png" to Fixtures.png(1, 1)))))
        assertEquals(ArchiveReader.Format.SEVEN_Z, format(Fixtures.sevenZ(listOf("1.png" to Fixtures.png(1, 1)))))
        assertEquals(ArchiveReader.Format.RAR, format(Fixtures.rar()))
        assertEquals(ArchiveReader.Format.UNKNOWN, format("PK".toByteArray()))
        assertEquals(ArchiveReader.Format.UNKNOWN, format(ByteArray(0)))
    }

    @Test fun aRarArchiveIsRefusedWithItsOwnReason() {
        assertEquals(ImportRefused.Reason.RAR, refused(Fixtures.rar()))
    }

    @Test fun aSevenZipBombStopsAtTheTotalCap() {
        val limits = ImportLimits(maxFileBytes = 1L shl 40, maxTotalBytes = 4L * 1024 * 1024)
        val archive = Fixtures.sevenZ(listOf("1.png" to Fixtures.png(10, 10), "padding.bin" to ByteArray(16 * 1024 * 1024)))
        assertTrue(archive.size < 1024 * 1024)
        assertEquals(ImportRefused.Reason.TOO_LARGE, refused(archive, limits))
    }

    // --- archives inside the archive ---

    @Test fun eachInnerArchiveBecomesAChapterFolder() {
        val inner1 = Fixtures.zip(listOf("img/01.png" to Fixtures.png(10, 10), "img/02.png" to Fixtures.png(10, 10)))
        val inner2 = Fixtures.sevenZ(listOf("01.png" to Fixtures.png(10, 10)))
        val pack = Fixtures.zip(listOf("Pack/Chapter 2.7z" to inner2, "Pack/Chapter 1.cbz" to inner1))
        val r = run(pack)
        assertEquals(listOf("Pack/Chapter 1/img 01.png", "Pack/Chapter 1/img 02.png", "Pack/Chapter 2/01.png"), r.pages.map { it.name })
        val chapters = FolderChapters.of(r.pages)
        assertEquals(listOf("1" to 2, "2" to 1), chapters.map { it.number to it.pages })
        assertTrue(scratch.listFiles().isNullOrEmpty(), "inner archives are deleted once read")
    }

    @Test fun everyComicInfoIsReportedWithTheFolderOfItsPages() {
        fun info(series: String) = "<ComicInfo><Series>$series</Series></ComicInfo>".toByteArray()
        val pack = Fixtures.zip(
            listOf(
                "ComicInfo.xml" to info("Top"),
                "Src/Stars/Chapter 1.cbz" to Fixtures.zip(listOf("01.png" to Fixtures.png(10, 10), "ComicInfo.xml" to info("Stars"))),
                "Src/Moons/Chapter 1.7z" to Fixtures.sevenZ(listOf("01.png" to Fixtures.png(10, 10), "ComicInfo.xml" to info("Moons"))),
                "Loose/Chapter 9/ComicInfo.xml" to info("Loose"),
                "Loose/Chapter 9/01.png" to Fixtures.png(10, 10),
            ),
        )
        val seen = mutableMapOf<String, String>()
        ArchiveReader(ImportLimits(), scratch).read(
            SeekableInMemoryByteChannel(pack), onImage = { _, _ -> }, onComicInfo = { folder, i -> seen[folder] = i.series },
        )
        assertEquals(mapOf("" to "Top", "Src/Stars/Chapter 1" to "Stars", "Src/Moons/Chapter 1" to "Moons", "Loose/Chapter 9" to "Loose"), seen)
    }

    @Test fun anArchiveTwoLevelsDeepIsSkipped() {
        val deepest = Fixtures.zip(listOf("01.png" to Fixtures.png(10, 10)))
        val inner = Fixtures.zip(listOf("01.png" to Fixtures.png(10, 10), "more.zip" to deepest))
        val r = run(Fixtures.zip(listOf("Chapter 1.cbz" to inner)))
        assertEquals(listOf("Chapter 1/01.png"), r.pages.map { it.name })
        assertEquals(listOf("Chapter 1/more.zip" to SkipReason.NESTED_ARCHIVE), r.skipped)
    }

    @Test fun aBrokenOrRarInnerArchiveIsSkippedNotFatal() {
        val pack = Fixtures.zip(
            listOf(
                "Chapter 1.cbz" to Fixtures.zip(listOf("01.png" to Fixtures.png(10, 10))),
                "Chapter 2.cbz" to "broken".toByteArray(),
                "Chapter 3.cbr" to Fixtures.rar(),
                "Chapter 4.cbz" to Fixtures.rar(), // a RAR named .cbz
            ),
        )
        val r = run(pack)
        assertEquals(listOf("Chapter 1/01.png"), r.pages.map { it.name })
        assertEquals(
            listOf(
                "Chapter 2.cbz" to SkipReason.UNREADABLE,
                "Chapter 3.cbr" to SkipReason.UNSUPPORTED_FORMAT,
                "Chapter 4.cbz" to SkipReason.UNSUPPORTED_FORMAT,
            ),
            r.skipped,
        )
    }

    @Test fun innerArchivesShareTheOuterCaps() {
        val limits = ImportLimits(maxPages = 3)
        val inner = Fixtures.zip((1..2).map { "$it.png" to Fixtures.png(10, 10) })
        val pack = Fixtures.zip(listOf("a.cbz" to inner, "b.cbz" to inner))
        assertEquals(ImportRefused.Reason.TOO_MANY_PAGES, refused(pack, limits))
    }

    @Test fun readAtMostReadsAcrossShortReadsAndStopsAtTheEnd() {
        // A stream that hands out 3 bytes per read, as an archive entry's decoder may.
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
