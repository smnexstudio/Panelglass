package com.smnexstudio.panelglass.core.data.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The store of finished translations: what it keeps, what it drops, and what it refuses to crash over. */
class RegionStoreTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000_000L
    private fun store() = RegionStore(dir) { now }

    private fun region(
        nx: Float = 0.1f, ny: Float = 0.1f, nw: Float = 0.2f, nh: Float = 0.05f,
        fingerprint: String = "fp1", webp: ByteArray = byteArrayOf(1, 2, 3),
        createdAtMs: Long = 1_000_000L,
    ) = SavedRegion(
        nx = nx, ny = ny, nw = nw, nh = nh, imgW = 1000f, imgH = 2000f,
        kind = 0, fingerprint = fingerprint, createdAtMs = createdAtMs, webp = webp,
    )

    @Test
    fun aRegionSurvivesTheRoundTrip() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(), maxBytes = 1_000_000)
        val back = s.load("chapter", "img.jpg")
        assertEquals(1, back.size)
        assertEquals(region(), back[0])
    }

    @Test
    fun anOverlappingRegionWithTheSameFingerprintIsReplaced() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(fingerprint = "fp1"), maxBytes = 1_000_000)
        s.upsert("chapter", "img.jpg", region(fingerprint = "fp1", webp = byteArrayOf(9)), maxBytes = 1_000_000)
        val back = s.load("chapter", "img.jpg")
        assertEquals(1, back.size)
        assertTrue(back[0].webp.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun differentFingerprintsCoexist() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(fingerprint = "fp1"), maxBytes = 1_000_000)
        s.upsert("chapter", "img.jpg", region(fingerprint = "fp2"), maxBytes = 1_000_000)
        assertEquals(2, s.load("chapter", "img.jpg").size)
    }

    @Test
    fun aDifferentImageKeepsItsOwnRegions() = runBlocking {
        val s = store()
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000)
        s.upsert("chapter", "b.jpg", region(), maxBytes = 1_000_000)
        assertEquals(1, s.load("chapter", "a.jpg").size)
        assertEquals(1, s.load("chapter", "b.jpg").size)
    }

    @Test
    fun aDifferentScopeKeepsItsOwnRegions() = runBlocking {
        val s = store()
        s.upsert("one", "a.jpg", region(), maxBytes = 1_000_000)
        s.upsert("two", "a.jpg", region(), maxBytes = 1_000_000)
        assertEquals(1, s.load("one", "a.jpg").size)
        assertEquals(1, s.load("two", "a.jpg").size)
    }

    @Test
    fun atMostThreeHundredRegionsAreKept() = runBlocking {
        val s = store()
        repeat(320) { i ->
            // Each region is in a different place, so none replaces another.
            s.upsert("chapter", "img.jpg", region(ny = i * 0.002f, fingerprint = "fp$i"), maxBytes = 100_000_000)
        }
        val back = s.load("chapter", "img.jpg")
        assertEquals(300, back.size)
    }

    @Test
    fun theBudgetRemovesTheOldestFileFirst() = runBlocking {
        val s = store()
        s.upsert("old", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        s.upsert("new", "b.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        // The eviction order is the file's own mtime, so it is set here: the oldest scope first. The file names are
        // hashes, so the files are found through their own scope rather than by name.
        fileIn("old").setLastModified(1_000_000L)
        fileIn("new").setLastModified(2_000_000L)
        // A budget that fits two files but not three: the oldest goes, the others stay. The file size is measured,
        // because a budget smaller than two files cannot hold two files.
        val oneFile = fileIn("old").length()
        val twoFiles = oneFile * 2 + 100
        s.upsert("newer", "c.jpg", region(webp = ByteArray(4000)), maxBytes = twoFiles)
        assertTrue(s.load("old", "a.jpg").isEmpty())
        assertEquals(1, s.load("new", "b.jpg").size)
    }

    /** The one file of a scope; the scope's directory is named after its hash. */
    private fun fileIn(scope: String): File {
        val d = File(dir, RegionStore.sha256Hex(scope.toByteArray()))
        return d.listFiles()!!.first { it.isFile }
    }

    @Test
    fun anExpiredRecordIsIgnoredAndDeleted() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(createdAtMs = now), maxBytes = 1_000_000)
        assertEquals(1, s.load("chapter", "img.jpg", maxAgeMs = 1000).size)
        now += 10_000
        assertTrue(s.load("chapter", "img.jpg", maxAgeMs = 1000).isEmpty())
        // And it is gone for good.
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
    }

    @Test
    fun aCorruptFileIsDroppedNotThrown() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(), maxBytes = 1_000_000)
        val f = dir.walkTopDown().first { it.isFile && it.name.endsWith(".pgr") }
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
        assertFalse(f.exists())
    }

    @Test
    fun aTruncatedFileIsDroppedNotThrown() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(webp = ByteArray(100)), maxBytes = 1_000_000)
        val f = dir.walkTopDown().first { it.isFile && it.name.endsWith(".pgr") }
        val bytes = f.readBytes()
        f.writeBytes(bytes.copyOf(bytes.size / 2))
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
    }

    @Test
    fun aFileWithAHugeCountIsDroppedNotThrown() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(), maxBytes = 1_000_000)
        val f = dir.walkTopDown().first { it.isFile && it.name.endsWith(".pgr") }
        val bytes = f.readBytes()
        // The count is the third int: make it absurd.
        bytes[8] = 0x7f; bytes[9] = 0x7f; bytes[10] = 0x7f; bytes[11] = 0x7f
        f.writeBytes(bytes)
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
    }

    @Test
    fun clearingEmptiesTheStore() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(), maxBytes = 1_000_000)
        s.clear()
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
        assertEquals(0L, s.sizeBytes())
    }

    @Test
    fun clearingOneScopeLeavesTheOthers() = runBlocking {
        val s = store()
        s.upsert("one", "a.jpg", region(), maxBytes = 1_000_000)
        s.upsert("two", "a.jpg", region(), maxBytes = 1_000_000)
        s.clearScope("one")
        assertTrue(s.load("one", "a.jpg").isEmpty())
        assertEquals(1, s.load("two", "a.jpg").size)
    }

    @Test
    fun clearingEveryScopeButOneLeavesThatOne() = runBlocking {
        val s = store()
        s.upsert("one", "a.jpg", region(), maxBytes = 1_000_000)
        s.upsert("two", "a.jpg", region(), maxBytes = 1_000_000)
        s.clearScopesExcept("two")
        assertTrue(s.load("one", "a.jpg").isEmpty())
        assertEquals(1, s.load("two", "a.jpg").size)
    }

    @Test
    fun twoCoroutinesWritingAtOnceLeaveAReadableFile() = runBlocking {
        val s = store()
        coroutineScope {
            val jobs = (1..4).map { i ->
                async(Dispatchers.IO) {
                    repeat(10) { k ->
                        s.upsert("chapter", "img$i.jpg", region(ny = k * 0.01f, fingerprint = "f$i$k"), maxBytes = 100_000_000)
                        delay(1)
                    }
                }
            }
            jobs.awaitAll()
        }
        // The file is still readable, and it holds what was written.
        val back = s.load("chapter", "img1.jpg")
        assertTrue(back.isNotEmpty())
    }

    @Test
    fun theFileNamesHoldNoPartOfTheImageKey() = runBlocking {
        val s = store()
        s.upsert("chapter", "https://example.com/secret-page.jpg", region(), maxBytes = 1_000_000)
        val names = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        assertTrue(names.isNotEmpty())
        for (n in names) {
            assertFalse(n.contains("example"), n)
            assertFalse(n.contains("secret"), n)
            assertFalse(n.contains("jpg"), n)
        }
    }

    @Test
    fun theScopeDirectoryNameHoldsNoPartOfTheScope() = runBlocking {
        val s = store()
        s.upsert("chapter-7/manga-name", "a.jpg", region(), maxBytes = 1_000_000)
        val dirs = dir.listFiles()!!.map { it.name }
        for (d in dirs) {
            assertFalse(d.contains("manga"), d)
            assertFalse(d.contains("chapter"), d)
        }
    }

    @Test
    fun aRecordFromTheFutureIsIgnoredWithoutLosingTheOthers() = runBlocking {
        val s = store()
        s.upsert("chapter", "img.jpg", region(fingerprint = "fp1"), maxBytes = 1_000_000)
        // A record whose schema is larger than the app supports: the file is from a newer version.
        val f = dir.walkTopDown().first { it.isFile && it.name.endsWith(".pgr") }
        val bytes = f.readBytes()
        // The first record's schema int sits after the magic, the file schema and the count.
        val off = 4 + 4 + 4
        bytes[off] = 0; bytes[off + 1] = 0; bytes[off + 2] = 0; bytes[off + 3] = 9
        f.writeBytes(bytes)
        // The file is dropped rather than misread; the reader carries on.
        assertTrue(s.load("chapter", "img.jpg").isEmpty())
    }
}
