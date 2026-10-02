package com.smnexstudio.panelglass.feature.studio.review

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One zoom tile: [sample] is the decode's power-of-two downscale, [col]/[row] its place in that level's grid. */
data class TileKey(val sample: Int, val col: Int, val row: Int)

/**
 * Which full-resolution tiles a view needs. The editor shows a downsampled base of the page; once the zoom asks for
 * more pixels than the base has, the visible area is drawn from tiles decoded at the sample that matches the screen.
 * Pure: tested on the JVM (`TileCacheTest`).
 */
object TileGrid {
    /** Tile edge in decoded pixels. */
    const val TILE = 512

    /**
     * The decode sample for a view drawing [screenPerPage] screen pixels per page pixel: the largest power of two that
     * still gives at least one decoded pixel per screen pixel. Null when the base ([baseSample]) is already enough.
     */
    fun sampleFor(screenPerPage: Float, baseSample: Int): Int? {
        var s = 1
        while (s * 2 <= baseSample && 1f / (s * 2) >= screenPerPage) s *= 2
        return if (s >= baseSample) null else s
    }

    /** Page rect a tile covers (page pixels, clipped to the page). */
    fun rect(key: TileKey, pageW: Int, pageH: Int): Box {
        val span = TILE * key.sample
        val l = key.col * span
        val t = key.row * span
        return Box(l.toFloat(), t.toFloat(), min(pageW, l + span).toFloat(), min(pageH, t + span).toFloat())
    }

    /** The tiles at [sample] that intersect [visible] (page pixels). */
    fun visible(visible: Box, sample: Int, pageW: Int, pageH: Int): List<TileKey> {
        val span = (TILE * sample).toFloat()
        val c0 = max(0, floor(visible.left / span).toInt())
        val r0 = max(0, floor(visible.top / span).toInt())
        val c1 = min(ceil(pageW / span).toInt() - 1, floor((visible.right - 0.001f) / span).toInt())
        val r1 = min(ceil(pageH / span).toInt() - 1, floor((visible.bottom - 0.001f) / span).toInt())
        val out = ArrayList<TileKey>()
        for (r in r0..r1) for (c in c0..c1) out += TileKey(sample, c, r)
        return out
    }
}

/**
 * A least-recently-used store bounded by [budget] bytes (not entries: a strip's edge tiles are small). [onEvict] frees
 * what falls out. Pure, for the same tests.
 */
class TileLru<K, V>(private val budget: Long, private val sizeOf: (V) -> Long, private val onEvict: (V) -> Unit = {}) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var bytes = 0L
        private set

    @Synchronized operator fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it); onEvict(it) }
        map[key] = value
        bytes += sizeOf(value)
        val it = map.entries.iterator()
        while (bytes > budget && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            it.remove()
            bytes -= sizeOf(e.value)
            onEvict(e.value)
        }
    }

    @Synchronized fun clear() {
        map.values.forEach(onEvict)
        map.clear()
        bytes = 0
    }

    @Synchronized fun keys(): Set<K> = map.keys.toSet()
}

/**
 * One page's pixels for the editor: a downsampled [base] (at most [BASE_PIXELS]) and full-resolution tiles decoded on
 * demand into an LRU of [budget] bytes. [close] releases everything; a page change closes the previous one.
 */
class PageTiles private constructor(
    /** Null for a page held only in memory (the rendered Final layer): it has its base and no tiles. */
    private val file: File?,
    val pageW: Int,
    val pageH: Int,
    val base: Bitmap,
    val baseSample: Int,
    budget: Long,
) {
    private val cache = TileLru<TileKey, Bitmap>(budget, { it.allocationByteCount.toLong() }, { it.recycle() })
    private var decoder: BitmapRegionDecoder? = null

    operator fun get(key: TileKey): Bitmap? = cache[key]?.takeIf { !it.isRecycled }

    /** Decodes [key] unless cached; call off the main thread. */
    @Synchronized fun load(key: TileKey): Bitmap? {
        cache[key]?.let { if (!it.isRecycled) return it }
        val path = file?.path ?: return null
        val d = decoder ?: runCatching { BitmapRegionDecoder.newInstance(path) }.getOrNull()?.also { decoder = it } ?: return null
        val r = TileGrid.rect(key, pageW, pageH)
        val bmp = runCatching {
            d.decodeRegion(
                Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()),
                BitmapFactory.Options().apply { inSampleSize = key.sample },
            )
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    @Synchronized fun close() {
        cache.clear()
        decoder?.recycle()
        decoder = null
        base.recycle()
    }

    companion object {
        /** The base the page is drawn from until the zoom needs more: ~8 MP (32 MB) at most. */
        const val BASE_PIXELS = 8_000_000L
        const val BUDGET = 24L * 1024 * 1024
        /** Phones under 4 GB of RAM (docs/STUDIO_PLAN.md › Features by device RAM). */
        const val BUDGET_SMALL = 12L * 1024 * 1024

        fun budgetFor(context: Context): Long {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            return if (mi.totalMem < 4L * 1024 * 1024 * 1024) BUDGET_SMALL else BUDGET
        }

        /**
         * A bitmap already in memory as a page of [pageW] × [pageH]: [base] is drawn scaled to the page, and since there
         * is no file there are no tiles. Its sample is the nearest power of two (only used to decide on tiles).
         */
        fun ofBitmap(base: Bitmap, pageW: Int, pageH: Int): PageTiles {
            var sample = 1
            while (base.width * sample * 2 <= pageW) sample *= 2
            return PageTiles(null, pageW, pageH, base, sample, 0)
        }

        /** Opens [file]: bounds, then the base at the smallest power-of-two sample that fits [BASE_PIXELS]. */
        fun open(file: File, budget: Long): PageTiles? {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, b)
            if (b.outWidth <= 0 || b.outHeight <= 0) return null
            var sample = 1
            while (b.outWidth.toLong() / sample * (b.outHeight.toLong() / sample) > BASE_PIXELS) sample *= 2
            val base = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return PageTiles(file, b.outWidth, b.outHeight, base, sample, budget)
        }
    }
}
