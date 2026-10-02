package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Encodes a rendered region crop as WEBP: lossless at quality 100, a few KB and ~5 ms. */
@Singleton
class PatchEncoder @Inject constructor() {
    fun encode(bitmap: Bitmap, quality: Int = 100): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val format = if (quality >= 100) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        bitmap.compress(format, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}
