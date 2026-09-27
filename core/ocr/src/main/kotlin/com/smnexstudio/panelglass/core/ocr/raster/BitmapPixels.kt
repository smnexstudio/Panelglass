package com.smnexstudio.panelglass.core.ocr.raster

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.IntRect

/** [PixelSource] over an Android bitmap; crops via getPixels so the page is never copied whole. */
class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun crop(rect: IntRect): Raster {
        val r = rect.clamp(width, height)
        val out = IntArray(maxOf(0, r.width * r.height))
        if (!r.isEmpty) bitmap.getPixels(out, 0, r.width, r.left, r.top, r.width, r.height)
        return Raster(r.width, r.height, out)
    }
}

object BitmapGray {
    /** Luminance of [bitmap] downsampled by [factor] using box averaging, with a single row buffer. */
    fun downsampled(bitmap: Bitmap, factor: Int): Gray {
        val f = maxOf(1, factor)
        val w = maxOf(1, bitmap.width / f); val h = maxOf(1, bitmap.height / f)
        val out = ByteArray(w * h)
        val rows = IntArray(bitmap.width * f)
        for (y in 0 until h) {
            val rowsHere = minOf(f, bitmap.height - y * f)
            bitmap.getPixels(rows, 0, bitmap.width, 0, y * f, bitmap.width, rowsHere)
            for (x in 0 until w) {
                var s = 0
                for (dy in 0 until rowsHere) {
                    val base = dy * bitmap.width + x * f
                    for (dx in 0 until f) s += Raster.lum(rows[base + dx])
                }
                out[y * w + x] = (s / (rowsHere * f)).toByte()
            }
        }
        return Gray(w, h, out)
    }
}
