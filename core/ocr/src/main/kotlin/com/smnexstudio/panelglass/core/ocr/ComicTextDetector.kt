package com.smnexstudio.panelglass.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextLine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-tier page detection: a comic text-and-bubble detector finds the containers, a crop
 * recogniser reads each one, and a full-page ML Kit pass catches what the detector missed.
 *
 * The detector is `ogkalu/comic-text-and-bubble-detector` (RT-DETR-v2, INT8 ONNX, Apache-2.0, 11 MB,
 * bundled): input `images` 1×3×640×640 in 0..1 RGB, `orig_target_sizes` [w, h]; outputs `labels`
 * (0 bubble, 1 text_bubble, 2 text_free), `boxes` xyxy in original pixels, `scores`. No anchors, no NMS —
 * near-duplicates are collapsed by IoU. If the model cannot be loaded the page falls back to ML Kit alone.
 *
 * Recognition targets are the text boxes; a balloon with no text box inside is read whole. Japanese
 * crops go to manga-ocr when it is installed, ML Kit otherwise.
 */
@Singleton
class ComicTextDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mlKit: MlKitTextDetector,
    private val mlKitCrops: MlKitCropRecognizer,
    private val mangaOcr: MangaOcrRecognizer,
) : TextDetector {

    private val mutex = Mutex()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var initialized = false

    private fun ensureSession() {
        if (initialized) return
        initialized = true
        runCatching {
            val e = OrtEnvironment.getEnvironment().also { env = it }
            val model = extractedModel() ?: return
            session = e.createSession(model.absolutePath, OrtCpu.options(xnnpack = false))
        }.onFailure { android.util.Log.w(TAG, "Detector unavailable: ${it::class.simpleName}") }
    }

    /** ORT opens models by path, so the asset is copied once into app storage and pinned by size. */
    private fun extractedModel(): File? {
        val target = File(File(context.filesDir, "models"), MODEL_ASSET)
        if (target.isFile && target.length() == MODEL_BYTES) return target
        return runCatching {
            target.parentFile?.mkdirs()
            context.assets.open(MODEL_ASSET).use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            target.takeIf { it.length() == MODEL_BYTES }
        }.getOrNull()
    }

    override suspend fun warmUp(lang: Lang) = warmUp(lang, recognizers = true)

    override suspend fun warmUp(lang: Lang, recognizers: Boolean) {
        withContext(Dispatchers.Default) {
            // Debug: `files/debug-ortbench` present -> time the ONNX models per session-option variant (OrtBench).
            if (File(context.filesDir, "debug-ortbench").exists()) {
                val m = File(File(context.filesDir, "models"), "manga-ocr")
                runCatching { extractedModel()?.let { OrtBench.run(it, File(m, "encoder_model.onnx").takeIf { f -> f.isFile }, File(m, "decoder_model.onnx").takeIf { f -> f.isFile }) } }
            }
            mutex.withLock { ensureSession() }
            if (!recognizers) return@withContext
            mlKit.warmUp(lang)
            if (lang == Lang.JA) mangaOcr.warmUp()
        }
    }

    override suspend fun detect(bitmap: Bitmap, lang: Lang): List<TextLine> = detectPage(bitmap, lang).lines

    override suspend fun detectPage(bitmap: Bitmap, lang: Lang): PageDetection = detectPage(bitmap, lang) { false }

    override suspend fun detectPage(bitmap: Bitmap, lang: Lang, skipRead: (TextBox) -> Boolean): PageDetection =
        detectPage(bitmap, lang, skipRead, read = true)

    override suspend fun detectPage(bitmap: Bitmap, lang: Lang, skipRead: (TextBox) -> Boolean, read: Boolean): PageDetection = withContext(Dispatchers.Default) {
        val boxes = mutex.withLock {
            ensureSession()
            val s = session; val e = env
            if (s == null || e == null) emptyList()
            else runCatching { infer(bitmap, s, e) }.getOrElse { android.util.Log.w(TAG, "Detector failed: ${it::class.simpleName}"); emptyList() }
        }
        if (boxes.isEmpty()) return@withContext PageDetection(mlKit.detect(bitmap, lang))

        if (!read) {
            // The engine reads the crops: one placeholder line per target, and no full-page backstop (text the detector
            // missed stays untranslated; the engine only sees what it is sent).
            val targets = recognitionTargets(boxes).filterNot(skipRead)
            val lines = targets.map { t ->
                TextLine(bbox = t.bbox, text = TextLine.UNREAD, vertical = lang.isCjk && lang != Lang.KO && t.bbox.height > t.bbox.width)
            }
            android.util.Log.i(TAG, "boxes=${boxes.size} targets=${targets.size} unread (engine reads the crops)")
            return@withContext PageDetection(lines, boxes)
        }

        val lines = ArrayList<TextLine>()
        val recognizer: CropRecognizer = if (lang == Lang.JA && mangaOcr.isAvailable) mangaOcr else mlKitCrops
        // The full-page pass does not depend on the crops: it runs while they are read (manga-ocr is on ORT, so the
        // two genuinely overlap; with ML Kit crops it merely queues on the same recognizer).
        val backstop = async { mlKit.detect(bitmap, lang) }
        val ocrStart = System.currentTimeMillis()
        // Targets are chosen from every box first (a balloon whose text box is skipped must not become a "lone"
        // balloon and be read whole), then the ones the caller will discard are left unread.
        val targets = recognitionTargets(boxes).filterNot(skipRead)
        for (target in targets) {
            val box = target.bbox
            val pad = (maxOf(box.width, box.height) * CROP_PAD).toInt().coerceAtLeast(2)
            val r = box.inflate(pad).clamp(bitmap.width, bitmap.height)
            if (r.width < 4 || r.height < 4) continue
            val crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width, r.height)
            try {
                for (l in recognizer.recognize(crop, lang)) {
                    // Back to page space, trimmed to the unpadded box: a line that spans the whole crop (manga-ocr
                    // reads the crop as one unit) must not carry the padding into the erase rectangle.
                    val inPage = l.bbox.offset(r.left, r.top).clamp(bitmap.width, bitmap.height)
                    lines += l.copy(bbox = inPage.intersect(box)?.takeIf { !it.isEmpty } ?: inPage)
                }
            } finally { crop.recycle() }
        }

        val fromCrops = lines.size
        val ocrMs = System.currentTimeMillis() - ocrStart
        // Recall backstop: anything ML Kit sees on the full page outside every container.
        for (line in backstop.await()) {
            val cx = line.bbox.centerX.toInt(); val cy = line.bbox.centerY.toInt()
            val covered = boxes.any { it.bbox.contains(cx, cy) || it.bbox.overlapFraction(line.bbox) >= BACKSTOP_OVERLAP }
            if (!covered) lines += line
        }
        android.util.Log.i(
            TAG, "boxes=${boxes.size} (bubble=${boxes.count { it.kind == BoxKind.BUBBLE }} text=${boxes.count { it.kind == BoxKind.TEXT_BUBBLE }} " +
                "free=${boxes.count { it.kind == BoxKind.TEXT_FREE }}) read=${targets.size} lines=$fromCrops via ${if (recognizer === mangaOcr) "manga-ocr" else "mlkit"} " +
                "in ${ocrMs}ms +${lines.size - fromCrops} backstop",
        )
        PageDetection(lines, boxes)
    }

    /** Text boxes, plus any balloon that has no text box of its own. */
    private fun recognitionTargets(boxes: List<TextBox>): List<TextBox> {
        val text = boxes.filter { it.kind != BoxKind.BUBBLE }
        val loneBubbles = boxes.filter { b ->
            b.kind == BoxKind.BUBBLE && text.none { t -> b.bbox.contains(t.bbox.centerX.toInt(), t.bbox.centerY.toInt()) }
        }
        return text + loneBubbles
    }

    private fun infer(bitmap: Bitmap, session: OrtSession, env: OrtEnvironment): List<TextBox> {
        val w = bitmap.width; val h = bitmap.height
        if (w <= 0 || h <= 0) return emptyList()
        // The model takes a plain 640×640 squash; orig_target_sizes maps its boxes back to source pixels.
        val squashed = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888).also { out ->
            Canvas(out).drawBitmap(bitmap, null, Rect(0, 0, INPUT, INPUT), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(INPUT * INPUT)
        squashed.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        squashed.recycle()
        val plane = INPUT * INPUT
        val input = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (i in 0 until plane) {
            val c = pixels[i]
            input.put(i, ((c shr 16) and 0xFF) / 255f)
            input.put(plane + i, ((c shr 8) and 0xFF) / 255f)
            input.put(2 * plane + i, (c and 0xFF) / 255f)
        }
        val sizes = LongBuffer.wrap(longArrayOf(w.toLong(), h.toLong()))
        val images = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()))
        val target = OnnxTensor.createTensor(env, sizes, longArrayOf(1, 2))
        val out = ArrayList<TextBox>()
        try {
            session.run(mapOf(IMAGES to images, SIZES to target)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val labels = (result.get(LABELS).get().value as Array<LongArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val boxes = (result.get(BOXES).get().value as Array<Array<FloatArray>>)[0]
                @Suppress("UNCHECKED_CAST")
                val scores = (result.get(SCORES).get().value as Array<FloatArray>)[0]
                val n = minOf(labels.size, boxes.size, scores.size)
                for (i in 0 until n) {
                    val score = scores[i]
                    if (!score.isFinite() || score < CONFIDENCE) continue
                    val kind = when (labels[i]) { 0L -> BoxKind.BUBBLE; 1L -> BoxKind.TEXT_BUBBLE; 2L -> BoxKind.TEXT_FREE; else -> continue }
                    val b = boxes[i]
                    val rect = IntRect(b[0].toInt(), b[1].toInt(), b[2].toInt(), b[3].toInt()).clamp(w, h)
                    if (rect.width < MIN_SIDE || rect.height < MIN_SIDE) continue
                    out += TextBox(rect, kind, score)
                }
            }
        } finally { images.close(); target.close() }
        return collapseDuplicates(out)
    }

    /** Same-kind boxes that almost coincide are one detection; the higher score is kept. */
    private fun collapseDuplicates(boxes: List<TextBox>): List<TextBox> {
        val kept = ArrayList<TextBox>()
        for (b in boxes.sortedByDescending { it.confidence }) {
            if (kept.none { k -> k.kind == b.kind && iou(k.bbox, b.bbox) >= DUPLICATE_IOU }) kept += b
        }
        return kept
    }

    private fun iou(a: IntRect, b: IntRect): Float {
        val inter = a.intersect(b)?.area ?: 0
        val union = a.area + b.area - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    override fun close() {
        runCatching { session?.close() }; session = null; initialized = false
        mlKit.close(); mangaOcr.close()
    }

    companion object {
        private const val TAG = "ComicTextDetector"
        const val MODEL_ASSET = "detector-v4-s_int8.onnx"
        const val MODEL_BYTES = 11_120_765L
        private const val INPUT = 640
        private const val IMAGES = "images"
        private const val SIZES = "orig_target_sizes"
        private const val LABELS = "labels"
        private const val BOXES = "boxes"
        private const val SCORES = "scores"
        private const val CONFIDENCE = 0.30f
        private const val DUPLICATE_IOU = 0.90f
        private const val MIN_SIDE = 4
        private const val CROP_PAD = 0.06f
        private const val BACKSTOP_OVERLAP = 0.35f
    }
}
