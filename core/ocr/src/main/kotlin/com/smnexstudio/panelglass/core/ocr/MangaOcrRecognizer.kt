package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * manga-ocr (kha-white's ViT encoder + BERT-style decoder, the 2025 ViT-Small ONNX export) on one bubble crop.
 * Trained on Manga109 bubble crops squashed to 224×224, so the crop is fed whole — no line splitting, no
 * deskew. Output is one string per crop; it is returned as a single [TextLine] over the crop, flagged
 * vertical when the crop is taller than wide, which is what the clusterer and renderer need.
 *
 * Pipeline: grayscale→RGB, (x/255−0.5)/0.5, `pixel_values` → `last_hidden_state`; then greedy decoding from
 * `[CLS]` until `[SEP]` or [MAX_TOKENS], re-running the decoder on the growing `input_ids` each step (the
 * export has no KV cache; at ≤48 tokens that is still tens of ms per step on a phone).
 */
@Singleton
class MangaOcrRecognizer @Inject constructor(private val store: MangaOcrStore) : CropRecognizer {
    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var vocab: List<String> = emptyList()
    private var loadedFrom: Long = 0

    val isAvailable: Boolean get() = store.isReady

    /**
     * Load the sessions and run one short read on a blank crop: the first real crop otherwise pays for paging in the
     * weights and ORT's first-run allocations (2–3× a normal crop).
     */
    suspend fun warmUp() {
        if (!isAvailable) return
        withContext(Dispatchers.Default) {
            mutex.withLock {
                runCatching {
                    ensureLoaded()
                    val blank = Bitmap.createBitmap(IMG, IMG, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                    try { read(preprocess(blank), WARM_STEPS) } finally { blank.recycle() }
                }
            }
        }
    }

    private fun ensureLoaded() {
        val f = store.files
        val stamp = f.encoder.lastModified() + f.decoder.lastModified()
        if (encoder != null && decoder != null && stamp == loadedFrom) return
        close()
        val e = OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtCpu.options(xnnpack = false)
        encoder = e.createSession(f.encoder.absolutePath, opts)
        decoder = e.createSession(f.decoder.absolutePath, opts)
        vocab = f.vocab.readLines()
        require(vocab.size > MASK_ID && vocab[CLS_ID] == "[CLS]" && vocab[SEP_ID] == "[SEP]") { "unexpected vocab" }
        loadedFrom = stamp
    }

    override suspend fun recognize(crop: Bitmap, lang: Lang): List<TextLine> = withContext(Dispatchers.Default) {
        if (!isAvailable || crop.width < 2 || crop.height < 2) return@withContext emptyList()
        val text = mutex.withLock {
            ensureLoaded()
            read(preprocess(crop), MAX_TOKENS)
        }
        if (text.isBlank()) return@withContext emptyList()
        // One reading for the whole box: glyph size follows from area per character (columns × rows ≈ chars).
        val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val glyph = kotlin.math.sqrt(crop.width.toFloat() * crop.height / chars).coerceIn(8f, minOf(crop.width, crop.height).toFloat())
        listOf(TextLine(bbox = IntRect(0, 0, crop.width, crop.height), text = text, vertical = crop.height > crop.width, confidence = 1f, fontSizePx = glyph))
    }

    /** Encoder once, then greedy decoding for at most [maxSteps] tokens. Caller holds [mutex] with the sessions loaded. */
    private suspend fun read(pixels: java.nio.FloatBuffer, maxSteps: Int): String {
        val e = env!!; val enc = encoder!!; val dec = decoder!!
        val ids = IntArray(maxSteps + 1); ids[0] = CLS_ID; var len = 1
        OnnxTensor.createTensor(e, pixels, longArrayOf(1, 3, IMG.toLong(), IMG.toLong())).use { pv ->
            enc.run(mapOf("pixel_values" to pv)).use { encOut ->
                // The encoder's output tensor goes straight back in as the decoder's input: no copy through the JVM heap.
                val hs = encOut.get(0) as OnnxTensor
                while (len <= maxSteps) {
                    coroutineContext.ensureActive()
                    val next = step(e, dec, hs, ids, len)
                    if (next == SEP_ID) break
                    ids[len++] = next
                }
            }
        }
        return collapseRepeats(decode(ids, 1, len), truncated = len > maxSteps)
    }

    /**
     * One decoder call over the whole prefix; the argmax of the last position is the next token. Only that row is
     * read, straight from the native buffer: materialising the full `[1, len, vocab]` logits as nested Java arrays
     * every step made decoding quadratic in copies.
     */
    private fun step(e: OrtEnvironment, dec: OrtSession, hidden: OnnxTensor, ids: IntArray, len: Int): Int {
        val buf = LongBuffer.allocate(len)
        for (i in 0 until len) buf.put(ids[i].toLong())
        buf.rewind()
        OnnxTensor.createTensor(e, buf, longArrayOf(1, len.toLong())).use { inputIds ->
            dec.run(mapOf("input_ids" to inputIds, "encoder_hidden_states" to hidden)).use { out ->
                val logits = out.get(0) as OnnxTensor
                val shape = logits.info.shape
                val vocabSize = shape[shape.size - 1].toInt()
                val rows = (shape[shape.size - 2]).toInt()
                val fb = logits.floatBuffer
                val base = (rows - 1) * vocabSize
                var best = 0; var bestV = Float.NEGATIVE_INFINITY
                for (i in 0 until vocabSize) { val v = fb.get(base + i); if (v > bestV) { bestV = v; best = i } }
                return best
            }
        }
    }

    /** Squash to 224², drop colour (the model was trained on grayscale) and normalise to −1..1, NCHW. */
    private fun preprocess(crop: Bitmap): java.nio.FloatBuffer {
        val bmp = Bitmap.createBitmap(IMG, IMG, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(bmp).drawBitmap(crop, null, Rect(0, 0, IMG, IMG), paint)
        val px = IntArray(IMG * IMG)
        bmp.getPixels(px, 0, IMG, 0, 0, IMG, IMG)
        bmp.recycle()
        val plane = IMG * IMG
        val out = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plane) {
            val c = px[i]
            out.put(i, (((c shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
            out.put(plane + i, (((c shr 8) and 0xFF) / 255f - 0.5f) / 0.5f)
            out.put(2 * plane + i, ((c and 0xFF) / 255f - 0.5f) / 0.5f)
        }
        return out
    }

    /** Tokens after `[CLS]`, special ids dropped, WordPiece `##` continuations glued on. */
    private fun decode(ids: IntArray, from: Int, to: Int): String = buildString {
        for (i in from until to) {
            val id = ids[i]
            if (id <= MASK_ID || id >= vocab.size) continue
            val tok = vocab[id]
            append(if (tok.startsWith("##")) tok.substring(2) else tok)
        }
    }.trim()

    fun close() {
        runCatching { encoder?.close() }; runCatching { decoder?.close() }
        encoder = null; decoder = null; vocab = emptyList(); loadedFrom = 0
    }

    /** Memory pressure: drop the ~140 MB of sessions unless a recognition is running; they reload on next use. */
    fun releaseIfIdle() { if (mutex.tryLock()) try { close() } finally { mutex.unlock() } }

    companion object {
        const val IMG = 224
        const val MAX_TOKENS = 48
        /** Decoder steps in the warm-up read: enough to exercise the decoder, not to read anything. */
        const val WARM_STEPS = 3
        /** Shortest repeated run [collapseRepeats] removes. */
        const val MIN_REPEAT = 4

        /**
         * Greedy decoding can fall into a loop and re-emit the sentence until [MAX_TOKENS]; a bubble then reads
         * "…受けているよそう構えずとも…" and the translation repeats itself. Trailing repeats of four or more
         * characters are dropped; when the cap cut the output ([truncated]) a final partial repeat goes too. Shorter
         * periods are left alone: "ハハハ" and "そうそう" are real.
         */
        internal fun collapseRepeats(text: String, truncated: Boolean = false): String {
            var t = text
            var changed = true
            while (changed) {
                changed = false
                for (p in MIN_REPEAT..t.length / 2) {
                    if (t.regionMatches(t.length - p, t, t.length - 2 * p, p)) { t = t.dropLast(p); changed = true; break }
                }
            }
            if (!truncated) return t
            // The loop restarts the sentence from its beginning, so a partial trailing period is a prefix of the
            // text itself: "ABCDEF" + "ABC" -> "ABCDEF". The longest such prefix that is at most half the text goes.
            for (k in t.length / 2 downTo MIN_REPEAT) {
                if (t.regionMatches(t.length - k, t, 0, k)) return t.dropLast(k)
            }
            return t
        }

        const val CLS_ID = 2
        const val SEP_ID = 3
        const val MASK_ID = 4
    }
}
