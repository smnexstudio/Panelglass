package com.smnexstudio.panelglass.feature.studio.importer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageHeaderTest {
    private fun probe(b: ByteArray) = ImageHeader.probe(b.inputStream())

    @Test fun png() = assertEquals(ImageHeader(ImageFormat.PNG, 800, 20000), probe(Fixtures.png(800, 20000)))

    @Test fun jpegFrameAfterBigExif() = assertEquals(ImageHeader(ImageFormat.JPEG, 1600, 2400), probe(Fixtures.jpeg(1600, 2400)))

    @Test fun webpVariants() {
        assertEquals(ImageHeader(ImageFormat.WEBP, 1000, 1500), probe(Fixtures.webpLossy(1000, 1500)))
        assertEquals(ImageHeader(ImageFormat.WEBP, 1000, 1500), probe(Fixtures.webpLossless(1000, 1500)))
        assertEquals(ImageHeader(ImageFormat.WEBP, 1000, 16000), probe(Fixtures.webpExtended(1000, 16000, animated = false)))
    }

    @Test fun animatedWebpIsConvertedNotCopied() {
        val h = probe(Fixtures.webpExtended(500, 700, animated = true))!!
        assertTrue(h.animated)
        assertFalse(h.copyAsIs)
    }

    @Test fun gifAndAvifAreConverted() {
        assertFalse(probe(Fixtures.gif(300, 400))!!.copyAsIs)
        val avif = probe(Fixtures.avif())!!
        assertEquals(ImageFormat.AVIF, avif.format)
        assertFalse(avif.copyAsIs) // size unknown until the platform reads it
    }

    @Test fun stillFormatsAreCopied() {
        assertTrue(probe(Fixtures.png(10, 10))!!.copyAsIs)
        assertTrue(probe(Fixtures.jpeg(10, 10, exifBytes = 0))!!.copyAsIs)
        assertTrue(probe(Fixtures.webpLossy(10, 10))!!.copyAsIs)
    }

    @Test fun heicAndJunkAreNotImages() {
        assertNull(probe(Fixtures.heic()))
        assertNull(probe("hello world, not an image at all".toByteArray()))
        assertNull(probe(ByteArray(0)))
        assertNull(probe(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) // a JPEG cut off before its frame
    }
}
