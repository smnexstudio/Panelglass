package com.smnexstudio.panelglass.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.data.cache.CachedPage
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.engine.BatchingTranslator
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.PromptParts
import com.smnexstudio.panelglass.core.engine.TranslateJob
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.DialogueContext
import com.smnexstudio.panelglass.core.model.DialogueLine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ImageSource
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.PageResult
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.ReadState
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.SkipReason
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.TextDetector
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.render.RegionRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    private val detection = DetectionStage(detector)
    /** Patch-cache writes: they encode every patch's WEBP, which nothing on screen waits for. */
    private val cacheWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val series = SeriesResolver(glossary)
    /** The reader's per-group translate cap ([STAGE_TIMEOUT_MS]); tests shorten it. */
    internal var stageTimeoutMs = STAGE_TIMEOUT_MS

    suspend fun translate(
        source: ImageSource,
        cfg: TranslateConfig,
        imageId: String = "",
        priorContext: List<ContextPair> = emptyList(),
        onStage: (Stage) -> Unit = {},
        roi: RegionOfInterest? = null,
        bypassCache: Boolean = false,
        /**
         * The context to use, asked AFTER the regions are known: the top of the first new bubble on the page, in
         * snapshot pixels. The reader answers with the lines above that point.
         */
        contextFor: ((topPx: Int) -> List<ContextPair>)? = null,
        onPatch: (Patch) -> Unit,
    ): Result<PageResult> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        try {
            Result.success(run(source, cfg, imageId, priorContext, onStage, onPatch, started, roi, bypassCache, contextFor))
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
        source: ImageSource, baseCfg: TranslateConfig, imageId: String, priorContext: List<ContextPair>,
        onStage: (Stage) -> Unit, onPatch: (Patch) -> Unit, started: Long,
        roi: RegionOfInterest? = null, bypassCache: Boolean = false,
        contextFor: ((topPx: Int) -> List<ContextPair>)? = null,
    ): PageResult {
        // ---- cache -------------------------------------------------------------------------
        onStage(Stage.CACHE)
        // Always a viewport snapshot: the reader's PixelCopy of the WebView, never a downloaded page.
        val native = (source as? ImageSource.Native)?.handle as? Bitmap ?: throw IllegalArgumentException("Unreadable image source")
        val cfg = series.resolve(baseCfg)
        val hash = nativeHash(native)
        // What else changes the translation goes into the key: the model, the context level, the character sheet.
        // Without it a page translated with one model would be served for another.
        val variant = listOf(
            registry.cacheTag(cfg.engineId),
            if (cfg.dialogueContext != DialogueContext.OFF) "ctx${cfg.contextLimit()}" else "",
            if (cfg.castSheet.isNotBlank()) "cast" + PatchCache.sha256Hex(cfg.castSheet.toByteArray()).take(8) else "",
        ).filter { it.isNotEmpty() }.joinToString("|")
        val cacheKey = patchCache.key(hash, cfg.src, cfg.tgt, cfg.engineId, PIPELINE_VERSION, cfg.fontId, SeriesResolver.digest(cfg), variant)
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
                detection.detectAll(
                    src, cfg.src, dropEdgeCut = true, roi = roi, deferred = deferred, read = read,
                    keepUnreadable = cfg.sfxMode == SfxMode.REPLACE,
                )
            }
            val regions = detected.filter { it.text.isNotBlank() && it.bbox.area >= 16 }.toMutableList()
            val detectedAt = elapsed(started)
            dump?.page(native)
            if (regions.isEmpty()) {
                return PageResult(imageId, width, height, 0, 0, false, SkipReason.NO_REGIONS, elapsed(started), deferred = deferred)
            }
            for ((i, r) in regions.withIndex()) {
                if (r.unread) crops[i] = readCrop(src, r.bbox)
                else if (r.kind == RegionKind.SFX && !r.unreadable) crops[i] = sfxCrop(src, r.bbox)
            }
            // The context is asked for AFTER the regions are known: the lines above the first new bubble on the
            // page are the ones that come before it, whether the reader scrolls up or down.
            val context0 = contextFor?.invoke(regions.minOf { it.bbox.top }) ?: priorContext

            // ---- translate + render --------------------------------------------------------------
            val patches = ArrayList<Patch>()
            var renderMs = 0L
            val outcome = translateGroups(regions, crops, cfg, context0, started, onStage) { group, translated ->
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
                            if (text == null) {
                                // A sound effect nothing could read: with Replace on, the original still goes.
                                if (region.unreadable && cfg.sfxMode == SfxMode.REPLACE) {
                                    val patch = renderer.eraseOnly(canvas, region, cfg, debug = dump?.let { d -> { s: String -> d.note(i, s) } }) ?: continue
                                    dump?.patch(i, patch)
                                    patches += patch
                                    onPatch(patch)
                                }
                                continue
                            }
                            val avoid = regions.filterIndexed { j, _ -> j != i }.flatMap { listOfNotNull(it.bbox, it.container) }
                            val patch = renderer.render(canvas, region, text, cfg, energy, avoid, debug = dump?.let { d -> { s: String -> d.note(i, s) } }) ?: continue
                            dump?.patch(i, patch)
                            patches += patch
                            onPatch(patch)
                        }
                    }
                }
                renderMs += elapsed(started) - renderStart
            }
            val translated = outcome.translated
            val translateStart = outcome.translateStart
            val translateMs = outcome.translateMs
            val groups = outcome.groups

            return withContext(NonCancellable) {
                // Every patch is on screen already; encoding them for the cache happens after this image is done.
                val page = CachedPage(width, height, regions.size, patches.toList())
                cacheWrites.launch { runCatching { patchCache.put(cacheKey, page) } }
                onStage(Stage.DONE)
                // Durations only (no text, no URLs): where a slow page spent its time.
                android.util.Log.i(
                    TAG, "timing ${cfg.engineId.name}: decode+detect+ocr=${detectedAt}ms wait=${translateStart - detectedAt}ms " +
                        "translate=${translateMs}ms render=${renderMs}ms total=${elapsed(started)}ms regions=${regions.size} groups=$groups",
                )
                val contextPairs = regions.mapIndexedNotNull { idx, region ->
                    val text = translated[idx]
                    if (!text.isNullOrBlank()) ContextPair(region.text, text, outcome.speakers[idx]) else null
                }
                // The dialogue of this screen, with where it sits: what the reader's memory is made of. SFX stay
                // out: they would pollute the memory with sound words.
                val lines = regions.mapIndexedNotNull { idx, region ->
                    val text = translated[idx]
                    if (!text.isNullOrBlank() && region.kind != RegionKind.SFX) {
                        DialogueLine(region.bbox, region.text, text, outcome.speakers[idx])
                    } else null
                }
                PageResult(
                    imageId, width, height, regions.size, patches.size, false, timingMs = elapsed(started),
                    contextPairs = contextPairs, deferred = deferred, lines = lines,
                )
            }
        } finally {
            dump?.close()
            crops.values.forEach { it.recycle() }
        }
    }

    /** What [translateGroups] did: one translation per region (null = none) and where the time went. */
    private class GroupsOutcome(val translated: Array<String?>, val translateStart: Long, val translateMs: Long, val groups: Int, val context: List<ContextPair>, val speakers: Array<String?>)

    /**
     * The translate stage both entry points share. One engine call per image, except the on-device model: there a page
     * of bubbles is one long generation, so it runs in reading-order groups of [LOCAL_GROUP], each with its own
     * watchdog and the previous group's last lines as continuity context. [onGroup] runs as each group returns (the
     * reader renders it at once). Unread regions are swapped in [regions] for what the engine read. [cfg] is already
     * resolved by [SeriesResolver.resolve].
     */
    private suspend fun translateGroups(
        regions: MutableList<TextRegion>, crops: Map<Int, Bitmap>, cfg: TranslateConfig, priorContext: List<ContextPair>,
        started: Long, onStage: (Stage) -> Unit, timeoutMs: Long = stageTimeoutMs,
        onGroup: suspend (group: List<Int>, translated: Array<String?>) -> Unit,
    ): GroupsOutcome {
        val groups = if (cfg.engineId.isLocalLlm && regions.size > LOCAL_GROUP)
            regions.indices.chunked(LOCAL_GROUP) else listOf(regions.indices.toList())
        val translated = arrayOfNulls<String>(regions.size)
        val speakers = arrayOfNulls<String>(regions.size)
        var context = priorContext
        var translateStart = -1L; var translateMs = 0L
        for (group in groups) {
            val groupCrops = group.withIndex().mapNotNull { (k, idx) -> crops[idx]?.let { k to it } }.toMap()
            val job = TranslateJob("", cfg, group.map { regions[it] }, groupCrops, context, immediate = true)
            // The stage flips, and the clock starts, only once this image holds a permit and the warm-up is over:
            // an image still queued has spent nothing, so Stop and engine changes may still cancel it.
            var groupStart = 0L
            val out = gates.translate(cfg.engineId).withPermit {
                awaitWarm(cfg)
                groupStart = elapsed(started)
                if (translateStart < 0) translateStart = groupStart
                onStage(Stage.TRANSLATE)
                watchdog(Stage.TRANSLATE, cfg.engineId, timeoutMs) { translator.translateReading(job) }
            }
            translateMs += elapsed(started) - groupStart
            group.forEachIndexed { k, idx ->
                translated[idx] = out[k]?.text?.takeIf { it.isNotBlank() }
                speakers[idx] = out[k]?.speaker
                // What the engine read replaces the placeholder: continuity context, the dump, and the glyph size.
                if (regions[idx].unread) regions[idx] = withReading(regions[idx], out[k]?.source, cfg.src)
            }
            onGroup(group, translated)
            context = (context + group.mapIndexedNotNull { k, idx -> translated[idx]?.takeIf { it.isNotBlank() }?.let { ContextPair(regions[idx].text, it, out[k]?.speaker) } }).takeLast(cfg.contextLimit())
        }
        return GroupsOutcome(translated, translateStart, translateMs, groups.size, context, speakers)
    }

    // ---- the Studio: whole pages, no viewport, no rendering, no patch cache ------------------------------------

    /**
     * Detects and reads a whole Studio page: nothing is dropped for touching an edge (a page is whole) and there is no
     * region of interest. An engine that reads the crops gets unread regions. A page taller than
     * [TileDetection.TALL_ASPECT] widths is detected in overlapping tiles. Regions come in reading order.
     */
    suspend fun detectStudioPage(bitmap: Bitmap, cfg: TranslateConfig): List<TextRegion> = withContext(Dispatchers.Default) {
        val src = PageSource.of(bitmap)
        if (src.width < MIN_EDGE || src.height < MIN_EDGE) return@withContext emptyList()
        val read = !cfg.engineId.readsCrops
        // The detector and recognizers only: reading a chapter should not load a translation model.
        runCatching { detector.warmUp(cfg.src, recognizers = read) }
        gates.detect.withPermit {
            coroutineContext.ensureActive()
            if (TileDetection.isTall(src.width, src.height)) detection.detectTiled(src, cfg.src, read) else detection.detectAll(src, cfg.src, read = read)
        }.filter { it.text.isNotBlank() && it.bbox.area >= 16 }
    }

    /** The Studio's translation of one page's regions (what [detectStudioPage] returned, maybe edited since). */
    class StudioTranslation(val regions: List<TextRegion>, val translations: List<String?>, val contextPairs: List<ContextPair>)

    /**
     * Translates a page's regions with [cfg]'s engine: the same grouped calls, watchdog and gates as the reader, crops
     * cut from [bitmap] for unread regions and sound effects. [priorContext] is the end of the previous page; the
     * result's [StudioTranslation.contextPairs] is this page's end, for the next one. Throws [EngineException] on an
     * engine failure (never switches engines).
     */
    suspend fun translateRegions(
        bitmap: Bitmap, regions: List<TextRegion>, cfg: TranslateConfig, priorContext: List<ContextPair> = emptyList(),
    ): StudioTranslation = withContext(Dispatchers.Default) {
        if (regions.isEmpty()) return@withContext StudioTranslation(emptyList(), emptyList(), priorContext)
        val src = PageSource.of(bitmap)
        val work = regions.toMutableList()
        val crops = HashMap<Int, Bitmap>()
        try {
            for ((i, r) in work.withIndex()) {
                if (r.unread) crops[i] = readCrop(src, r.bbox)
                else if (r.kind == RegionKind.SFX && !r.unreadable) crops[i] = sfxCrop(src, r.bbox)
            }
            val started = System.currentTimeMillis()
            // A Studio run is a batch nobody watches page by page: a slow on-device model (the CPU on a small phone) may
            // take minutes for a group, which is still progress. The reader keeps its tight cap.
            val out = translateGroups(work, crops, series.resolve(cfg), priorContext, started, onStage = {}, timeoutMs = STUDIO_TIMEOUT_MS) { _, _ -> }
            // Durations only (no text).
            android.util.Log.i(TAG, "studio ${cfg.engineId.name}: translate=${out.translateMs}ms regions=${work.size} groups=${out.groups}")
            StudioTranslation(work, out.translated.toList(), out.context)
        } finally {
            crops.values.forEach { it.recycle() }
        }
    }

    /**
     * The text inside [rect] (page pixels) as one region: what the user boxed on a page because detection missed it.
     * The crop is read like a page and everything found joins in reading order; an engine that reads the crops gets an
     * unread region instead. [kind] is what the user drew (a bubble, a sound effect).
     */
    suspend fun readStudioRegion(bitmap: Bitmap, rect: IntRect, cfg: TranslateConfig, kind: RegionKind): TextRegion = withContext(Dispatchers.Default) {
        val r = rect.clamp(bitmap.width, bitmap.height)
        if (cfg.engineId.readsCrops || r.width < 8 || r.height < 8) {
            return@withContext TextRegion.unread(r, kind)
        }
        runCatching { detector.warmUp(cfg.src, recognizers = true) }
        val crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width, r.height)
        val found = try {
            gates.detect.withPermit { detection.detectAll(PageSource.of(crop), cfg.src) }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
        val lines = found.flatMap { it.lines }.map { it.copy(bbox = it.bbox.offset(r.left, r.top)) }
        val text = found.joinToString(if (cfg.src.isCjk) "" else " ") { it.text }.trim()
        TextRegion(
            bbox = r, kind = kind, text = text, lines = lines, container = r,
            vertical = found.any { it.vertical },
            bgColor = found.firstOrNull()?.bgColor ?: 0xFFFFFFFF.toInt(),
            fgColor = found.firstOrNull()?.fgColor ?: 0xFF000000.toInt(),
        )
    }

    /** A whole page read and translated in one go: [detectStudioPage] then [translateRegions]. */
    suspend fun analyze(bitmap: Bitmap, cfg: TranslateConfig, priorContext: List<ContextPair> = emptyList()): StudioTranslation =
        translateRegions(bitmap, detectStudioPage(bitmap, cfg), cfg, priorContext)

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
    private suspend fun <T> watchdog(stage: Stage, engineId: EngineId, timeoutMs: Long = STAGE_TIMEOUT_MS, block: suspend () -> T): T =
        try { withTimeout(timeoutMs) { block() } }
        catch (e: TimeoutCancellationException) {
            android.util.Log.w(TAG, "Watchdog: ${stage.name.lowercase()} exceeded ${timeoutMs} ms")
            throw EngineException(EngineFailure.Unavailable(engineId, "Watchdog: ${stage.name.lowercase()} ${timeoutMs / 1000}s"), e)
        }

    companion object {
        /**
         * An unread region once the engine has read it: the text it read, and a glyph size from area per character, as
         * manga-ocr gives for a whole-bubble reading (a Latin letter is about half as wide as it is tall).
         */
        internal fun withReading(region: TextRegion, source: String?, lang: Lang): TextRegion {
            val text = source?.trim().orEmpty()
            val read = ReadState.READ
            if (text.isEmpty()) return region.copy(text = "", state = read, lines = region.lines.map { it.copy(text = "", state = read) })
            val chars = text.count { !it.isWhitespace() }.coerceAtLeast(1)
            val b = region.bbox
            val perChar = if (lang.isCjk) 1f else 0.6f
            val glyph = kotlin.math.sqrt(b.width.toFloat() * b.height / (chars * perChar)).coerceIn(8f, maxOf(8f, minOf(b.width, b.height).toFloat()))
            return region.copy(text = text, state = read, lines = region.lines.map { it.copy(text = text, fontSizePx = glyph, state = read) })
        }

        /** Bump whenever rendering changes so stale patches are not served from the disk cache. */
        const val PIPELINE_VERSION = 15
        const val MIN_EDGE = 96
        /** Bubbles per on-device generation on a viewport: small enough that the first patches come early. */
        const val LOCAL_GROUP = 4
        private const val TAG = "TranslationPipeline"
        const val STAGE_TIMEOUT_MS = 50_000L
        /** A Studio group's cap: generous, so only a hung engine fails a page (Qwen on a phone's CPU takes minutes). */
        const val STUDIO_TIMEOUT_MS = 240_000L


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
