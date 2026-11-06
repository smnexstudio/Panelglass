package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads a comic archive: zip (`.cbz`, `.zip`) or 7z (`.cb7`, `.7z`), told apart by their first bytes, never by the
 * file name. Both are read through their index (the zip central directory, the 7z header), so entries whose sizes
 * follow their data, Zip64, Deflate64 and names in another encoding than UTF-8 all open. A RAR archive is recognised
 * and refused ([ImportRefused.Reason.RAR]).
 *
 * Entries go to the caller in archive order (it sorts them); `ComicInfo.xml` is parsed; `__MACOSX/`, hidden files and
 * known non-page files are left out silently; every other entry is offered as a page, so the caller decides from its
 * bytes whether it is an image (pages named `001`, `.jfif`, …). An archive inside the archive (`.cbz`, `.zip`, `.cb7`,
 * `.7z`) is read once more, one level deep, its pages named under the inner archive's name so that each becomes its
 * own chapter folder (`FolderChapters`). Deeper ones are skipped.
 *
 * Entry names are only ever used to order and label pages, never as paths. Every byte unpacked counts against the
 * caps, so a zip bomb stops at the cap instead of filling the disk or spinning on the CPU; entries left out are never
 * unpacked at all. An inner archive is unpacked into [scratch] within what is left of the total cap, and deleted after.
 */
class ArchiveReader(private val limits: ImportLimits, private val scratch: File) {

    enum class Format { ZIP, SEVEN_Z, RAR, UNKNOWN }

    /**
     * [onImage] gets each candidate page with a stream of its bytes; it may read it fully or not at all. [onSkip] gets
     * entries left out for a reason the user should see. [onComicInfo] gets every ComicInfo.xml with the folder its
     * pages are named under (`""` at the top, an inner archive's name for its own), so a pack of several series can
     * tell them apart. The caller owns [channel] and closes it. Returns the first ComicInfo found.
     */
    fun read(
        channel: SeekableByteChannel,
        onImage: (name: String, stream: InputStream) -> Unit,
        onSkip: (name: String, reason: SkipReason) -> Unit = { _, _ -> },
        onComicInfo: (folder: String, info: ComicInfo) -> Unit = { _, _ -> },
    ): ComicInfo? {
        val run = Run(onImage, onSkip, onComicInfo)
        run.archive(channel, prefix = "", depth = 0)
        return run.info
    }

