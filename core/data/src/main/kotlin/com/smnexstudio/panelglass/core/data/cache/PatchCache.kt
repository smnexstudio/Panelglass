package com.smnexstudio.panelglass.core.data.cache

import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Patch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/** Cached rendering for one image: dimensions plus every patch. Patches cache individually inside the record. */
class CachedPage(val width: Int, val height: Int, val regionCount: Int, val patches: List<Patch>)

/**
 * Disk LRU of rendered patches, keyed by `sha256(bytes) + src + tgt + engineId + PIPELINE_VERSION`.
 * Back-navigation is served from here with zero engine calls. One file per key; LRU order is the
 * file mtime, which we touch on every hit. Bounded to [maxBytes] (500 MB by default).
 */
class PatchCache(
    private val dir: File,
    private val maxBytes: Long = 500L * 1024 * 1024,
) {
    private val mutex = Mutex()
    private var totalBytes: Long = -1

    fun key(imageHash: String, src: Lang, tgt: Lang, engine: EngineId, pipelineVersion: Int): String =
        "$imageHash|${src.code}|${tgt.code}|${engine.name}|v$pipelineVersion"

    suspend fun get(key: String): CachedPage? = withContext(Dispatchers.IO) {
        val f = fileFor(key)
        if (!f.isFile) return@withContext null
        runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@withContext null
                val w = input.readInt()
                val h = input.readInt()
                val regionCount = input.readInt()
                val n = input.readInt()
                // A damaged file must not size an allocation: out-of-range counts drop the record (deleted below).
                require(n in 0..MAX_PATCHES)
                val patches = ArrayList<Patch>(n)
                repeat(n) {
                    val x = input.readFloat(); val y = input.readFloat()
                    val pw = input.readFloat(); val ph = input.readFloat()
                    val len = input.readInt()
                    require(len in 0..MAX_PATCH_BYTES)
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    patches += Patch(x, y, pw, ph, bytes)
                }
                CachedPage(w, h, regionCount, patches)
            }
        }.getOrElse { f.delete(); null }?.also {
            // LRU order is the mtime; touch only after the stream is closed (Windows refuses otherwise).
            f.setLastModified(System.currentTimeMillis())
        }
    }

    suspend fun put(key: String, page: CachedPage) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureScanned()
            val f = fileFor(key)
            val tmp = File(f.path + ".tmp")
            val previous = if (f.isFile) f.length() else 0L
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(page.width); out.writeInt(page.height)
                out.writeInt(page.regionCount)
                out.writeInt(page.patches.size)
                for (p in page.patches) {
                    out.writeFloat(p.xPct); out.writeFloat(p.yPct)
                    out.writeFloat(p.wPct); out.writeFloat(p.hPct)
                    out.writeInt(p.webp.size)
                    out.write(p.webp)
                }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            totalBytes += f.length() - previous
            evictIfNeeded()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            totalBytes = 0
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { ensureScanned(); totalBytes } }

    private fun ensureScanned() {
        if (totalBytes >= 0) return
        dir.mkdirs()
        totalBytes = dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun evictIfNeeded() {
        if (totalBytes <= maxBytes) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (totalBytes <= maxBytes * 0.9) break
            val len = f.length()
            if (f.delete()) totalBytes -= len
        }
    }

    private fun fileFor(key: String): File {
        dir.mkdirs()
        return File(dir, sha256Hex(key.toByteArray()) + ".pg")
    }

    companion object {
        private const val MAGIC = 0x50474331 // "PGC1"
        private const val MAX_PATCHES = 10_000
        private const val MAX_PATCH_BYTES = 20 shl 20

        fun sha256Hex(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(64)
            for (b in d) { sb.append(HEX[(b.toInt() shr 4) and 0xF]); sb.append(HEX[b.toInt() and 0xF]) }
            return sb.toString()
        }
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
