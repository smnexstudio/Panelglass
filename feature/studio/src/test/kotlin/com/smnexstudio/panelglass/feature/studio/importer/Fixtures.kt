package com.smnexstudio.panelglass.feature.studio.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Image headers good enough for [ImageHeader.probe]; the pixels after them are filler, never decoded here. */
internal object Fixtures {
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le24(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    fun png(w: Int, h: Int, filler: Int = 64): ByteArray =
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + be32(13) + "IHDR".toByteArray() + be32(w) + be32(h) +
            bytes(8, 6, 0, 0, 0) + ByteArray(4) + ByteArray(filler)

    /** A JPEG with a large EXIF segment before its frame header, as phone and scanner files have. */
    fun jpeg(w: Int, h: Int, exifBytes: Int = 70_000): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes(0xFF, 0xD8))
        var left = exifBytes
        while (left > 0) {
            val n = minOf(left, 60_000)
            out.write(bytes(0xFF, 0xE1)); out.write(be16(n + 2)); out.write(ByteArray(n))
            left -= n
        }
        out.write(bytes(0xFF, 0xC0)); out.write(be16(17)); out.write(8); out.write(be16(h)); out.write(be16(w)); out.write(ByteArray(10))
        out.write(ByteArray(32))
        out.write(bytes(0xFF, 0xD9))
        return out.toByteArray()
    }

    fun webpLossy(w: Int, h: Int) =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8 ".toByteArray() + ByteArray(4) +
            ByteArray(3) + bytes(0x9D, 0x01, 0x2A) + le16(w) + le16(h) + ByteArray(32)

    fun webpLossless(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        return "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8L".toByteArray() + ByteArray(4) +
            bytes(0x2F) + bytes(bits and 0xFF, (bits ushr 8) and 0xFF, (bits ushr 16) and 0xFF, (bits ushr 24) and 0xFF) + ByteArray(32)
    }

    fun webpExtended(w: Int, h: Int, animated: Boolean) =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + "VP8X".toByteArray() + ByteArray(4) +
            bytes(if (animated) 0x02 else 0x00, 0, 0, 0) + le24(w - 1) + le24(h - 1) + ByteArray(32)

    fun gif(w: Int, h: Int) = "GIF89a".toByteArray() + le16(w) + le16(h) + ByteArray(32)

    fun avif() = be32(28) + "ftypavif".toByteArray() + ByteArray(32)

    fun heic() = be32(28) + "ftypheic".toByteArray() + ByteArray(32)

    /** An `ftyp` box with a generic main type and its real type among the compatible ones, as many encoders write. */
    fun ftyp(major: String, vararg compatible: String): ByteArray {
        val body = major.toByteArray() + ByteArray(4) + compatible.fold(ByteArray(0)) { a, b -> a + b.toByteArray() }
        return be32(8 + body.size) + "ftyp".toByteArray() + body + ByteArray(32)
    }

    fun jxlCodestream() = bytes(0xFF, 0x0A) + ByteArray(40)
    fun jxlContainer() = be32(12) + "JXL ".toByteArray() + bytes(0x0D, 0x0A, 0x87, 0x0A) + ByteArray(40)
    fun bmp() = "BM".toByteArray() + ByteArray(60)

    /** A RAR 5 signature and nothing usable after it: the importer only has to recognise it. */
    fun rar() = "Rar!".toByteArray() + bytes(0x1A, 0x07, 0x01, 0x00) + ByteArray(64)

    /** One entry of [rawZip]: its name as raw bytes, its data stored (or flagged with another [method]). */
    class RawEntry(
        val name: ByteArray,
        val data: ByteArray,
        val utf8Flag: Boolean = false,
        /** Sizes and CRC after the data (bit 3), zero in the local header: what streaming zip writers produce. */
        val descriptor: Boolean = true,
        val encrypted: Boolean = false,
        /** 0 = stored. Anything else is written as is, with the data stored, for a method the reader can't handle. */
        val method: Int = 0,
    )

    /**
     * A zip written by hand, entry by entry, so that the layouts `java.util.zip.ZipOutputStream` never writes can be
     * tested: stored entries with a data descriptor, names that are not UTF-8 and carry no UTF-8 flag, the encryption
     * bit, other compression methods.
     */
    fun rawZip(entries: List<RawEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        for (e in entries) {
            val crc = java.util.zip.CRC32().apply { update(e.data) }.value.toInt()
            var flags = 0
            if (e.encrypted) flags = flags or 0x01
            if (e.descriptor) flags = flags or 0x08
            if (e.utf8Flag) flags = flags or 0x800
            val offset = out.size()
            out.write(le32(0x04034b50)); out.write(le16(20)); out.write(le16(flags)); out.write(le16(e.method))
            out.write(le16(0)); out.write(le16(0x21)) // time, date
            if (e.descriptor) { out.write(le32(0)); out.write(le32(0)); out.write(le32(0)) }
            else { out.write(le32(crc)); out.write(le32(e.data.size)); out.write(le32(e.data.size)) }
            out.write(le16(e.name.size)); out.write(le16(0)); out.write(e.name)
            out.write(e.data)
            if (e.descriptor) { out.write(le32(0x08074b50)); out.write(le32(crc)); out.write(le32(e.data.size)); out.write(le32(e.data.size)) }
            central.write(le32(0x02014b50)); central.write(le16(20)); central.write(le16(20)); central.write(le16(flags))
            central.write(le16(e.method)); central.write(le16(0)); central.write(le16(0x21))
            central.write(le32(crc)); central.write(le32(e.data.size)); central.write(le32(e.data.size))
            central.write(le16(e.name.size)); central.write(le16(0)); central.write(le16(0)); central.write(le16(0))
            central.write(le16(0)); central.write(le32(0)); central.write(le32(offset)); central.write(e.name)
        }
        val cdOffset = out.size()
        val cd = central.toByteArray()
        out.write(cd)
        out.write(le32(0x06054b50)); out.write(le16(0)); out.write(le16(0)); out.write(le16(entries.size)); out.write(le16(entries.size))
        out.write(le32(cd.size)); out.write(le32(cdOffset)); out.write(le16(0))
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** A 7z archive (LZMA2, solid) written with Commons Compress, the library that reads it. */
    fun sevenZ(entries: List<Pair<String, ByteArray>>): ByteArray {
        val channel = org.apache.commons.compress.utils.SeekableInMemoryByteChannel()
        var size = 0L
        org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(channel).use { z ->
            for ((name, data) in entries) {
                val e = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                e.name = name
                e.size = data.size.toLong()
                z.putArchiveEntry(e)
                z.write(data)
                z.closeArchiveEntry()
            }
            z.finish()
            size = channel.size() // closing the writer closes the channel
        }
        return channel.array().copyOf(size.toInt())
    }

    fun zip(entries: List<Pair<String, ByteArray>>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Stands in for the platform decoders: every file "decodes" to [size] and is written as a marker file. */
    class FakeTranscoder(private val size: Pair<Int, Int>? = 800 to 1200) : Transcoder {
        val converted = mutableListOf<String>()
        override fun bounds(src: File) = size
        override fun toPng(src: File, dst: File): Pair<Int, Int>? {
            val s = size ?: return null
            dst.writeText("png")
            converted += dst.path
            return s
        }
    }
}