    private inner class Run(
        val onImage: (String, InputStream) -> Unit,
        val onSkip: (String, SkipReason) -> Unit,
        val onComicInfo: (String, ComicInfo) -> Unit,
    ) {
        var info: ComicInfo? = null
        var entries = 0
        var pages = 0
        val total = Counter(limits.maxTotalBytes)

        fun archive(channel: SeekableByteChannel, prefix: String, depth: Int) {
            when (formatOf(channel)) {
                Format.SEVEN_Z -> sevenZ(channel, prefix, depth)
                Format.RAR -> throw ImportRefused(ImportRefused.Reason.RAR)
                // Unknown first bytes may still be a zip with data in front of it (a self-extractor): its index is at the end.
                Format.ZIP, Format.UNKNOWN -> zip(channel, prefix, depth)
            }
        }

        private fun zip(channel: SeekableByteChannel, prefix: String, depth: Int) {
            val zf = try {
                ZipFile.builder().setSeekableByteChannel(channel).get()
            } catch (e: IOException) {
                throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
            } catch (e: IllegalArgumentException) {
                throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
            }
            // Not closed here: closing the ZipFile closes the channel, which the caller owns.
            val list = zf.entriesInPhysicalOrder.toList()
            if (entries + list.size > limits.maxEntries) throw ImportRefused(ImportRefused.Reason.TOO_MANY_ENTRIES)
            for (e in list) {
                countEntry()
                val name = nameOf(e)
                val kind = kindOf(name, e.isDirectory)
                if (kind == Kind.IGNORED) continue
                if (e.generalPurposeBit.usesEncryption() || e.generalPurposeBit.usesStrongEncryption()) {
                    throw ImportRefused(ImportRefused.Reason.ENCRYPTED_ARCHIVE)
                }
                if (!zf.canReadEntryData(e)) {
                    onSkip(prefix + name, SkipReason.UNSUPPORTED_COMPRESSION)
                    continue
                }
                entry(name, e.size, kind, prefix, depth) {
                    try {
                        zf.getInputStream(e)
                    } catch (x: IOException) {
                        throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
                    }
                }
            }
        }

        private fun sevenZ(channel: SeekableByteChannel, prefix: String, depth: Int) {
            val sz = try {
                SevenZFile.builder()
                    .setSeekableByteChannel(channel)
                    .setMaxMemoryLimitKiB(MAX_7Z_MEMORY_KIB)
                    .setTryToRecoverBrokenArchives(false)
                    .get()
            } catch (e: PasswordRequiredException) {
                throw ImportRefused(ImportRefused.Reason.ENCRYPTED_ARCHIVE) // encrypted headers
            } catch (e: MemoryLimitException) {
                throw ImportRefused(ImportRefused.Reason.UNSUPPORTED_COMPRESSION)
            } catch (e: IOException) {
                throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
            } catch (e: IllegalArgumentException) {
                throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
            }
            // Not closed here: closing the SevenZFile closes the channel, which the caller owns.
            if (entries + sz.entries.count() > limits.maxEntries) throw ImportRefused(ImportRefused.Reason.TOO_MANY_ENTRIES)
            // Read in order, never by jumping to an entry: a solid archive is one compressed stream, and every jump
            // would decompress everything before it. The current entry's bytes come from the reader itself.
            val current = object : InputStream() {
                override fun read(): Int = sz.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = sz.read(b, off, len)
            }
            try {
                while (true) {
                    val e = sz.nextEntry ?: break
                    countEntry()
                    val name = e.name.orEmpty().replace('\\', '/')
                    val kind = kindOf(name, e.isDirectory || !e.hasStream())
                    if (kind == Kind.IGNORED) continue
                    // An unknown method is refused before it is read; AES is the 7z password protection.
                    val methods = runCatching { e.contentMethods.map { it.method } }.getOrNull()
                    when {
                        methods == null || methods.any { it == null } -> throw ImportRefused(ImportRefused.Reason.UNSUPPORTED_COMPRESSION)
                        methods.any { it == SevenZMethod.AES256SHA256 } -> throw ImportRefused(ImportRefused.Reason.ENCRYPTED_ARCHIVE)
                        methods.any { it !in SEVEN_Z_METHODS } -> throw ImportRefused(ImportRefused.Reason.UNSUPPORTED_COMPRESSION)
                    }
                    entry(name, e.size, kind, prefix, depth) { current }
                }
            } catch (e: ImportRefused) {
                throw e
            } catch (e: PasswordRequiredException) {
                throw ImportRefused(ImportRefused.Reason.ENCRYPTED_ARCHIVE)
            } catch (e: MemoryLimitException) {
                throw ImportRefused(ImportRefused.Reason.UNSUPPORTED_COMPRESSION)
            } catch (e: IOException) {
                if (StorageSpace.isFull(e)) throw e
                throw ImportRefused(ImportRefused.Reason.NOT_AN_ARCHIVE)
            }
        }

        private fun countEntry() {
            if (++entries > limits.maxEntries) throw ImportRefused(ImportRefused.Reason.TOO_MANY_ENTRIES)
        }

        /** One entry of a known kind; [open] gives its bytes. Entries not read are never unpacked. */
        private fun entry(name: String, size: Long, kind: Kind, prefix: String, depth: Int, open: () -> InputStream) {
            when (kind) {
                Kind.IGNORED -> Unit
                Kind.COMIC_INFO -> open().use {
                    val parsed = readComicInfo(EntryStream(it, limits.maxFileBytes, total)) ?: return@use
                    if (info == null) info = parsed
                    onComicInfo(pageName(prefix, name).substringBeforeLast('/', ""), parsed)
                }
                Kind.RAR -> onSkip(prefix + name, SkipReason.UNSUPPORTED_FORMAT)
                Kind.ARCHIVE -> if (depth > 0) onSkip(prefix + name, SkipReason.NESTED_ARCHIVE) else nested(name, open)
                Kind.PAGE -> {
                    if (size > limits.maxFileBytes) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
                    if (++pages > limits.maxPages) throw ImportRefused(ImportRefused.Reason.TOO_MANY_PAGES)
                    open().use { onImage(pageName(prefix, name), EntryStream(it, limits.maxFileBytes, total)) }
                }
            }
        }

        /**
         * An archive inside the archive, unpacked into [scratch] within what is left of the total cap (its own entries
         * count again as they are read), then read with its name, less the extension, as the folder of its pages.
         */
        private fun nested(name: String, open: () -> InputStream) {
            val left = limits.maxTotalBytes - total.n
            scratch.mkdirs()
            val tmp = File.createTempFile("nested", ".bin", scratch)
            try {
                val copied = open().use { PageStager.copyCapped(it, tmp, left) }
                if (copied < 0) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
                RandomAccessFile(tmp, "r").use { raf ->
                    try {
                        archive(raf.channel, name.substringBeforeLast('.') + "/", depth = 1)
                    } catch (e: ImportRefused) {
                        // A bad inner archive is one skipped chapter, not a failed import; the caps still stop everything.
                        val reason = when (e.reason) {
                            ImportRefused.Reason.TOO_LARGE, ImportRefused.Reason.TOO_MANY_ENTRIES,
                            ImportRefused.Reason.TOO_MANY_PAGES, ImportRefused.Reason.NO_SPACE -> throw e
                            ImportRefused.Reason.RAR -> SkipReason.UNSUPPORTED_FORMAT
                            ImportRefused.Reason.UNSUPPORTED_COMPRESSION -> SkipReason.UNSUPPORTED_COMPRESSION
                            else -> SkipReason.UNREADABLE
                        }
                        onSkip(name, reason)
                    }
                }
            } finally {
                tmp.delete()
            }
        }

        private fun readComicInfo(body: InputStream): ComicInfo? {
            val bytes = body.readAtMost(ComicInfo.MAX_BYTES + 1)
            if (bytes.size > ComicInfo.MAX_BYTES) return null
            return ComicInfo.parse(String(bytes, Charsets.UTF_8).removePrefix("﻿"))
        }
    }

