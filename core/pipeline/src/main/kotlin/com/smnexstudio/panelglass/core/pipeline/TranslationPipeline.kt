package com.smnexstudio.panelglass.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.data.cache.CachedPage
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.engine.BatchingTranslator
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.TranslateJob
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ImageSource
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.PageResult
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SkipReason
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.BoxKind
import com.smnexstudio.panelglass.core.ocr.PageDetection
import com.smnexstudio.panelglass.core.ocr.PanelCutter
import com.smnexstudio.panelglass.core.ocr.RegionBuilder
import com.smnexstudio.panelglass.core.ocr.RegionClassifier
import com.smnexstudio.panelglass.core.ocr.RegionClusterer
import com.smnexstudio.panelglass.core.ocr.TextBox
import com.smnexstudio.panelglass.core.ocr.TextDetector
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.render.RegionRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

enum class Stage { CACHE, DECODE, PREPASS, DETECT, TRANSLATE, RENDER, DONE }

/**
 * Where comic pixels are inside a bitmap that is not itself a comic page (a viewport snapshot): detections outside
 * every [include] rect, or under any [exclude] rect (page controls floating above the art), are not translated.
 * An empty [include] means no restriction. [done] are areas already translated by an earlier, overlapping capture: a
 * detection lying (almost) entirely inside one is not read or translated again.
 */
class RegionOfInterest(
    val include: List<IntRect> = emptyList(),
    val exclude: List<IntRect> = emptyList(),
    val done: List<IntRect> = emptyList(),
) {
    fun allows(r: IntRect): Boolean {
        val cx = r.centerX.toInt(); val cy = r.centerY.toInt()
        if (exclude.any { it.contains(cx, cy) }) return false
        if (r.area > 0 && done.any { d -> (d.intersect(r)?.area ?: 0) >= r.area * DONE_COVERAGE }) return false
        return include.isEmpty() || include.any { it.contains(cx, cy) }
    }

    private companion object { const val DONE_COVERAGE = 0.8f }
}

/**
 * Per-stage concurrency. The stages bottleneck on different resources, so a single global
 * semaphore would be wrong: while image 3 waits on the API, images 4 and 5 should be detecting.
 */
@Singleton
class StageGates @Inject constructor() {
    val decode = Semaphore(2)
    val detect = Semaphore(2)
    private val translateCloud = Semaphore(6)
    /** The on-device runtime is single-session: one image in the translate stage, so nothing queues on its lock under a watchdog. */
    private val translateLocal = Semaphore(1)
    val render = Semaphore(2)

    fun translate(engineId: EngineId): Semaphore = if (engineId.isLocalLlm) translateLocal else translateCloud
}

/**
 * The one translation entry point: the reader's screen translation calls this; there is no
 * second code path. Returns patches, never a whole image, and emits each patch the moment its
 * region finishes so the first bubble appears while the rest are still working.
 */
