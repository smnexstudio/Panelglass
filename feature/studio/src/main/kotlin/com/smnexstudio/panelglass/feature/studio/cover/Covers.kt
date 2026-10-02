package com.smnexstudio.panelglass.feature.studio.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Where a 2 : 3 cover sits in a picture: the largest 2 : 3 window that fits, slid by [biasX] / [biasY] (−1 left/top,
 * 0 centre, 1 right/bottom) along the side that has room.
 */
object CoverCrop {
    const val RATIO = 2f / 3f

    /** The window in [w] × [h] pixels: left, top, right, bottom. */
    fun window(w: Int, h: Int, biasX: Float, biasY: Float): IntArray {
        if (w <= 0 || h <= 0) return intArrayOf(0, 0, 0, 0)
        val cw: Int
        val ch: Int
        if (w.toFloat() / h > RATIO) { ch = h; cw = (h * RATIO).roundToInt().coerceIn(1, w) } else { cw = w; ch = (w / RATIO).roundToInt().coerceIn(1, h) }
        val left = ((w - cw) * (biasX.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt()
        val top = ((h - ch) * (biasY.coerceIn(-1f, 1f) + 1f) / 2f).roundToInt()
        return intArrayOf(left, top, left + cw, top + ch)
    }
}

/** What a cover is made from: a page of the manga, or a picture the user picked. */
sealed interface CoverSource {
    data class Page(val file: File) : CoverSource
    data class Picked(val uri: Uri) : CoverSource
}

/**
 * The manga's cover file: made from a page or a picked picture, cropped to 2 : 3 at the chosen place and saved as a
 * 600 × 900 JPEG (`covers/<mangaId>.jpg`); removed, the first page stands in again. The picture is decoded small
 * (no more than twice the cover's size), so a huge photo costs little.
 */
@Singleton
class Covers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: StudioRepository,
) {
    /** Bumped on every save or clear: lists showing covers read them again. */
    val version = MutableStateFlow(0)

    fun file(mangaId: Long): File = repo.files.cover(mangaId)

    /** The custom cover when there is one. */
    fun custom(mangaId: Long): File? = file(mangaId).takeIf { it.isFile && it.length() > 0 }

    /** A preview-sized picture of [source] (about [targetPx] on its long side), or null when it cannot be read. */
    suspend fun preview(source: CoverSource, targetPx: Int): Bitmap? = withContext(Dispatchers.IO) { decode(source, targetPx) }

    /** Saves [source] cropped at [biasX] / [biasY] as the cover; false when the picture cannot be read. */
    suspend fun save(mangaId: Long, source: CoverSource, biasX: Float, biasY: Float): Boolean = withContext(Dispatchers.IO) {
        val bmp = decode(source, H * 2) ?: return@withContext false
        try {
            val (l, t, r, b) = CoverCrop.window(bmp.width, bmp.height, biasX, biasY).let { listOf(it[0], it[1], it[2], it[3]) }
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            try {
                Canvas(out).drawBitmap(bmp, Rect(l, t, r, b), Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
                val target = file(mangaId)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".part")
                tmp.outputStream().buffered().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                version.value++
                true
            } finally {
                out.recycle()
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Back to the first page. */
    suspend fun clear(mangaId: Long) {
        withContext(Dispatchers.IO) { file(mangaId).delete() }
        version.value++
    }

    private fun decode(source: CoverSource, targetPx: Int): Bitmap? = runCatching {
        when (source) {
            is CoverSource.Page -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
                BitmapFactory.decodeFile(source.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            is CoverSource.Picked -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source.uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val long = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (long / (sample * 2) >= targetPx) sample *= 2
                d.setTargetSampleSize(sample)
            }
        }
    }.getOrNull()

    companion object {
        const val W = 600
        const val H = 900
    }
}
