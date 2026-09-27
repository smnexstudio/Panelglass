package com.smnexstudio.panelglass.core.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max

/** Scales a page band for the detector. */
object ImageDecoder {
    const val DETECT_EDGE = 1600

    /** ARGB copy scaled so the longest edge is [edge]; ML Kit wants ARGB_8888 input. Returns scale factor applied. */
    fun forDetection(src: Bitmap, edge: Int = DETECT_EDGE): Pair<Bitmap, Float> {
        val longest = max(src.width, src.height)
        val scale = if (longest > edge) edge.toFloat() / longest else 1f
        val w = max(1, (src.width * scale).toInt()); val h = max(1, (src.height * scale).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out to scale
    }
}
