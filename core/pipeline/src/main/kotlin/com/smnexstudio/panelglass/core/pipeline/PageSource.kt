package com.smnexstudio.panelglass.core.pipeline

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.ocr.raster.BitmapGray
import com.smnexstudio.panelglass.core.ocr.raster.BitmapPixelSource
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.ocr.raster.PixelSource
import com.smnexstudio.panelglass.core.ocr.raster.Raster

/** Uniform access to a page's pixels for the pipeline stages: one bitmap the caller owns (the viewport snapshot). */
class PageSource private constructor(val bitmap: Bitmap) : PixelSource {
    private val pixels = BitmapPixelSource(bitmap)
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override fun crop(rect: IntRect): Raster = pixels.crop(rect)

    /** Luminance of the whole page at 1/8 scale for the placement energy map. */
    fun gray8(): Gray = BitmapGray.downsampled(bitmap, 8)

    companion object {
        fun of(bitmap: Bitmap): PageSource = PageSource(bitmap)
    }
}