    private enum class Kind { IGNORED, COMIC_INFO, ARCHIVE, RAR, PAGE }

    /** Counts bytes against the archive's total cap. */
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
            // Skipping still unpacks; read so it is counted.
            val buf = ByteArray(minOf(n, 8192L).toInt().coerceAtLeast(1))
            val r = read(buf, 0, buf.size)
            return if (r < 0) 0 else r.toLong()
        }

        /** The reader closes the entry's stream itself. */
        override fun close() = Unit

        private fun count(k: Int) {
            n += k
            if (n > max) throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
            total.add(k)
        }
    }

    companion object {
        /** 7z decoders allocate their dictionary (LZMA "ultra" presets: 64 MB and more); an archive needing more is refused. */
        const val MAX_7Z_MEMORY_KIB = 192 * 1024

        private val ARCHIVE_EXT = setOf("cbz", "zip", "cb7", "7z")
        private val RAR_EXT = setOf("cbr", "rar")

        /**
         * Files archivers, scanners and readers put beside the pages: never offered as pages, never listed as skipped.
         * `tmp` / `part` / `crdownload`: unfinished downloads a reader app (Mihon) leaves in its folders.
         */
        private val NOT_PAGES = setOf(
            "xml", "txt", "nfo", "json", "md", "htm", "html", "url", "ini", "db", "log", "csv", "yml", "yaml",
            "sfv", "md5", "sha1", "sha256", "opf", "ncx", "css", "js", "nomedia", "par2", "torrent",
            "tmp", "part", "crdownload",
        )

        private val SEVEN_Z_METHODS = setOf(
            SevenZMethod.COPY, SevenZMethod.LZMA, SevenZMethod.LZMA2, SevenZMethod.DEFLATE, SevenZMethod.DEFLATE64,
            SevenZMethod.BZIP2, SevenZMethod.BCJ_X86_FILTER, SevenZMethod.BCJ_PPC_FILTER, SevenZMethod.BCJ_IA64_FILTER,
            SevenZMethod.BCJ_ARM_FILTER, SevenZMethod.BCJ_ARM_THUMB_FILTER, SevenZMethod.BCJ_SPARC_FILTER,
            SevenZMethod.DELTA_FILTER,
        )

        private val SEVEN_Z_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        private val RAR_MAGIC = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

        /** The archive's format from its first bytes; the channel is left at position 0. */
        fun formatOf(channel: SeekableByteChannel): Format {
            val head = ByteBuffer.allocate(8)
            channel.position(0)
            while (head.hasRemaining() && channel.read(head) > 0) Unit
            channel.position(0)
            val b = head.array()
            val n = head.position()
            fun starts(p: ByteArray) = n >= p.size && p.indices.all { b[it] == p[it] }
            val pk = n >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte()
            return when {
                // Local file header, empty archive, spanned-archive marker.
                pk && ((b[2].toInt() == 3 && b[3].toInt() == 4) || (b[2].toInt() == 5 && b[3].toInt() == 6) || (b[2].toInt() == 7 && b[3].toInt() == 8)) -> Format.ZIP
                starts(SEVEN_Z_MAGIC) -> Format.SEVEN_Z
                starts(RAR_MAGIC) -> Format.RAR
                else -> Format.UNKNOWN
            }
        }

        private fun kindOf(name: String, directory: Boolean): Kind {
            val leaf = name.substringAfterLast('/')
            val ext = leaf.substringAfterLast('.', "").lowercase()
            return when {
                directory || leaf.isEmpty() || skipped(name) -> Kind.IGNORED
                leaf.equals("ComicInfo.xml", ignoreCase = true) -> Kind.COMIC_INFO
                ext in ARCHIVE_EXT -> Kind.ARCHIVE
                ext in RAR_EXT -> Kind.RAR
                ext in NOT_PAGES || leaf.equals("Thumbs.db", ignoreCase = true) -> Kind.IGNORED
                else -> Kind.PAGE
            }
        }

        /** macOS resource forks and hidden files. */
        fun skipped(name: String): Boolean {
            val parts = name.split('/').filter { it.isNotEmpty() }
            return parts.any { it == "__MACOSX" } || parts.lastOrNull()?.startsWith(".") == true
        }

        /** A page's name: inside an inner archive, its own folders flattened under the inner archive's name. */
        internal fun pageName(prefix: String, name: String): String =
            if (prefix.isEmpty()) name else prefix + name.trimStart('/').replace('/', ' ')

        /**
         * A zip entry's name. The UTF-8 flag or a Unicode path field is trusted; otherwise the raw bytes are read as
         * UTF-8 when they are valid UTF-8 (many tools write it without the flag), then as Shift_JIS (Japanese archives),
         * then GB18030, then Latin-1. Digits and Latin letters come out the same in all of them, so page order holds
         * even when a guess is wrong.
         */
        internal fun nameOf(e: ZipArchiveEntry): String {
            val decoded = if (e.generalPurposeBit.usesUTF8ForNames() || e.getExtraField(UnicodePathExtraField.UPATH_ID) != null) {
                e.name
            } else decodeName(e.rawName ?: e.name.toByteArray(Charsets.UTF_8))
            return decoded.replace('\\', '/')
        }

        internal fun decodeName(raw: ByteArray): String {
            for (cs in NAME_CHARSETS) {
                val c = runCatching { Charset.forName(cs) }.getOrNull() ?: continue
                try {
                    return c.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw))
                        .toString()
                } catch (_: CharacterCodingException) {
                }
            }
            return String(raw, Charsets.ISO_8859_1)
        }

        private val NAME_CHARSETS = listOf("UTF-8", "Shift_JIS", "GB18030")
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
