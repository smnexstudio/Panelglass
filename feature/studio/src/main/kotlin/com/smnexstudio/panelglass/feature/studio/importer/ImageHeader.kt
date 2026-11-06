package com.smnexstudio.panelglass.feature.studio.importer

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

enum class ImageFormat(val ext: String) { PNG("png"), JPEG("jpg"), WEBP("webp"), GIF("gif"), AVIF("avif") }

/**
 * Format and size read from an image's header, without decoding it, so the pixel cap is checked before any
 * allocation. [width]/[height] are 0 when the header does not carry them (AVIF: the platform reads the bounds).
 */
data class ImageHeader(val format: ImageFormat, val width: Int, val height: Int, val animated: Boolean = false) {
    val pixels: Long get() = width.toLong() * height

    /** Kept byte for byte as the page file: a format every Android 12 decoder reads, one frame, size known. */
    val copyAsIs: Boolean get() = !animated && width > 0 && height > 0 && format in COPYABLE

    companion object {
        private val COPYABLE = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP)
        private val AVIF_BRANDS = setOf("avif", "avis")
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "miaf")

        /** Null when the stream is not an image the Studio takes (HEIC included: not every Android 12 phone decodes it). */
        fun probe(input: InputStream): ImageHeader? = try {
            val d = DataInputStream(input)
            val head = ByteArray(HEAD)
            val n = readUpTo(d, head)
            when {
                n >= 24 && head.startsWith(PNG_SIG) && head.ascii(12, 4) == "IHDR" ->
                    ImageHeader(ImageFormat.PNG, head.be32(16), head.be32(20))
                n >= 3 && head.u8(0) == 0xFF && head.u8(1) == 0xD8 && head.u8(2) == 0xFF -> jpeg(d, head, n)
                n >= 30 && head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WEBP" -> webp(head)
                n >= 10 && (head.ascii(0, 6) == "GIF87a" || head.ascii(0, 6) == "GIF89a") ->
                    ImageHeader(ImageFormat.GIF, head.le16(6), head.le16(8), animated = true)
                n >= 12 && head.ascii(4, 4) == "ftyp" && isAvif(head, n) -> ImageHeader(ImageFormat.AVIF, 0, 0)
                else -> null
            }
        } catch (_: java.io.IOException) {
            null
        }

        /**
         * An AVIF's `ftyp` box: `avif` / `avis` as its main type, or among its compatible types behind a generic one
         * (`mif1`, `msf1`, `miaf`: many encoders write those first). A HEIC lists `heic` there instead, and is refused.
         */
        private fun brands(h: ByteArray, n: Int): List<String> {
            val box = minOf(h.be32(0).toLong().coerceIn(0, n.toLong()).toInt(), n)
            val list = mutableListOf(h.ascii(8, 4))
            var i = 16 // after the main type and its minor version
            while (i + 4 <= box) { list += h.ascii(i, 4); i += 4 }
            return list
        }

        private fun isAvif(h: ByteArray, n: Int): Boolean = brands(h, n).any { it in AVIF_BRANDS }

        /**
         * True for an image the Studio recognises but can't decode on every Android 12 phone: HEIC / HEIF, JPEG XL,
         * BMP and TIFF. A file that [probe] takes is never one of these.
         */
        fun isUnsupportedImage(input: InputStream): Boolean = try {
            val h = ByteArray(HEAD)
            val n = readUpTo(input, h)
            when {
                n >= 12 && h.ascii(4, 4) == "ftyp" -> !isAvif(h, n) && brands(h, n).any { it in HEIF_BRANDS }
                n >= 2 && h.u8(0) == 0xFF && h.u8(1) == 0x0A -> true // JPEG XL codestream
                n >= 12 && h.be32(0) == 12 && h.ascii(4, 4) == "JXL " -> true // JPEG XL container
                n >= 14 && h.ascii(0, 2) == "BM" -> true
                n >= 4 && (h.ascii(0, 4) == "II*\u0000" || h.ascii(0, 4) == "MM\u0000*") -> true
                else -> false
            }
        } catch (_: java.io.IOException) {
            false
        }

        private fun webp(h: ByteArray): ImageHeader? = when (h.ascii(12, 4)) {
            "VP8 " -> {
                if (h.u8(23) != 0x9D || h.u8(24) != 0x01 || h.u8(25) != 0x2A) null
                else ImageHeader(ImageFormat.WEBP, h.le16(26) and 0x3FFF, h.le16(28) and 0x3FFF)
            }
            "VP8L" -> {
                if (h.u8(20) != 0x2F) null
                else {
                    val bits = h.u8(21) or (h.u8(22) shl 8) or (h.u8(23) shl 16) or (h.u8(24) shl 24)
                    ImageHeader(ImageFormat.WEBP, (bits and 0x3FFF) + 1, ((bits ushr 14) and 0x3FFF) + 1)
                }
            }
            "VP8X" -> ImageHeader(
                ImageFormat.WEBP,
                (h.u8(24) or (h.u8(25) shl 8) or (h.u8(26) shl 16)) + 1,
                (h.u8(27) or (h.u8(28) shl 8) or (h.u8(29) shl 16)) + 1,
                animated = h.u8(20) and 0x02 != 0,
            )
            else -> null
        }

        /** Walks the JPEG markers to the first start-of-frame (EXIF and ICC segments come first and can be large). */
        private fun jpeg(d: DataInputStream, head: ByteArray, n: Int): ImageHeader? {
            // Replay what was already read, then continue from the stream.
            val s = DataInputStream(java.io.SequenceInputStream(head.inputStream(2, n - 2), d))
            var budget = MAX_JPEG_SCAN
            while (budget > 0) {
                var b = s.readUnsignedByte()
                if (b != 0xFF) return null
                while (b == 0xFF) b = s.readUnsignedByte() // fill bytes
                val marker = b
                if (marker == 0xD9 || marker == 0xDA) return null // end of image / start of scan before a frame
                if (marker == 0x01 || marker in 0xD0..0xD7) continue
                val len = s.readUnsignedShort()
                if (len < 2) return null
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    s.readUnsignedByte() // precision
                    val height = s.readUnsignedShort()
                    val width = s.readUnsignedShort()
                    return ImageHeader(ImageFormat.JPEG, width, height)
                }
                skipFully(s, len - 2L)
                budget -= len
            }
            return null
        }

        private const val MAX_JPEG_SCAN = 4 * 1024 * 1024

        /** Bytes read before deciding: enough for a WebP VP8X header and an `ftyp` box with a dozen compatible types. */
        private const val HEAD = 64
        private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        private fun readUpTo(d: InputStream, buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = d.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        private fun skipFully(s: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val k = s.skip(left)
                if (k <= 0) {
                    if (s.read() < 0) throw EOFException()
                    left--
                } else left -= k
            }
        }

        private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
        private fun ByteArray.be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
        private fun ByteArray.le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        private fun ByteArray.ascii(i: Int, len: Int) = String(this, i, len, Charsets.ISO_8859_1)
        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
