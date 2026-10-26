package com.smnexstudio.panelglass.core.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The store's own accounting, and the races around it.
 *
 * The store reports how much disk it uses. That number is what decides whether a translation the reader has paid
 * for is thrown away, so it has to be the truth — and a clear that happens while a write is on its way must not be
 * undone by that write.
 *
 * Robolectric (JUnit 4 via the vintage engine), because the store logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionStoreAccountingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root

    private var now = 1_000_000L
    private fun store() = RegionStore(dir) { now }

    private fun region(
        nx: Float = 0.1f, ny: Float = 0.1f, nw: Float = 0.2f, nh: Float = 0.05f,
        fingerprint: String = "fp1", webp: ByteArray = byteArrayOf(1, 2, 3),
        createdAtMs: Long = 1_000_000L, occurrence: Int = 0,
    ) = SavedRegion(
        nx = nx, ny = ny, nw = nw, nh = nh, imgW = 1000f, imgH = 2000f, kind = 0, occurrence = occurrence,
        fingerprint = fingerprint, createdAtMs = createdAtMs, webp = webp,
    )

    /** The bytes the store's files really hold. */
    private fun actualBytes(): Long =
        dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".old") }
            .sumOf { it.length() }

    @Test
    fun theReportedUsageIsTheBytesOnDisk() = runBlocking {
        val s = store()
        s.upsert("chapter", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        s.upsert("chapter", "b.jpg", region(webp = ByteArray(7000)), maxBytes = 100_000_000)
        assertEquals("the store's own count is not the disk's count", actualBytes(), s.sizeBytes())
    }

    /**
     * A clear must bring the count back to the truth.
     *
     * The old code deleted the files and then asked for a scan that returned the **cached** total, so the store
     * still believed it held the deleted bytes. It then thought it was over its budget and evicted files that were
     * still there — throwing away translations for nothing.
     */
    @Test
    fun clearingAScopeBringsTheCountBackToTheTruth() = runBlocking {
        val s = store()
        s.upsert("one", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        s.upsert("two", "b.jpg", region(webp = ByteArray(7000)), maxBytes = 100_000_000)
        val before = s.sizeBytes()
        assertTrue(before > 0)

        s.clearScope("one")
        assertEquals("the store still counts the files it deleted", actualBytes(), s.sizeBytes())
        assertTrue("the count did not go down", s.sizeBytes() < before)
        assertEquals("the other scope was touched", 1, s.load("two", "b.jpg").size)
    }

    @Test
    fun clearingEveryScopeButOneBringsTheCountBackToTheTruth() = runBlocking {
        val s = store()
        s.upsert("one", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        s.upsert("two", "b.jpg", region(webp = ByteArray(7000)), maxBytes = 100_000_000)
        s.clearScopesExcept("two")
        assertEquals("the store still counts the files it deleted", actualBytes(), s.sizeBytes())
        assertEquals(1, s.load("two", "b.jpg").size)
    }

    /**
     * A file that could not be read is deleted, and the count follows: the bytes are gone.
     */
    @Test
    fun aCorruptFileStopsBeingCounted() = runBlocking {
        val s = store()
        s.upsert("chapter", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        val before = s.sizeBytes()
        val f = dir.walkTopDown().first { it.isFile && it.name.endsWith(".pgr") }
        f.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertTrue(s.load("chapter", "a.jpg").isEmpty())
        assertEquals("the damaged file is still counted", actualBytes(), s.sizeBytes())
        assertTrue("the count did not go down", s.sizeBytes() < before)
    }

    /**
     * The expired records are rewritten away, and the count follows.
     */
    @Test
    fun theExpiredRecordsLeaveTheCountRight() = runBlocking {
        val s = store()
        s.upsert("chapter", "a.jpg", region(webp = ByteArray(4000), createdAtMs = now), maxBytes = 100_000_000)
        now += 10_000
        s.load("chapter", "a.jpg", maxAgeMs = 1000)
        assertEquals("the expired bytes are still counted", actualBytes(), s.sizeBytes())
    }

    /**
     * A write that started before a clear must not undo that clear.
     *
     * The epoch is the store's own: a clear advances it, and a write that carries the older epoch is refused.
     * The check is inside the lock, so there is no window between the check and the write.
     */
    @Test
    fun aWriteFromBeforeTheClearIsRefused() = runBlocking {
        val s = store()
        // The epoch a writer takes when it starts.
        val epoch = s.epochOf("chapter")
        // The clear happens while the write is on its way.
        s.clearScope("chapter")
        // The write arrives afterwards.
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000, epoch = epoch)
        assertTrue("a stale write resurrected what was cleared", s.load("chapter", "a.jpg").isEmpty())
    }

    /**
     * A write that starts after the clear is fine: the reader is allowed to save again.
     */
    @Test
    fun aWriteFromAfterTheClearIsKept() = runBlocking {
        val s = store()
        s.clearScope("chapter")
        val epoch = s.epochOf("chapter")
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000, epoch = epoch)
        assertEquals(1, s.load("chapter", "a.jpg").size)
    }

    /**
     * A clear of one scope does not refuse the writes of another.
     */
    @Test
    fun aClearOfOneScopeDoesNotRefuseAnother() = runBlocking {
        val s = store()
        val one = s.epochOf("one")
        val two = s.epochOf("two")
        s.clearScope("one")
        s.upsert("one", "a.jpg", region(), 1_000_000, one)
        s.upsert("two", "b.jpg", region(), 1_000_000, two)
        assertTrue(s.load("one", "a.jpg").isEmpty())
        assertEquals(1, s.load("two", "b.jpg").size)
    }

    /**
     * A long webtoon has more bubbles than the old cap of 300. The cap must not throw them away while the disk
     * has room: the byte budget is what evicts.
     */
    @Test
    fun moreThanThreeHundredRegionsAreKept() = runBlocking {
        val s = store()
        repeat(320) { i ->
            s.upsert("chapter", "img.jpg", region(ny = i * 0.002f, fingerprint = "fp$i"), maxBytes = 100_000_000)
        }
        assertEquals("the record cap threw away good translations", 320, s.load("chapter", "img.jpg").size)
    }

    /**
     * The same picture twice on one page: the two records are both kept, and they are told apart.
     */
    @Test
    fun twoOccurrencesOfTheSameKeyAreBothKeptAndToldApart() = runBlocking {
        val s = store()
        s.upsert("chapter", "same.jpg", region(ny = 0.1f, occurrence = 0), maxBytes = 1_000_000)
        s.upsert("chapter", "same.jpg", region(ny = 0.8f, occurrence = 1), maxBytes = 1_000_000)
        val back = s.load("chapter", "same.jpg")
        assertEquals(2, back.size)
        assertEquals(listOf(0, 1), back.map { it.occurrence }.sorted())
    }

    /**
     * A record of the same bubble, but of the other picture, does not replace the first one.
     */
    @Test
    fun aRecordOfAnotherOccurrenceDoesNotReplaceTheFirst() = runBlocking {
        val s = store()
        s.upsert("chapter", "same.jpg", region(ny = 0.1f, occurrence = 0, webp = byteArrayOf(1)), 1_000_000)
        s.upsert("chapter", "same.jpg", region(ny = 0.1f, occurrence = 1, webp = byteArrayOf(2)), 1_000_000)
        assertEquals(2, s.load("chapter", "same.jpg").size)
    }

    /**
     * A file that is over the budget on its own is kept: it is the translation the reader is holding.
     */
    @Test
    fun theFileJustWrittenIsNotEvicted() = runBlocking {
        val s = store()
        s.upsert("old", "a.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        s.upsert("new", "b.jpg", region(webp = ByteArray(4000)), maxBytes = 100_000_000)
        // A budget that fits nothing: the file just written still has to be there.
        s.upsert("newest", "c.jpg", region(webp = ByteArray(4000)), maxBytes = 1)
        assertEquals("the translation just written was thrown away", 1, s.load("newest", "c.jpg").size)
    }

    /**
     * A temporary file left behind by an interrupted write is not counted as the store's content.
     */
    @Test
    fun aTemporaryFileIsNotCounted() = runBlocking {
        val s = store()
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000)
        val leftover = File(dir, "leftover.tmp")
        leftover.writeBytes(ByteArray(9999))
        assertEquals(actualBytes(), s.sizeBytes())
    }

    @Test
    fun theStoreStillWorksUnderParallelWrites() = runBlocking {
        val s = store()
        coroutineScope {
            val jobs = (1..4).map { i ->
                async(Dispatchers.IO) {
                    repeat(10) { k ->
                        s.upsert("chapter", "img$i.jpg", region(ny = k * 0.01f, fingerprint = "f$i$k"), 100_000_000)
                    }
                }
            }
            jobs.awaitAll()
        }
        assertTrue(s.load("chapter", "img1.jpg").isNotEmpty())
        assertEquals("the count is wrong after parallel writes", actualBytes(), s.sizeBytes())
        assertFalse("a temporary file was left behind", dir.walkTopDown().any { it.name.endsWith(".tmp") })
    }
}
