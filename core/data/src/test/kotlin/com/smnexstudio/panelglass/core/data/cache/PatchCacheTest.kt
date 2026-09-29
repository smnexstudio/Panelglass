package com.smnexstudio.panelglass.core.data.cache

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Patch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PatchCacheTest {
    @TempDir lateinit var dir: File

    private fun page(n: Int, bytesEach: Int) = CachedPage(1000, 1500, n, List(n) { i -> Patch(i * 1f, 2f, 3f, 4f, ByteArray(bytesEach) { (i + it).toByte() }) })

    @Test
    fun roundTripPreservesGeometryAndBytes() = runTest {
        val cache = PatchCache(dir)
        val key = cache.key("abc", Lang.JA, Lang.EN, EngineId.GEMINI, 1)
        cache.put(key, page(3, 64))
        val hit = cache.get(key)
        assertNotNull(hit)
        assertEquals(1000, hit!!.width); assertEquals(1500, hit.height); assertEquals(3, hit.regionCount)
        assertEquals(3, hit.patches.size)
        assertEquals(2f, hit.patches[2].xPct)
        assertTrue(hit.patches[1].webp.contentEquals(ByteArray(64) { (1 + it).toByte() }))
    }

    @Test
    fun keyIncludesEveryDimensionThatChangesRendering() = runTest {
        val cache = PatchCache(dir)
        val a = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 1)
        assertTrue(a != cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 2))
        assertTrue(a != cache.key("h", Lang.JA, Lang.ES, EngineId.GEMINI, 1))
        assertTrue(a != cache.key("h", Lang.JA, Lang.EN, EngineId.GOOGLE, 1))
        assertNull(cache.get(cache.key("h", Lang.KO, Lang.EN, EngineId.GEMINI, 1)))
    }

    @Test
    fun evictsLeastRecentlyUsedWhenOverBudget() = runTest {
        val cache = PatchCache(dir, maxBytes = 10_000)
        val keys = (0 until 8).map { cache.key("img$it", Lang.JA, Lang.EN, EngineId.GOOGLE, 1) }
        for ((i, k) in keys.withIndex()) {
            cache.put(k, page(1, 2_000))
            Thread.sleep(5) // mtime resolution
            if (i == 3 || i == 6) { cache.get(keys[0]); Thread.sleep(5) } // keep touching the first so it stays recent
        }
        assertTrue(cache.sizeBytes() <= 10_000)
        assertNotNull(cache.get(keys[0]))
        assertNotNull(cache.get(keys[7]))
        assertNull(cache.get(keys[1]))
        assertNull(cache.get(keys[4]))
    }

    @Test
    fun corruptFileIsDroppedNotThrown() = runTest {
        val cache = PatchCache(dir)
        val key = cache.key("x", Lang.JA, Lang.EN, EngineId.GOOGLE, 1)
        cache.put(key, page(1, 8))
        dir.listFiles()!!.first { it.name.endsWith(".pg") }.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.get(key))
    }

    /** A damaged length field must not size an allocation: the record is dropped and its file deleted. */
    @Test
    fun oversizedLengthIsRefusedAndDeleted() = runTest {
        val cache = PatchCache(dir)
        val key = cache.key("y", Lang.JA, Lang.EN, EngineId.GOOGLE, 1)
        cache.put(key, page(1, 8))
        val file = dir.listFiles()!!.first { it.name.endsWith(".pg") }
        val bytes = file.readBytes()
        // Header: magic, w, h, regionCount, n (5 ints); patch: 4 floats, then the length int at offset 36.
        java.nio.ByteBuffer.wrap(bytes).putInt(36, Int.MAX_VALUE)
        file.writeBytes(bytes)
        assertNull(cache.get(key))
        assertTrue(!file.exists())
    }
}