@Singleton
class TranslationPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val detector: TextDetector,
    private val renderer: RegionRenderer,
    private val translator: BatchingTranslator,
    private val patchCache: PatchCache,
    private val glossary: GlossaryRepository,
    private val registry: EngineRegistry,
    private val gates: StageGates,
) {
    private val regionBuilder = RegionBuilder(RegionClusterer())
    private val classifier = RegionClassifier()
    private val panelCutter = PanelCutter()

    suspend fun translate(
        source: ImageSource,
        cfg: TranslateConfig,
        imageId: String = "",
        priorContext: List<ContextPair> = emptyList(),
        onStage: (Stage) -> Unit = {},
        roi: RegionOfInterest? = null,
        bypassCache: Boolean = false,
        onPatch: (Patch) -> Unit,
    ): Result<PageResult> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        try {
            Result.success(run(source, cfg, imageId, priorContext, onStage, onPatch, started, roi, bypassCache))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: EngineException) {
            // Failure type and our own reason string only; never page text.
            android.util.Log.w(TAG, "Image failed: ${e.failure::class.simpleName} ${(e.failure as? EngineFailure.Unavailable)?.reason.orEmpty()}")
            Result.failure(e)
        } catch (e: OutOfMemoryError) {
            android.util.Log.w(TAG, "Image failed: out of memory")
            Result.failure(EngineException(EngineFailure.Unavailable(cfg.engineId, "Out of memory"), e))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Image failed: ${e::class.simpleName}")
            Result.failure(EngineException(EngineFailure.Unavailable(cfg.engineId, e::class.simpleName ?: "error"), e))
        }
    }

    /** Completes when the most recent [warmUp] has finished, so an image's watchdog never counts warm-up time. */
    @Volatile private var warming: CompletableDeferred<Unit>? = null
    @Volatile private var warmingFor: EngineId? = null

    /** Pre-warm the recognizer and the engine (TLS session, or the on-device model's load and first inference). */
    suspend fun warmUp(cfg: TranslateConfig) {
        val done = CompletableDeferred<Unit>()
        warming = done
        warmingFor = cfg.engineId
        try {
            // An engine that reads the crops needs only the container model, not manga-ocr or ML Kit.
            runCatching { detector.warmUp(cfg.src, recognizers = !cfg.engineId.readsCrops) }
            runCatching { registry[cfg.engineId].warmUp(cfg.src, cfg.tgt) }
        } finally {
            done.complete(Unit)
        }
    }

    /** Any caller (the reader's Start, or a one-off screen translation) gets the engine warmed before its clock starts. */
    private suspend fun awaitWarm(cfg: TranslateConfig) {
        if (warmingFor != cfg.engineId) warmUp(cfg) else warming?.join()
    }

    private suspend fun run(
        source: ImageSource, cfg: TranslateConfig, imageId: String, priorContext: List<ContextPair>,
        onStage: (Stage) -> Unit, onPatch: (Patch) -> Unit, started: Long,
        roi: RegionOfInterest? = null, bypassCache: Boolean = false,
    ): PageResult {
        // ---- cache -------------------------------------------------------------------------
        onStage(Stage.CACHE)
        // Always a viewport snapshot: the reader's PixelCopy of the WebView, never a downloaded page.
        val native = (source as? ImageSource.Native)?.handle as? Bitmap ?: throw IllegalArgumentException("Unreadable image source")
        val hash = nativeHash(native)
        val cacheKey = patchCache.key(hash, cfg.src, cfg.tgt, cfg.engineId, PIPELINE_VERSION)
        (if (bypassCache) null else patchCache.get(cacheKey))?.let { hit ->
            hit.patches.forEach(onPatch)
            onStage(Stage.DONE)
            return PageResult(imageId, hit.width, hit.height, hit.regionCount, hit.patches.size, fromCache = true, timingMs = elapsed(started))
        }

        // ---- wrap + energy map ------------------------------------------------------------------
        onStage(Stage.DECODE)
        var page: PageSource? = null
        var energy: Gray? = null
        val crops = HashMap<Int, Bitmap>()
        val dump = PipelineDump.open(context)
        try {
            var width = 0; var height = 0
            gates.decode.withPermit {
                val p = PageSource.of(native)
                page = p
                width = p.width; height = p.height
                if (width < MIN_EDGE || height < MIN_EDGE) {
                    return PageResult(imageId, width, height, 0, 0, false, SkipReason.TOO_SMALL, elapsed(started))
                }
                // Free-text placement drifts toward emptier art: the page's edge energy at 1/8 scale says where that is.
                energy = p.gray8().sobelMagnitude()
            }
            val src = page!!

            // ---- detect + cluster + classify ---------------------------------------------------
            onStage(Stage.DETECT)
            // No watchdog here: detection is local CPU work that cannot hang, only run slowly under load. Nothing is cached
            // at this stage: a viewport's pixels and its region of interest change with every scroll.
            val deferred = ArrayList<IntRect>()
            // An engine that reads the crops gets boxes with unread text instead of OCR.
            val read = !cfg.engineId.readsCrops
            val detected = gates.detect.withPermit {
                coroutineContext.ensureActive()
                // A viewport is a window onto a page: containers cut by its edge are partial and are dropped.
                detectAll(src, cfg.src, dropEdgeCut = true, roi = roi, deferred = deferred, read = read)
            }
            val regions = detected.filter { it.text.isNotBlank() && it.bbox.area >= 16 }.toMutableList()
            val detectedAt = elapsed(started)
            dump?.page(native)
            if (regions.isEmpty()) {
                return PageResult(imageId, width, height, 0, 0, false, SkipReason.NO_REGIONS, elapsed(started), deferred = deferred)
            }
            for ((i, r) in regions.withIndex()) {
                if (r.unread) crops[i] = readCrop(src, r.bbox)
                else if (r.kind == RegionKind.SFX) crops[i] = sfxCrop(src, r.bbox)
            }

            // ---- translate + render --------------------------------------------------------------
            // One engine call per image, except the on-device model on a viewport: there a screen of bubbles is one
            // long generation, so it runs in reading-order groups and each group is drawn the moment it returns. The
            // first bubbles appear after a fraction of the wait, each group has its own watchdog, and each carries the
            // previous group's last lines as continuity context.
            val groups = if (cfg.engineId.isLocalLlm && regions.size > LOCAL_GROUP)
                regions.indices.chunked(LOCAL_GROUP) else listOf(regions.indices.toList())
            val seriesGlossary = if (cfg.seriesKey.isNotEmpty()) glossary.forSeries(cfg.seriesKey) else emptyMap()
            val jobCfg = cfg.copy(glossary = cfg.glossary + seriesGlossary)
            val translated = arrayOfNulls<String>(regions.size)
            val patches = ArrayList<Patch>()
            var context = priorContext
            var translateStart = -1L; var translateMs = 0L; var renderMs = 0L
            for (group in groups) {
                val groupCrops = group.withIndex().mapNotNull { (k, idx) -> crops[idx]?.let { k to it } }.toMap()
                val job = TranslateJob(imageId, jobCfg, group.map { regions[it] }, groupCrops, context)
                // The stage flips, and the clock starts, only once this image holds a permit and the warm-up is over:
                // an image still queued has spent nothing, so Stop and engine changes may still cancel it.
                var groupStart = 0L
                val out = gates.translate(cfg.engineId).withPermit {
                    awaitWarm(cfg)
                    groupStart = elapsed(started)
                    if (translateStart < 0) translateStart = groupStart
                    onStage(Stage.TRANSLATE)
                    watchdog(Stage.TRANSLATE, cfg.engineId) { translator.translateReading(job) }
                }
                translateMs += elapsed(started) - groupStart
                group.forEachIndexed { k, idx ->
                    translated[idx] = out[k]?.text?.takeIf { it.isNotBlank() }
                    // What the engine read replaces the placeholder: continuity context, the dump, and the glyph size.
                    if (regions[idx].unread) regions[idx] = withReading(regions[idx], out[k]?.source, cfg.src)
                }

                // ---- render + encode: the engine call is spent, so this group is finished even if cancelled --
                val renderStart = elapsed(started)
                withContext(NonCancellable) {
                    onStage(Stage.RENDER)
                    gates.render.withPermit {
                        val canvas = src
                        for (i in group) {
                            val region = regions[i]
                            val text = translated[i]
                            dump?.region(i, region, null, text)
                            if (text == null) continue
                            val avoid = regions.filterIndexed { j, _ -> j != i }.flatMap { listOfNotNull(it.bbox, it.container) }
                            val patch = renderer.render(canvas, region, text, cfg, energy, avoid, debug = dump?.let { d -> { s: String -> d.note(i, s) } }) ?: continue
                            dump?.patch(i, patch)
                            patches += patch
                            onPatch(patch)
                        }
                    }
                }
                renderMs += elapsed(started) - renderStart
                context = (context + group.mapNotNull { idx -> translated[idx]?.takeIf { it.isNotBlank() }?.let { ContextPair(regions[idx].text, it) } }).takeLast(2)
            }

            return withContext(NonCancellable) {
                patchCache.put(cacheKey, CachedPage(width, height, regions.size, patches))
                onStage(Stage.DONE)
                // Durations only (no text, no URLs): where a slow page spent its time.
                android.util.Log.i(
                    TAG, "timing ${cfg.engineId.name}: decode+detect+ocr=${detectedAt}ms wait=${translateStart - detectedAt}ms " +
                        "translate=${translateMs}ms render=${renderMs}ms total=${elapsed(started)}ms regions=${regions.size} groups=${groups.size}",
                )
                val contextPairs = regions.mapIndexedNotNull { idx, region ->
                    val text = translated[idx]
                    if (!text.isNullOrBlank()) ContextPair(region.text, text) else null
                }
                PageResult(imageId, width, height, regions.size, patches.size, false, timingMs = elapsed(started), contextPairs = contextPairs, deferred = deferred)
            }
        } finally {
            dump?.close()
            crops.values.forEach { it.recycle() }
        }
    }

    /**
     * Detection on the snapshot scaled to ≤1600 px, mapped back to its pixels, then clustered and classified against
     * the full-resolution pixels.
     */
    private suspend fun detectAll(
        src: PageSource, lang: Lang, dropEdgeCut: Boolean = false, roi: RegionOfInterest? = null,
        deferred: MutableList<IntRect>? = null, read: Boolean = true,
    ): List<TextRegion> {
        val lines = ArrayList<TextLine>()
        val boxes = ArrayList<TextBox>()
        // The whole snapshot, scaled for the detector; its boxes map back by 1/scale.
        coroutineContext.ensureActive()
        val (small, scale) = ImageDecoder.forDetection(src.bitmap)
        val inv = 1f / scale
        // Boxes the filters below will drop anyway are not OCR'd: outside the page's images, under its controls,
        // already translated, or a text box cut by a scroll edge (the same tests, in page coordinates).
        val skipRead: (TextBox) -> Boolean = { b ->
            val p = b.bbox.scale(inv).clamp(src.width, src.height)
            (roi != null && !roi.allows(p)) ||
                (dropEdgeCut && b.kind != BoxKind.BUBBLE && (p.top <= EDGE_PX || p.bottom >= src.height - EDGE_PX))
        }
        val detection = try {
            when {
                !read -> detector.detectPage(small, lang, skipRead, read = false)
                roi != null || dropEdgeCut -> detector.detectPage(small, lang, skipRead)
                else -> detector.detectPage(small, lang)
            }
        } finally { if (small !== src.bitmap) small.recycle() }
        for (l in detection.lines) lines += l.copy(bbox = l.bbox.scale(inv).clamp(src.width, src.height))
        for (b in detection.boxes) boxes += b.copy(bbox = b.bbox.scale(inv).clamp(src.width, src.height))
        // One region per detector box (a bubble is one sentence), proximity clustering for the rest.
        val found = boxes.size
        if (roi != null) {
            boxes.removeAll { !roi.allows(it.bbox) }
            lines.removeAll { !roi.allows(it.bbox) }
        }
        if (dropEdgeCut) {
            // Only the scroll edges cut content: the page fills the width, so a box at the left/right edge is whole.
            // A cut *text* box is deferred; a cut balloon whose text box is whole is merely a smaller layout area
            // (joined balloons often reach the edge while their lower bubble is entirely on screen).
            val cut = boxes.filter { it.bbox.top <= EDGE_PX || it.bbox.bottom >= src.height - EDGE_PX }
            boxes.removeAll(cut.toSet())
            lines.removeAll { l -> cut.any { it.kind != BoxKind.BUBBLE && it.bbox.contains(l.bbox.centerX.toInt(), l.bbox.centerY.toInt()) } }
            deferred?.addAll(cut.map { it.bbox })
            // A loose line (outside every remaining text box) within two glyph heights of a scroll edge belongs to a
            // block the detector could not see whole; it will be read when the block is fully on screen.
            lines.removeAll { l ->
                val glyph = minOf(l.bbox.width, l.bbox.height)
                val boxed = boxes.any { it.kind != BoxKind.BUBBLE && it.bbox.contains(l.bbox.centerX.toInt(), l.bbox.centerY.toInt()) }
                !boxed && (l.bbox.top <= EDGE_PX + 2 * glyph || l.bbox.bottom >= src.height - EDGE_PX - 2 * glyph)
            }
        }
        android.util.Log.d(TAG, "detect: boxes=$found kept=${boxes.size} lines=${lines.size} deferred=${deferred?.size ?: 0}")
        val built = regionBuilder.build(PageDetection(lines, boxes), lang)
        val seeds = java.util.IdentityHashMap<TextRegion, com.smnexstudio.panelglass.core.ocr.BoxKind?>()
        for (b in built) seeds[b.region] = b.seed
        // A region with no letters is a page number or bare punctuation ("139", "……", "!?"): nothing to translate, and
        // painting over it only hides the original.
        val regions = built.map { it.region }.filter { it.unread || it.text.any(Char::isLetter) }
        val classified = regions.map { RegionBuilder.seeded(classifier.classify(src, it), seeds[it]) }
        val panels = panelCutter.segment(src, lang.rtlReading)
        return panelCutter.orderRegions(classified, panels, lang.rtlReading)
    }

    /** 10%-padded crop of an SFX region, shrunk to ~160 px for the montage. */
    private fun sfxCrop(src: PageSource, bbox: IntRect): Bitmap {
        val pad = (maxOf(bbox.width, bbox.height) * 0.1f).toInt().coerceAtLeast(2)
        val r = bbox.inflate(pad).clamp(src.width, src.height)
        val raster = src.crop(r)
        val crop = Bitmap.createBitmap(raster.argb, raster.width.coerceAtLeast(1), raster.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val longest = maxOf(crop.width, crop.height)
        if (longest <= 160) return crop
        val s = 160f / longest
        val scaled = Bitmap.createScaledBitmap(crop, (crop.width * s).toInt().coerceAtLeast(1), (crop.height * s).toInt().coerceAtLeast(1), true)
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    /** 6%-padded full-resolution crop of a region the engine reads itself (the engine layer scales it for sending). */
    private fun readCrop(src: PageSource, bbox: IntRect): Bitmap {
        val pad = (maxOf(bbox.width, bbox.height) * 0.06f).toInt().coerceAtLeast(2)
        val r = bbox.inflate(pad).clamp(src.width, src.height)
        val raster = src.crop(r)
        return Bitmap.createBitmap(raster.argb, raster.width.coerceAtLeast(1), raster.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }

    private fun elapsed(started: Long) = System.currentTimeMillis() - started

    /**
     * Per-stage hard cap. Wrapping the whole image instead would count time spent waiting for a
     * permit or for the single-session on-device runtime, and one slow call would then fail every image
     * queued behind it. A timeout surfaces as a typed failure for this image only; the stage's work is
     * cancelled, which the engines honour.
     */
    private suspend fun <T> watchdog(stage: Stage, engineId: EngineId, block: suspend () -> T): T =
        try { withTimeout(STAGE_TIMEOUT_MS) { block() } }
        catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "Watchdog: ${stage.name.lowercase()} exceeded ${STAGE_TIMEOUT_MS} ms")
            throw EngineException(EngineFailure.Unavailable(engineId, "Watchdog: ${stage.name.lowercase()} ${STAGE_TIMEOUT_MS / 1000}s"), e)
        }

    companion object {
        /**
         * An unread region once the engine has read it: the text it read, and a glyph size from area per character, as
         * manga-ocr gives for a whole-bubble reading (a Latin letter is about half as wide as it is tall).
         */
        internal fun withReading(region: TextRegion, source: String?, lang: Lang): TextRegion {
            val text = source?.trim().orEmpty()
            if (text.isEmpty()) return region.copy(text = "", lines = region.lines.map { it.copy(text = "") })
            val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
            val b = region.bbox
            val perChar = if (lang.isCjk) 1f else 0.6f
            val glyph = kotlin.math.sqrt(b.width.toFloat() * b.height / (chars * perChar)).coerceIn(8f, maxOf(8f, minOf(b.width, b.height).toFloat()))
            return region.copy(text = text, lines = region.lines.map { it.copy(text = text, fontSizePx = glyph) })
        }

        /** Bump whenever rendering changes so stale patches are not served from the disk cache. */
        const val PIPELINE_VERSION = 8
        const val MIN_EDGE = 96
        /** Bubbles per on-device generation on a viewport: small enough that the first patches come early. */
        const val LOCAL_GROUP = 4
        private const val TAG = "TranslationPipeline"
        const val STAGE_TIMEOUT_MS = 50_000L
        /** A detector box this close to a viewport edge is assumed cut off. */
        private const val EDGE_PX = 2


        /** Bitmaps given directly have no byte stream; hash dimensions plus a sparse pixel sample. */
        fun nativeHash(bmp: Bitmap): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(bmp.width.toString().toByteArray()); md.update(bmp.height.toString().toByteArray())
            val step = maxOf(1, minOf(bmp.width, bmp.height) / 64)
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) { val p = bmp.getPixel(x, y); md.update(byteArrayOf((p shr 16).toByte(), (p shr 8).toByte(), p.toByte())); x += step }
                y += step
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
