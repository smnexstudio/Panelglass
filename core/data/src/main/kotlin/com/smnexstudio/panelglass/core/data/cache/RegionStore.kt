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
    /**
     * Which image of the same key this record belongs to: 0 is the first, 1 the second, and so on.
     *
     * The file is named after the image's key, so a page that shows the same picture twice keeps both in one
     * file. Without this, the record of the second picture would be drawn on the first.
     */
    val occurrence: Int = 0,
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
            imgW == other.imgW && imgH == other.imgH && kind == other.kind && occurrence == other.occurrence &&
            fingerprint == other.fingerprint && createdAtMs == other.createdAtMs && webp.contentEquals(other.webp)
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
        result = 31 * result + occurrence
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
 *
 * **The epoch.** Every scope has an invalidation epoch that a clear advances. A write carries the epoch it was
 * started with; the store refuses it when the two differ. The check happens **inside** the lock that serialises
 * the writes, so a clear that lands while a write is on its way is not undone by that write.
 */
class RegionStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var totalBytes: Long = -1

    /** The invalidation epoch of each scope, by its own name. */
    private val epochs = HashMap<String, Long>()

    /** The directory name of each scope, so a clear that keeps one scope can invalidate the others. */
    private val scopeDirs = HashMap<String, String>()

    /** The epoch a scope is at now: 0 for a scope that was never cleared. */
    private fun currentEpoch(scope: String): Long = epochs[scope] ?: 0L

    /**
     * The epoch of a scope, for a writer to carry.
     *
     * A writer takes it when the capture is accepted and gives it back with the write: the store then knows
     * whether the write still belongs to a state that exists.
     */
    suspend fun epochOf(scope: String): Long = withContext(Dispatchers.IO) { mutex.withLock { currentEpoch(scope) } }

    /** The saved regions of one image, newest first is not guaranteed: in the order they were saved. */
    suspend fun load(scope: String, imageKey: String, maxAgeMs: Long? = null): List<SavedRegion> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = fileFor(scope, imageKey)
            if (!f.isFile) return@withLock emptyList()
            val regions = runCatching { read(f) }.getOrElse {
                // A damaged file must not take the reader with it: drop it and carry on. The bytes it held are
                // gone, so the store's total must not keep counting them.
                val len = f.length()
                if (f.delete()) {
                    // The total is measured again, exactly: subtracting would be right only when the total was
                    // measured, and a total that was never measured must not be turned into a negative one.
                    rescan()
                } else {
                    // The file could not be deleted: it is still on the disk, so it is still counted.
                    if (totalBytes >= 0) totalBytes += len
                }
                emptyList()
            }
            val now = clock()
            val fresh = if (maxAgeMs == null) regions else regions.filter { now - it.createdAtMs <= maxAgeMs }
            if (fresh.size != regions.size) {
                // The expired ones are gone for good.
                val previous = f.length()
                runCatching { write(f, fresh) }
                if (totalBytes >= 0) totalBytes += f.length() - previous else rescan()
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
     *
     * [epoch] is the scope's epoch when the write was started. A write that started before a clear is refused.
     */
    suspend fun upsert(
        scope: String, imageKey: String, region: SavedRegion, maxBytes: Long, epoch: Long = 0,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // The check is inside the lock that serialises the writes: a write that is waiting for it sees the
            // new epoch and is refused, so a clear is never undone by a write that was already on its way.
            if (epoch != currentEpoch(scope)) {
                // The write belongs to a state that no longer exists: the clear that came after it won.
                log("stale write rejected")
                return@withLock
            }
            ensureScanned()
            val f = fileFor(scope, imageKey)
            val existing = if (f.isFile) runCatching { read(f) }.getOrElse { emptyList() } else emptyList()
            val same = existing.indexOfFirst {
                it.fingerprint == region.fingerprint && it.occurrence == region.occurrence && overlaps(it, region)
            }
            val merged = if (same >= 0) {
                existing.toMutableList().also { it[same] = region }
            } else {
                existing + region
            }
            // The record budget. It is generous: a long webtoon has more bubbles than the old cap allowed, and
            // throwing the oldest away while the disk has room is a silent loss. What is over the budget is not
            // dropped in silence: it is said, and the store's own byte budget evicts whole files.
            val capped = if (merged.size > MAX_REGIONS) {
                log("record budget reached: ${merged.size} -> $MAX_REGIONS")
                merged.takeLast(MAX_REGIONS)
            } else merged
            val previous = if (f.isFile) f.length() else 0L
            write(f, capped)
            if (totalBytes >= 0) totalBytes += f.length() - previous else rescan()
            // The file just written is never evicted: the reader is holding this translation.
            evictIfNeeded(maxBytes, keep = f)
        }
    }

    /** Forgets every saved translation. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            // Every scope is invalidated: a write still on its way to any of them is refused.
            epochs.keys.toList().forEach { epochs[it] = currentEpoch(it) + 1 }
            rescan()
        }
    }

    /**
     * Forgets every saved translation of one scope (the chapter).
     *
     * The scope's epoch is advanced **inside** the lock, so a write that is waiting for it is refused.
     */
    suspend fun clearScope(scope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            scopeDir(scope).deleteRecursively()
            epochs[scope] = currentEpoch(scope) + 1
            rescan()
        }
    }

    /**
     * Forgets every saved translation except those of one scope.
     *
     * The scopes that go are invalidated as well: a write still on its way to one of them is refused. The scope
     * that stays is not touched, and its epoch does not move.
     */
    suspend fun clearScopesExcept(scope: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keep = scopeDir(scope)
            dir.listFiles()?.forEach { f ->
                if (f != keep) {
                    f.deleteRecursively()
                    // The scopes are tracked by their own name; the directory is named after the hash of it.
                    scopeDirs.filterValues { it == f.name }.keys.toList().forEach { gone ->
                        epochs[gone] = currentEpoch(gone) + 1
                    }
                }
            }
            rescan()
        }
    }

    /** How much disk the saved translations use. */
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { mutex.withLock { ensureScanned(); totalBytes } }

    /**
     * How much disk the saved translations use, and how much of it is really there.
     *
     * The two must be the same number: a count that is too large makes the store throw away files that are
     * still there.
     */
    suspend fun sizeBytesAndActual(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureScanned()
            val actual = dir.walkTopDown().filter { it.isFile && !it.isTemporary() }.sumOf { it.length() }
            totalBytes to actual
        }
    }

    // ---- the file -------------------------------------------------------------------------------

    private fun fileFor(scope: String, imageKey: String): File {
        val d = scopeDir(scope)
        d.mkdirs()
        return File(d, sha256Hex(imageKey.toByteArray()) + ".pgr")
    }

    private fun scopeDir(scope: String): File {
        val name = sha256Hex(scope.toByteArray())
        scopeDirs[scope] = name
        return File(dir, name)
    }

    private fun read(f: File): List<SavedRegion> {
        DataInputStream(f.inputStream().buffered()).use { input ->
            if (input.readInt() != MAGIC) throw IllegalStateException("not a region file")
            val fileSchema = input.readInt()
            if (fileSchema > FILE_SCHEMA) throw IllegalStateException("from the future")
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
                // The occurrence came with version 2 of the file; a version 1 record is the first occurrence.
                val occurrence = if (fileSchema >= FILE_SCHEMA) input.readInt() else 0
                val fpLen = input.readInt()
                require(fpLen in 0..MAX_FINGERPRINT)
                val fp = ByteArray(fpLen).also { input.readFully(it) }.toString(Charsets.UTF_8)
                val created = input.readLong()
                val len = input.readInt()
                require(len in 0..MAX_WEBP)
                val bytes = ByteArray(len).also { input.readFully(it) }
                // A record from a newer format is left alone, not guessed at.
                if (s > MAX_RECORD_SCHEMA) return@repeat
                out += SavedRegion(s, nx, ny, nw, nh, iw, ih, kind, occurrence, fp, created, bytes)
            }
            return out
        }
    }

    /**
     * Writes the file, and keeps the old one until the new one is there.
     *
     * The bytes go to a temporary file first. The rename is the commit: if it fails, the old file is still the
     * valid one and the temporary file is thrown away. A reader never sees half a file.
     */
    private fun write(f: File, regions: List<SavedRegion>) {
        val tmp = File(f.path + TEMP_SUFFIX)
        try {
            // The file stream is not buffered here: the bytes have to reach the disk before the rename makes them
            // the file, or a power cut leaves a file that says what it holds but does not hold it.
            val raw = java.io.FileOutputStream(tmp)
            DataOutputStream(raw).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FILE_SCHEMA)
                out.writeInt(regions.size)
                for (r in regions) {
                    out.writeInt(r.schema)
                    out.writeFloat(r.nx); out.writeFloat(r.ny)
                    out.writeFloat(r.nw); out.writeFloat(r.nh)
                    out.writeFloat(r.imgW); out.writeFloat(r.imgH)
                    out.writeInt(r.kind)
                    out.writeInt(r.occurrence)
                    val fp = r.fingerprint.toByteArray(Charsets.UTF_8).let { b -> if (b.size > MAX_FINGERPRINT) b.copyOf(MAX_FINGERPRINT) else b }
                    out.writeInt(fp.size)
                    out.write(fp)
                    out.writeLong(r.createdAtMs)
                    out.writeInt(r.webp.size)
                    out.write(r.webp)
                }
                out.flush()
                raw.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                // The rename over the old file did not work. The old file is only removed once the new one is
                // known to be good: it is renamed aside first, and put back if the second rename fails too.
                val aside = File(f.path + OLD_SUFFIX)
                if (f.exists() && !f.renameTo(aside)) {
                    // The old file could not even be moved aside: it is left alone and the write is dropped.
                    tmp.delete()
                    return
                }
                if (!tmp.renameTo(f)) {
                    // The new file could not be committed: the old one goes back.
                    aside.renameTo(f)
                    tmp.delete()
                    return
                }
                // The new file is committed: the old one is no longer needed.
                aside.delete()
            }
        } catch (e: Exception) {
            // A failed write must not leave the store in a state it cannot read.
            tmp.delete()
            throw e
        }
    }

    private fun ensureScanned() {
        if (totalBytes >= 0) return
        rescan()
    }

    /**
     * Measures the store again, exactly.
     *
     * The cached total is only a shortcut between mutations. Every mutation that removes files has to call this:
     * a total that is too large makes the store evict files that are still there, and a translation the reader has
     * paid for is thrown away for nothing.
     */
    private fun rescan() {
        dir.mkdirs()
        totalBytes = dir.walkTopDown().filter { it.isFile && !it.isTemporary() }.sumOf { it.length() }
    }

    /**
     * The oldest files go first, until the store is under the budget.
     *
     * The headroom the whole-viewport cache uses (90% of the budget) is not used here: a budget that barely fits
     * what is already stored must not throw away more than it has to.
     *
     * [keep] is the file that was just written: it is never evicted, or the reader would throw away the
     * translation it is holding. A file that is over the budget on its own is still kept for the same reason —
     * the next write of another image will bring the store back under the budget.
     */
    private fun evictIfNeeded(maxBytes: Long, keep: File? = null) {
        if (maxBytes <= 0 || totalBytes <= maxBytes) return
        val files = dir.walkTopDown().filter { it.isFile && it != keep && !it.isTemporary() }
            .sortedBy { it.lastModified() }.toList()
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

    /**
     * A line in the log, if there is a log.
     *
     * A unit test has no Android framework, and a diagnostic that crashes the app it is meant to explain is
     * worse than no diagnostic. The line holds a number and a word: never a key, never a URL, never text.
     */
    private fun log(message: String) {
        try {
            android.util.Log.d("RegionStore", message)
        } catch (_: Throwable) {
            // No framework: nothing to log to, and nothing to do about it.
        }
    }

    /** A file that is not a committed record: a write in progress, or one that was put aside. */
    private fun File.isTemporary(): Boolean = name.endsWith(TEMP_SUFFIX) || name.endsWith(OLD_SUFFIX)

    companion object {
        private const val MAGIC = 0x50475231 // "PGR1"

        /**
         * The version of the **file** format.
         *
         * Version 2 added the occurrence: which image of the same key a record belongs to. A file of version 1
         * has no such field, so it is read with the old layout and its records count as the first occurrence.
         *
         * This is the file's own version and has nothing to do with a record's geometry version, which is the
         * reader's business ([com.smnexstudio.panelglass.feature.browser.PatchRetention]).
         */
        const val FILE_SCHEMA = 2
        private const val SCHEMA_1 = 1
        private const val TEMP_SUFFIX = ".tmp"
        private const val OLD_SUFFIX = ".old"

        /**
         * The most regions one image may hold.
         *
         * It is a parsing limit as much as a storage one: a long webtoon has more bubbles than the old cap of
         * 300 allowed, and the cap must not throw away translations while the disk has room. The store's byte
         * budget is what evicts.
         */
        const val MAX_REGIONS = 4000

        /**
         * The highest record version this app reads.
         *
         * A record from a newer app is left alone, not guessed at: its geometry may mean something else.
         */
        const val MAX_RECORD_SCHEMA = 8

        /** The most bytes one region's WEBP may have. */
        const val MAX_WEBP = 2 shl 20
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
