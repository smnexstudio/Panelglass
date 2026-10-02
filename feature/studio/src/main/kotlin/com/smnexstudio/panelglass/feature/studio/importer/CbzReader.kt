package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.model.ComicInfo
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Streams a CBZ's entries. Image entries go to the caller in archive order (it sorts them); `ComicInfo.xml` is
 * parsed; `__MACOSX/`, hidden files and anything else are skipped. Entry names are only ever used to order and label
 * pages, never as paths. Every byte unpacked counts against the caps, skipped entries included, so a zip bomb stops
 * at the cap instead of filling the disk or spinning on the CPU.
 */
class CbzReader(private val limits: ImportLimits) {

    /** [onImage] gets each image entry with a stream of its bytes; it may read it fully or not at all. */
    fun read(input: InputStream, onImage: (name: String, stream: InputStream) -> Unit): ComicInfo? {
        var info: ComicInfo? = null
        var entries = 0
        var images = 0
        val total = Counter(limits.maxTotalBytes)
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (++entries > limits.maxEntries) throw ImportRefused(ImportRefused.Reason.TOO_MANY_ENTRIES)
                    if (entry.size > limits.maxFileBytes) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
                    val body = EntryStream(zip, limits.maxFileBytes, total)
                    val name = entry.name.replace('\\', '/')
                    when {
                        entry.isDirectory || skipped(name) -> Unit
                        name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true) && info == null ->
                            info = readComicInfo(body)
                        isImage(name) -> {
                            if (++images > limits.maxPages) throw ImportRefused(ImportRefused.Reason.TOO_MANY_PAGES)
                            onImage(name, body)
                        }
                    }
                    body.drain() // what the caller left, through the caps
                }
            }
        } catch (e: ZipException) {
            throw ImportRefused(ImportRefused.Reason.NOT_A_ZIP)
        } catch (e: IllegalArgumentException) {
            // ZipInputStream throws it for a malformed entry name encoding.
            throw ImportRefused(ImportRefused.Reason.NOT_A_ZIP)
        }
        if (entries == 0) throw ImportRefused(ImportRefused.Reason.NOT_A_ZIP)
        return info
    }

    private fun readComicInfo(body: InputStream): ComicInfo? {
        val bytes = body.readAtMost(ComicInfo.MAX_BYTES + 1)
        if (bytes.size > ComicInfo.MAX_BYTES) return null
        return ComicInfo.parse(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
    }

    private class Counter(val max: Long) {
        var n = 0L
        fun add(k: Int) {
            n += k
            if (n > max) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
        }
    }

    /** One entry's bytes; more than the per-entry cap refuses the archive, and every byte counts toward the total. */
    private class EntryStream(input: InputStream, private val max: Long, private val total: Counter) : FilterInputStream(input) {
        private var n = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count(r)
            return r
        }

        override fun skip(n: Long): Long {
            // Skipping still inflates; read so it is counted.
            val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
            val r = read(buf, 0, buf.size)
            return if (r < 0) 0 else r.toLong()
        }

        /** Never closes the zip stream: the next entry follows. */
        override fun close() = Unit

        fun drain() {
            val buf = ByteArray(8192)
            while (read(buf, 0, buf.size) >= 0) Unit
        }

        private fun count(k: Int) {
            n += k
            if (n > max) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
            total.add(k)
        }
    }

    companion object {
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "avif")

        /** Only `.cbz` is taken: a plain `.zip` is refused whatever its MIME type says. */
        fun isCbzName(displayName: String?): Boolean = displayName?.trim()?.endsWith(".cbz", ignoreCase = true) == true

        fun isImage(name: String) = name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in IMAGE_EXT

        /** macOS resource forks and hidden files. */
        fun skipped(name: String): Boolean {
            val parts = name.split('/').filter { it.isNotEmpty() }
            return parts.any { it == "__MACOSX" } || parts.lastOrNull()?.startsWith(".") == true
        }
    }
}

/**
 * Up to [n] bytes, fewer only at the end of the stream. `InputStream.readNBytes` would do, but it is API 33 and the
 * app runs from 31: on Android 12 it is a NoSuchMethodError.
 */
internal fun InputStream.readAtMost(n: Int): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(buf, got, n - got)
        if (r < 0) break
        got += r
    }
    return if (got == n) buf else buf.copyOf(got)
}
