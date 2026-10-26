package com.smnexstudio.panelglass.core.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * One translated region of one image, kept on disk.
 *
 * The box is a fraction of the image's own size, so the record survives the image being laid out at another zoom or
 * another width (a rotation). The [fingerprint] says what the translation was made with — the engine, the model, the
 * languages — so a record made under other settings is never shown again.
 */
data class SavedRegion(
    /** The version of this record's format. */
    val schema: Int = 1,
    /** The box, as fractions of the image's size: left/width, top/height, width/width, height/height. */
    val nx: Float,
    val ny: Float,
    val nw: Float,
    val nh: Float,
    /** The image's CSS size when the record was made. */
    val imgW: Float,
    val imgH: Float,
    /** The region's kind, as the pipeline's number; 0 when it is not known. */
    val kind: Int = 0,
    /** What the translation was made with. */
    val fingerprint: String = "",
    /** When the record was made, in epoch milliseconds. */
    val createdAtMs: Long = 0,
    /** The rendered region, as WEBP bytes. */
    val webp: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SavedRegion) return false
        return schema == other.schema && nx == other.nx && ny == other.ny && nw == other.nw && nh == other.nh &&
            imgW == other.imgW && imgH == other.imgH && kind == other.kind && fingerprint == other.fingerprint &&
            createdAtMs == other.createdAtMs && webp.contentEquals(other.webp)
    }

    override fun hashCode(): Int {
        var result = schema
        result = 31 * result + nx.hashCode()
        result = 31 * result + ny.hashCode()
        result = 31 * result + nw.hashCode()
        result = 31 * result + nh.hashCode()
        result = 31 * result + imgW.hashCode()
        result = 31 * result + imgH.hashCode()
        result = 31 * result + kind
        result = 31 * result + fingerprint.hashCode()
        result = 31 * result + createdAtMs.hashCode()
        result = 31 * result + webp.contentHashCode()
        return result
    }
}

/**
 * The translations the reader has already paid for, kept on disk.
 *
 * A file holds every saved region of one image, under a directory named after the scope (the chapter). The names
 * come only from hashes: no part of an image key is ever a file name.
 *
 * The byte budget and the time-to-live are arguments of the calls that need them, not state of the store, so a
 * change in the settings takes effect at once.
 */
class RegionStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var totalBytes: Long = -1

    /** The saved regions of one image, newest first is not guaranteed: in the order they were saved. */
    suspend fun load(scope: String, imageKey: String, maxAgeMs: Long? = null): List<SavedRegion> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = fileFor(scope, imageKey)
            if (!f.isFile) return@withLock emptyList()
            val regions = runCatching { read(f) }.getOrElse {
                // A damaged file must not take the reader with it: drop it and carry on.
                f.delete()
                emptyList()
            }
            val now = clock()
            val fresh = if (maxAgeMs == null) regions else regions.filter { now - it.createdAtMs <= maxAgeMs }
            if (fresh.size != regions.size) {
                // The expired ones are gone for good.
                runCatching { write(f, fresh) }
            } else if (regions.isNotEmpty()) {
                // The LRU order is the mtime: a file that is read is a file that is wanted.
                f.setLastModified(now)
            }
            fresh
        }
    }

    /**
     * Saves one region.
     *
     * A stored region of the same image with the same fingerprint and about the same box is replaced: the reader
     * translated that bubble again, and the newer translation is the better one. Otherwise the region is appended.
     */
    suspend fun upsert(scope: String, imageKey: String, region: SavedRegion, maxBytes: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureScanned()
            val f = fileFor(scope, imageKey)
            val existing = if (f.isFile) runCatching { read(f) }.getOrElse { emptyList() } else emptyList()
            val same = existing.indexOfFirst { it.fingerprint == region.fingerprint && overlaps(it, region) }
            val merged = if (same >= 0) {
                existing.toMutableList().also { it[same] = region }
            } else {
                existing + region
            }
            // At most 300 regions per image; the oldest go first.
            val capped = if (merged.size > MAX_REGIONS) merged.takeLast(MAX_REGIONS) else merged
            val previous = if (f.isFile) f.length() else 0L
            write(f, capped)
            totalBytes += f.length() - previous
            // The file just written is never evicted: the reader is holding this translation.
            evictIfNeeded(maxBytes, keep = f)
        }
    }

    /** Forgets every saved translation. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            totalBytes = 0
        }
    }

    /** Forgets every saved translation of one scope (the chapter). */
    suspend fun clearScope(scope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            scopeDir(scope).deleteRecursively()
            ensureScanned()
        }
    }

    /** Forgets every saved translation except those of one scope. */
    suspend fun clearScopesExcept(scope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keep = scopeDir(scope)
            dir.listFiles()?.forEach { f ->
                if (f != keep) f.deleteRecursively()
            }
            ensureScanned()
        }
    }

    /** How much disk the saved translations use. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { ensureScanned(); totalBytes } }

    // ---- the file -------------------------------------------------------------------------------

    private fun fileFor(scope: String, imageKey: String): File {
        val d = scopeDir(scope)
        d.mkdirs()
        return File(d, sha256Hex(imageKey.toByteArray()) + ".pgr")
    }

    private fun scopeDir(scope: String): File = File(dir, sha256Hex(scope.toByteArray()))

    private fun read(f: File): List<SavedRegion> {
        DataInputStream(f.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) throw IllegalStateException("not a region file")
            val schema = input.readInt()
            if (schema > SCHEMA) throw IllegalStateException("from the future")
            val n = input.readInt()
            // A damaged file must not size an allocation.
            require(n in 0..MAX_REGIONS)
            val out = ArrayList<SavedRegion>(n)
            repeat(n) {
                val s = input.readInt()
                val nx = input.readFloat(); val ny = input.readFloat()
                val nw = input.readFloat(); val nh = input.readFloat()
                val iw = input.readFloat(); val ih = input.readFloat()
                val kind = input.readInt()
                val fpLen = input.readInt()
                require(fpLen in 0..MAX_FINGERPRINT)
                val fp = ByteArray(fpLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
                val created = input.readLong()
                val len = input.readInt()
                require(len in 0..MAX_WEBP)
                val bytes = ByteArray(len).also { input.readFully(it) }
                // A record from a newer format is left alone, not guessed at.
                if (s > SCHEMA) return@repeat
                out += SavedRegion(s, nx, ny, nw, nh, iw, ih, kind, fp, created, bytes)
            }
            return out
        }
    }

    private fun write(f: File, regions: List<SavedRegion>) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(SCHEMA)
            out.writeInt(regions.size)
            for (r in regions) {
                out.writeInt(r.schema)
                out.writeFloat(r.nx); out.writeFloat(r.ny)
                out.writeFloat(r.nw); out.writeFloat(r.nh)
                out.writeFloat(r.imgW); out.writeFloat(r.imgH)
                out.writeInt(r.kind)
                val fp = r.fingerprint.toByteArray(Charsets.UTF_8).let { b -> if (b.size > MAX_FINGERPRINT) b.copyOf(MAX_FINGERPRINT) else b }
                out.writeInt(fp.size)
                out.write(fp)
                out.writeLong(r.createdAtMs)
                out.writeInt(r.webp.size)
                out.write(r.webp)
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun ensureScanned() {
        if (totalBytes >= 0) return
        dir.mkdirs()
        totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * The oldest files go first, until the store is under the budget.
     *
     * The oldest file goes until the store is under the budget. The headroom the whole-viewport cache uses (90% of
     * the budget) is not used here: a budget that barely fits what is already stored must not throw away more than
     * it has to.
     *
     * [keep] is the file that was just written: it is never evicted, or the reader would throw away the
     * translation it is holding.
     */
    private fun evictIfNeeded(maxBytes: Long, keep: File? = null) {
        if (maxBytes <= 0 || totalBytes <= maxBytes) return
        val files = dir.walkTopDown().filter { it.isFile && it != keep }.sortedBy { it.lastModified() }.toList()
        for (f in files) {
            if (totalBytes <= maxBytes) break
            val len = f.length()
            if (f.delete()) totalBytes -= len
        }
    }

    /**
     * Whether two regions are the same bubble: they overlap by at least 60% of the smaller one, and they are about
     * the same size.
     */
    private fun overlaps(a: SavedRegion, b: SavedRegion): Boolean {
        val w = minOf(a.nx + a.nw, b.nx + b.nw) - maxOf(a.nx, b.nx)
        val h = minOf(a.ny + a.nh, b.ny + b.nh) - maxOf(a.ny, b.ny)
        if (w <= 0f || h <= 0f) return false
        val inter = w * h
        val areaA = a.nw * a.nh
        val areaB = b.nw * b.nh
        val smaller = minOf(areaA, areaB)
        if (smaller <= 0f) return false
        if (inter / smaller < 0.6f) return false
        val ratio = maxOf(areaA, areaB) / smaller
        return ratio <= 2.5f
    }

    companion object {
        private const val MAGIC = 0x50475231 // "PGR1"
        private const val SCHEMA = 1
        private const val MAX_REGIONS = 300
        private const val MAX_WEBP = 2 shl 20
        private const val MAX_FINGERPRINT = 256

        fun sha256Hex(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-256").digest(bytes)
            val sb = StringBuilder(64)
            for (b in d) { sb.append(HEX[(b.toInt() shr 4) and 0xF]); sb.append(HEX[b.toInt() and 0xF]) }
            return sb.toString()
        }
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
