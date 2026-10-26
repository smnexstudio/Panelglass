package com.smnexstudio.panelglass.core.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The order of the writes, and the order of the clears.
 *
 * A translation is written after it is drawn, on a background thread. A clear can therefore land while a write is
 * on its way. The write must not undo the clear, and a clear must not delete what came after it.
 *
 * Robolectric (JUnit 4 via the vintage engine), because the store logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionStoreOrderingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root

    private fun region(
        nx: Float = 0.1f, ny: Float = 0.1f, nw: Float = 0.2f, nh: Float = 0.05f,
        fingerprint: String = "fp1", webp: ByteArray = byteArrayOf(1, 2, 3), occurrence: Int = 0,
    ) = SavedRegion(
        nx = nx, ny = ny, nw = nw, nh = nh, imgW = 1000f, imgH = 2000f, kind = 0, occurrence = occurrence,
        fingerprint = fingerprint, createdAtMs = 1_000_000L, webp = webp,
    )

    /**
     * A write that is on its way when the clear lands does not come back.
     *
     * The write takes the epoch **before** the clear, and the store refuses it when the two differ. The check is
     * inside the store's own lock, so there is no window between the check and the write.
     */
    @Test
    fun aWriteInFlightWhenTheClearLandsDoesNotComeBack() = runBlocking {
        val s = RegionStore(dir)
        // The write starts: it takes the epoch and then waits.
        val epoch = s.epochOf("chapter")
        // The clear lands while the write waits.
        s.clearScope("chapter")
        // The write goes on.
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000, epoch = epoch)
        assertTrue("the write came back after the clear", s.load("chapter", "a.jpg").isEmpty())
    }

    /**
     * The other order: the write lands first, and the clear then removes it.
     */
    @Test
    fun aWriteThatLandedBeforeTheClearIsRemoved() = runBlocking {
        val s = RegionStore(dir)
        val epoch = s.epochOf("chapter")
        s.upsert("chapter", "a.jpg", region(), maxBytes = 1_000_000, epoch = epoch)
        assertEquals(1, s.load("chapter", "a.jpg").size)
        s.clearScope("chapter")
        assertTrue(s.load("chapter", "a.jpg").isEmpty())
    }

    /**
     * A re-translate: the clear happens, and then the replacement is written with the new epoch. The replacement
     * survives, and the old one does not come back.
     */
    @Test
    fun aReTranslateKeepsItsReplacementAndNotTheOldOne() = runBlocking {
        val s = RegionStore(dir)
        val oldEpoch = s.epochOf("chapter")
        s.upsert("chapter", "a.jpg", region(webp = byteArrayOf(1)), maxBytes = 1_000_000, epoch = oldEpoch)

        // The re-translate clears and then writes its own.
        s.clearScope("chapter")
        val newEpoch = s.epochOf("chapter")
        s.upsert("chapter", "a.jpg", region(webp = byteArrayOf(9)), maxBytes = 1_000_000, epoch = newEpoch)

        val back = s.load("chapter", "a.jpg")
        assertEquals(1, back.size)
        assertTrue("the old translation came back", back[0].webp.contentEquals(byteArrayOf(9)))

        // And the old write, still on its way, does not undo the replacement.
        s.upsert("chapter", "a.jpg", region(webp = byteArrayOf(1)), maxBytes = 1_000_000, epoch = oldEpoch)
        val after = s.load("chapter", "a.jpg")
        assertEquals(1, after.size)
        assertTrue("the old translation won", after[0].webp.contentEquals(byteArrayOf(9)))
    }

    /**
     * A write that is on its way to one scope does not touch another scope.
     */
    @Test
    fun aClearOfOneScopeDoesNotTouchAnother() = runBlocking {
        val s = RegionStore(dir)
        val one = s.epochOf("one")
        val two = s.epochOf("two")
        s.upsert("one", "a.jpg", region(), 1_000_000, one)
        s.upsert("two", "b.jpg", region(), 1_000_000, two)
        s.clearScope("one")
        // The write for the other scope, started before the clear, still lands: the other scope was not cleared.
        s.upsert("two", "c.jpg", region(), 1_000_000, two)
        assertEquals("the other scope was touched", 1, s.load("two", "b.jpg").size)
        assertEquals("the write of the other scope did not land", 1, s.load("two", "c.jpg").size)
        assertTrue(s.load("one", "a.jpg").isEmpty())
    }

    /**
     * Several writes at once, and a clear in the middle: the store is still readable, and what it holds is what it
     * says it holds.
     */
    @Test
    fun writesAndAClearAtOnceLeaveAReadableStore() = runBlocking {
        val s = RegionStore(dir)
        coroutineScope {
            val writers = (1..4).map { i ->
                async(Dispatchers.IO) {
                    repeat(8) { k ->
                        val epoch = s.epochOf("chapter")
                        s.upsert("chapter", "img$i.jpg", region(ny = k * 0.05f, fingerprint = "f$i$k"), 100_000_000, epoch)
                        delay(1)
                    }
                }
            }
            val clearer = async(Dispatchers.IO) {
                repeat(3) {
                    s.clearScope("chapter")
                    delay(3)
                }
            }
            writers.awaitAll()
            clearer.await()
        }
        // The store reads without throwing, and its count is the truth.
        val actual = dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".old") }
            .sumOf { it.length() }
        assertEquals("the count is not the truth", actual, s.sizeBytes())
    }

    /**
     * A write whose file cannot be replaced leaves the old file alone: a reader never sees half a file.
     */
    @Test
    fun aFailedWriteLeavesTheOldFileReadable() = runBlocking {
        val s = RegionStore(dir)
        s.upsert("chapter", "a.jpg", region(webp = byteArrayOf(1, 2, 3)), maxBytes = 1_000_000)
        val before = s.load("chapter", "a.jpg")
        assertEquals(1, before.size)

        // The directory of the store is made read-only, so the temporary file cannot be written.
        val scopeDir = dir.walkTopDown().first { it.isDirectory && it.listFiles()?.any { f -> f.name.endsWith(".pgr") } == true }
        val original = scopeDir.setWritable(false)
        try {
            // The write fails, and the old file is still there.
            runCatching { s.upsert("chapter", "a.jpg", region(webp = byteArrayOf(9)), maxBytes = 1_000_000) }
        } finally {
            scopeDir.setWritable(original)
        }
        val after = s.load("chapter", "a.jpg")
        assertTrue("the old file was lost", after.isNotEmpty())
    }
}
