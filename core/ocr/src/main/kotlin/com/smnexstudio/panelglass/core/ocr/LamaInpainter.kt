package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fills masked pixels of a picture from what surrounds them. */
interface Inpainter {
    /** Whether it can run now (the model is installed and this phone has the memory). */
    val available: Boolean

    /**
     * Fills the pixels of [crop] that [mask] marks (mask coordinates are [crop]'s: `left`/`top` 0). Returns false
     * when it did not run; [crop] is then unchanged.
     */
    fun inpaint(crop: Raster, mask: GlyphMask): Boolean

    companion object {
        /** No model: every caller falls back to its own fill. */
        val NONE = object : Inpainter {
            override val available = false
            override fun inpaint(crop: Raster, mask: GlyphMask) = false
        }
    }
}

/**
 * LaMa on ONNX Runtime (CPU, the detector's options). The model takes exactly 512×512: a square window around the mask
 * (about twice its size, so the model sees enough of the art) is resized to 512, filled, resized back, and only the
 * masked pixels are written into the crop, so nothing outside the mask ever changes. One fill runs at a time; the
 * session (~200 MB of weights plus working memory) is released when the app is asked to trim memory.
 */
@Singleton
class LamaInpainter @Inject constructor(private val store: LamaStore) : Inpainter {
    private val lock = ReentrantLock()
    private var session: OrtSession? = null

    override val available: Boolean get() = store.isReady && store.fitsThisPhone

    override fun inpaint(crop: Raster, mask: GlyphMask): Boolean {
        if (!available) return false
        val b = bounds(mask) ?: return false
        return lock.withLock {
            val started = System.nanoTime()
            runCatching { fill(crop, mask, b) }
                .onSuccess { if (it) Log.i(TAG, "filled ${b[2] - b[0]}x${b[3] - b[1]} px in ${(System.nanoTime() - started) / 1_000_000} ms") }
                .onFailure { Log.w(TAG, "inpaint failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
        }
    }

    private fun fill(crop: Raster, mask: GlyphMask, b: IntArray): Boolean {
        val s = sessionOrNull() ?: return false
        val env = OrtEnvironment.getEnvironment()
        // A square window around the mask, about twice its size, kept inside the crop.
        val side = min(max(max(b[2] - b[0], b[3] - b[1]) * 2, 128), max(crop.width, crop.height))
        val cx = (b[0] + b[2]) / 2
        val cy = (b[1] + b[3]) / 2
        val left = (cx - side / 2).coerceIn(0, max(0, crop.width - side))
        val top = (cy - side / 2).coerceIn(0, max(0, crop.height - side))
        val w = min(side, crop.width - left)
        val h = min(side, crop.height - top)

        val img = FloatArray(3 * N * N)
        val msk = FloatArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            // Bilinear sample of the window (edge pixels repeat where the window is narrower than the square).
            val px = bilinear(crop, left + (x + 0.5f) * w / N - 0.5f, top + (y + 0.5f) * h / N - 0.5f)
            val i = y * N + x
            img[i] = ((px shr 16) and 0xFF) / 255f
            img[N * N + i] = ((px shr 8) and 0xFF) / 255f
            img[2 * N * N + i] = (px and 0xFF) / 255f
            // Nearest for the mask, grown by one model pixel so the fill reaches past anti-aliased edges.
            var hole = false
            for (dy in -1..1) for (dx in -1..1) {
                val mx = left + ((x + dx + 0.5f) * w / N).toInt()
                val my = top + ((y + dy + 0.5f) * h / N).toInt()
                if (mask[mx, my]) hole = true
            }
            msk[i] = if (hole) 1f else 0f
        }
        val shape = longArrayOf(1, 3, N.toLong(), N.toLong())
        val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(img), shape).use { image ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(msk), longArrayOf(1, 1, N.toLong(), N.toLong())).use { m ->
                s.run(mapOf("image" to image, "mask" to m)).use { r ->
                    val buf = (r[0] as OnnxTensor).floatBuffer
                    FloatArray(buf.remaining()).also { buf.get(it) }
                }
            }
        }
        // The output is 0..255 per channel; written back only where the mask is.
        for (y in top until top + h) for (x in left until left + w) {
            if (!mask[x, y]) continue
            val fx = (x - left + 0.5f) * N / w - 0.5f
            val fy = (y - top + 0.5f) * N / h - 0.5f
            val r = sample(out, 0, fx, fy)
            val g = sample(out, 1, fx, fy)
            val bl = sample(out, 2, fx, fy)
            crop.set(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or bl)
        }
        return true
    }

    private fun sample(out: FloatArray, c: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, N - 1); val y0 = fy.toInt().coerceIn(0, N - 1)
        val x1 = min(x0 + 1, N - 1); val y1 = min(y0 + 1, N - 1)
        val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
        val o = c * N * N
        val v = (out[o + y0 * N + x0] * (1 - ax) + out[o + y0 * N + x1] * ax) * (1 - ay) +
            (out[o + y1 * N + x0] * (1 - ax) + out[o + y1 * N + x1] * ax) * ay
        return v.roundToInt().coerceIn(0, 255)
    }

    private fun bilinear(r: Raster, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, r.width - 1); val y0 = fy.toInt().coerceIn(0, r.height - 1)
        val x1 = min(x0 + 1, r.width - 1); val y1 = min(y0 + 1, r.height - 1)
        val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            fun ch(p: Int) = (p shr shift) and 0xFF
            val v = (ch(r[x0, y0]) * (1 - ax) + ch(r[x1, y0]) * ax) * (1 - ay) + (ch(r[x0, y1]) * (1 - ax) + ch(r[x1, y1]) * ax) * ay
            out = out or (v.roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /** The mask's bounding box (left, top, right, bottom), or null when it is empty. */
    private fun bounds(m: GlyphMask): IntArray? {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }

    private fun sessionOrNull(): OrtSession? {
        session?.let { return it }
        val f = store.file
        if (!f.isFile) return null
        val s = OrtEnvironment.getEnvironment().createSession(f.absolutePath, OrtCpu.options(xnnpack = false))
        session = s
        return s
    }

    /** Drops the session (~0.5 GB with its buffers) unless a fill is running. */
    fun releaseIfIdle() {
        if (lock.tryLock()) try { session?.close(); session = null } finally { lock.unlock() }
    }

    private companion object {
        const val N = 512
        const val TAG = "LamaInpainter"
    }
}
