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
