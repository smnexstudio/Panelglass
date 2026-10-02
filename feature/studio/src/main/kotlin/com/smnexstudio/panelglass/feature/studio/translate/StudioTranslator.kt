package com.smnexstudio.panelglass.feature.studio.translate

import com.smnexstudio.panelglass.core.render.BalloonFit
import kotlinx.coroutines.withContext
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import android.graphics.Bitmap
import java.io.File
import com.smnexstudio.panelglass.core.engine.EngineResolution
import com.smnexstudio.panelglass.core.engine.EngineResolver
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.render.SfxRenderer
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class RunPhase { QUEUED, READING, TRANSLATING, CLEANING, DONE, FAILED, CANCELLED }

/** Where a chapter's run is: its phase, pages done of those the phase has to do, and what stopped it. */
data class ChapterRun(
    val chapterId: Long,
    val phase: RunPhase,
    val done: Int = 0,
    val total: Int = 0,
    val failure: EngineFailure? = null,
    /** Pages left untranslated by failures that did not stop the run (a timeout, an unreadable reply). */
    val failedPages: Int = 0,
) {
    val active: Boolean get() = phase == RunPhase.QUEUED || phase == RunPhase.READING || phase == RunPhase.TRANSLATING ||
        phase == RunPhase.CLEANING
}

/**
 * Reads and translates chapters in the background, one chapter at a time (one page decoded, one model in memory).
 * A run goes stage by stage: every page is read first (detector + OCR), then every page translated in order, each
 * page's bubbles and stages saved the moment it is done. Run again, it starts from the first page missing a stage, so
 * a cancelled run or a killed app loses at most the page in progress.
 *
 * The engine is the one selected in Settings. One that cannot be used (no key, no model, a missing language pack)
 * stops the run with that failure, for the UI to act on; the engine is never switched.
 */
@Singleton
class StudioTranslator @Inject constructor(
    private val repo: StudioRepository,
    private val stages: StudioStages,
    private val loader: PageLoader,
    private val resolver: EngineResolver,
    private val settings: SettingsRepository,
) {
    /** App-wide: a run outlives the screen that started it. Tests run [run] directly. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val one = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val _runs = MutableStateFlow<Map<Long, ChapterRun>>(emptyMap())
    val runs: StateFlow<Map<Long, ChapterRun>> = _runs.asStateFlow()

    /** Reads and translates what [chapterId] is missing; [redo] reads and translates every page again. */
    fun start(chapterId: Long, redo: Boolean = false) {
        if (jobs[chapterId]?.isActive == true) return
        set(ChapterRun(chapterId, RunPhase.QUEUED))
        jobs[chapterId] = scope.launch { one.withLock { run(chapterId, redo) } }
    }

    fun cancel(chapterId: Long) {
        jobs.remove(chapterId)?.cancel()
        set(ChapterRun(chapterId, RunPhase.CANCELLED))
    }

    /** Forgets a finished run's status (the message was read). */
    fun dismiss(chapterId: Long) {
        if (_runs.value[chapterId]?.active != true) _runs.update { it - chapterId }
    }

    /** The chapter's translation settings, or why its engine cannot be used (never another engine instead). */
    private sealed interface Setup {
        class Ready(val cfg: TranslateConfig) : Setup
        class Refused(val failure: EngineFailure) : Setup
    }

    private suspend fun setup(chapterId: Long): Setup? {
        val chapter = repo.getChapter(chapterId) ?: return null
        val manga = repo.getManga(chapter.mangaId) ?: return null
        val s = settings.current()
        (resolver.resolve(s.engineId) as? EngineResolution.Failed)?.let { return Setup.Refused(it.failure) }
        return Setup.Ready(
            TranslateConfig(
                src = manga.srcLang, tgt = chapter.language ?: manga.tgtLang, engineId = s.engineId,
                sfxMode = s.sfxMode, freeTextMode = s.freeTextMode, fontId = s.readerFontId, contextMode = s.contextMode,
                // The manga's own glossary (the "Remember this effect" entries, later).
                seriesKey = seriesKey(manga.id),
            ),
        )
    }

    /**
     * Translates one bubble again (its source text may have been corrected), with the page's earlier bubbles as
     * context. Waits for a chapter run in progress (one model at a time). Returns the failure, or null.
     */
    suspend fun retranslateBubble(bubbleId: Long, pageId: Long): EngineFailure? = one.withLock {
        val page = repo.getPage(pageId) ?: return@withLock null
        val all = repo.bubbleList(pageId).filter { !it.ignored }
        val bubble = all.firstOrNull { it.id == bubbleId } ?: return@withLock null
        val cfg = when (val s = setup(page.chapterId)) {
            is Setup.Ready -> s.cfg
            is Setup.Refused -> return@withLock s.failure
            null -> return@withLock null
        }
        val loaded = loader.load(repo.files.file(page.file))
            ?: return@withLock EngineFailure.Unavailable(cfg.engineId, UNREADABLE_PAGE)
        try {
            val out = stages.translate(loaded.bitmap, listOf(RegionScale.scale(regionOf(bubble), loaded.scale)), cfg, contextOf(all.takeWhile { it.id != bubbleId }))
            repo.saveTranslation(page, listOf(applied(bubble, out, 0, loaded.scale)))
            null
        } catch (e: EngineException) {
            e.failure
        } finally {
            loaded.bitmap.recycle()
        }
    }

    /**
     * A bubble the user boxed on a page ([rect], page pixels): its text read (or left for an engine that reads the
     * crop), then translated, and saved last in reading order. Returns the new bubble's id (null if the page is gone)
     * and the failure, if translating it failed: the bubble is kept either way, with what could be read.
     */
    suspend fun addBubble(pageId: Long, rect: IntRect, kind: RegionKind): Pair<Long?, EngineFailure?> = one.withLock {
        val page = repo.getPage(pageId) ?: return@withLock null to null
        val cfg = when (val s = setup(page.chapterId)) {
            is Setup.Ready -> s.cfg
            is Setup.Refused -> return@withLock addUnread(page, rect, kind) to s.failure
            null -> return@withLock null to null
        }
        val loaded = loader.load(repo.files.file(page.file))
            ?: return@withLock addUnread(page, rect, kind) to EngineFailure.Unavailable(cfg.engineId, UNREADABLE_PAGE)
        try {
            val region = RegionScale.scale(stages.read(loaded.bitmap, RegionScale.rect(rect, loaded.scale), cfg, kind), 1f / loaded.scale)
                .copy(bbox = rect, container = rect)
            val order = (repo.bubbleList(pageId).maxOfOrNull { it.order } ?: -1) + 1
            val bubble = Bubble(
                pageId = pageId, order = order, kind = kind, polygon = rect(rect),
                sourceText = if (region.unread) "" else region.text, region = region.copy(mask = null),
            ).let { if (it.kind == RegionKind.SFX) it.copy(sfx = SfxRenderer.initial(it, SfxRenderer.modeOf(cfg.sfxMode))) else it }
            val id = repo.addBubble(bubble)
            val saved = bubble.copy(id = id)
            val failure = try {
                val prior = contextOf(repo.bubbleList(pageId).filter { !it.ignored && it.id != id })
                val out = stages.translate(loaded.bitmap, listOf(RegionScale.scale(regionOf(saved), loaded.scale)), cfg, prior)
                repo.saveTranslation(repo.getPage(pageId) ?: page, listOf(applied(saved, out, 0, loaded.scale)))
                null
            } catch (e: EngineException) {
                e.failure
            }
            id to failure
        } finally {
            loaded.bitmap.recycle()
        }
    }

    /** A boxed bubble saved without reading it (the engine cannot be used or the page cannot be decoded). */
    private suspend fun addUnread(page: StudioPage, rect: IntRect, kind: RegionKind): Long {
        val order = (repo.bubbleList(page.id).maxOfOrNull { it.order } ?: -1) + 1
        val b = Bubble(pageId = page.id, order = order, kind = kind, polygon = rect(rect), region = null)
        return repo.addBubble(if (kind == RegionKind.SFX) b.copy(sfx = SfxRenderer.initial(b, SfxEditMode.OVERLAY)) else b)
    }

    /** [b] with the translation [out] gave for item [i]; what an engine that reads crops read becomes its source text. */
    private fun applied(b: Bubble, out: com.smnexstudio.panelglass.core.pipeline.TranslationPipeline.StudioTranslation, i: Int, scale: Float): Bubble {
        val readNow = b.region?.unread == true
        val region = out.regions.getOrNull(i)?.let { RegionScale.scale(it, 1f / scale) }
        return b.copy(
            translatedText = out.translations.getOrNull(i).orEmpty(),
            sourceText = if (readNow) region?.text.orEmpty() else b.sourceText,
            region = if (readNow) region?.copy(mask = null) ?: b.region else b.region,
        )
    }

    internal suspend fun run(chapterId: Long, redo: Boolean = false) {
        val cfg = when (val s = setup(chapterId)) {
            is Setup.Ready -> s.cfg
            is Setup.Refused -> { set(ChapterRun(chapterId, RunPhase.FAILED, failure = s.failure)); return }
            null -> return
        }
        val read = !cfg.engineId.readsCrops

        // ---- read: detector + OCR, every page first ----------------------------------------------------------
        val toRead = repo.pageList(chapterId).filter { redo || needsReading(it, read) }
        for ((i, page) in toRead.withIndex()) {
            set(ChapterRun(chapterId, RunPhase.READING, i, toRead.size))
            readPage(page, cfg, read)
        }

        // ---- translate: in page order, each page carrying the end of the one before --------------------------
        val pages = repo.pageList(chapterId)
        val toTranslate = pages.count { redo || StudioStage.TRANSLATED !in it.stages }
        var context = emptyList<ContextPair>()
        var done = 0
        var failedPages = 0
        var last: EngineFailure? = null
        for (page in pages) {
            if (!redo && StudioStage.TRANSLATED in page.stages) {
                context = contextOf(repo.bubbleList(page.id))
                continue
            }
            set(ChapterRun(chapterId, RunPhase.TRANSLATING, done, toTranslate, last, failedPages))
            try {
                context = translatePage(page, cfg, context)
            } catch (e: EngineException) {
                repo.updatePage(page.copy(failed = encode(e.failure)))
                if (stopsTheRun(e.failure)) {
                    set(ChapterRun(chapterId, RunPhase.FAILED, done, toTranslate, e.failure, failedPages + 1))
                    return
                }
                failedPages++
                last = e.failure
            }
            done++
        }
        // ---- clean: every read page whose clean layer is missing or out of date -------------------------------
        val toClean = repo.pageList(chapterId).filter { StudioStage.DETECTED in it.stages && (redo || StudioStage.CLEANED !in it.stages) }
        for ((i, page) in toClean.withIndex()) {
            set(ChapterRun(chapterId, RunPhase.CLEANING, i, toClean.size, last, failedPages))
            cleanNow(page)
        }
        if (toClean.isNotEmpty()) stages.cleaningDone()
        set(ChapterRun(chapterId, if (failedPages > 0) RunPhase.FAILED else RunPhase.DONE, done, toTranslate, last, failedPages))
    }

    /**
     * Rebuilds one page's clean layer now (the editor, after a reshape or a brush stroke). Waits for a chapter run in
     * progress. Returns false when the page could not be decoded.
     */
    suspend fun cleanPage(pageId: Long): Boolean = one.withLock {
        val page = repo.getPage(pageId) ?: return@withLock false
        cleanNow(page)
    }

    /**
     * The clean layer: the original with every bubble's text removed and the strokes applied, written as `clean.png`
     * beside the page (through a `.part` file, so a killed write never leaves half a layer), then the page marked
     * [StudioStage.CLEANED]. The latest page row is read again before saving: an edit made meanwhile is kept.
     */
    // Decoding, LaMa and the PNG write take seconds (tens on an emulator): never on the caller's thread, which for the
    // editor's re-clean is the main thread (an ANR while LaMa ran).
    private suspend fun cleanNow(page: StudioPage): Boolean = withContext(Dispatchers.Default) { cleanOff(page) }

    private suspend fun cleanOff(page: StudioPage): Boolean {
        val loaded = loader.load(repo.files.file(page.file)) ?: return false
        try {
            // Speech balloons still shaped as their detected box take the balloon's own outline first: fill, border,
            // cleaning and text then all follow the bubble, not the rectangle around it.
            traceBalloons(page.id, loaded.bitmap, loaded.scale)
            val bubbles = repo.bubbleList(page.id).map { b ->
                if (loaded.scale == 1f) b
                else b.copy(polygon = b.polygon.map { Pt(it.x * loaded.scale, it.y * loaded.scale) }, region = b.region?.let { RegionScale.scale(it, loaded.scale) })
            }
            val strokes = StrokeStore.scaled(StrokeStore.read(repo.files.file(StudioFiles.layer(page.file, StudioFiles.STROKES))), loaded.scale)
            val clean = stages.clean(loaded.bitmap, bubbles, strokes)
            try {
                val target = repo.files.file(StudioFiles.layer(page.file, StudioFiles.CLEAN))
                val tmp = File(target.path + ".part")
                tmp.outputStream().buffered().use { clean.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            } finally {
                clean.recycle()
            }
        } finally {
            loaded.bitmap.recycle()
        }
        val latest = repo.getPage(page.id) ?: return true
        repo.updatePage(latest.copy(stages = latest.stages + StudioStage.CLEANED))
        return true
    }

    /**
     * Each speech balloon of the page whose shape is still its detected rectangle is given the balloon's traced
     * outline ([BalloonFit.outline], on the original page in [bitmap], [scale] = bitmap / page pixels). Captions, free
     * text and sound effects keep their rectangle, as does any balloon whose paper cannot be found, and a shape the
     * user drew is never touched.
     */
    private suspend fun traceBalloons(pageId: Long, bitmap: Bitmap, scale: Float) {
        for (b in repo.bubbleList(pageId)) {
            if (b.kind != RegionKind.ENCLOSED || !isBox(b.polygon)) continue
            val l = (b.polygon.minOf { it.x } * scale).toInt().coerceIn(0, bitmap.width - 1)
            val t = (b.polygon.minOf { it.y } * scale).toInt().coerceIn(0, bitmap.height - 1)
            val r = (b.polygon.maxOf { it.x } * scale).toInt().coerceIn(l + 1, bitmap.width)
            val bt = (b.polygon.maxOf { it.y } * scale).toInt().coerceIn(t + 1, bitmap.height)
            val w = r - l
            val h = bt - t
            if (w < 8 || h < 8) continue
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, l, t, w, h)
            val outline = BalloonFit.outline(px, w, h) ?: continue
            repo.updateBubble(b.copy(polygon = outline.map { (x, y) -> Pt((x + l) / scale, (y + t) / scale) }))
        }
    }

    /** Detects and reads one page and saves its bubbles; later stages are cleared (they belonged to the old ones). */
    private suspend fun readPage(page: StudioPage, cfg: TranslateConfig, read: Boolean) {
        val loaded = loader.load(repo.files.file(page.file))
        if (loaded == null) {
            repo.updatePage(page.copy(failed = encode(EngineFailure.Unavailable(cfg.engineId, UNREADABLE_PAGE))))
            return
        }
        val regions = try {
            stages.detect(loaded.bitmap, cfg).map { RegionScale.scale(it, 1f / loaded.scale) }
        } finally {
            loaded.bitmap.recycle()
        }
        val stagesNow = page.stages - CLEARED_BY_READING + StudioStage.DETECTED + (if (read) setOf(StudioStage.READ) else emptySet())
        repo.saveDetection(page.copy(stages = stagesNow, failed = null), bubblesOf(page.id, regions, SfxRenderer.modeOf(cfg.sfxMode)))
    }

    /** Translates one page's bubbles and saves them; returns the page's last lines, the next page's context. */
    private suspend fun translatePage(page: StudioPage, cfg: TranslateConfig, prior: List<ContextPair>): List<ContextPair> {
        val bubbles = repo.bubbleList(page.id).filter { !it.ignored }
        val done = page.stages + StudioStage.TRANSLATED + StudioStage.READ - StudioStage.REVIEWED
        if (bubbles.isEmpty()) {
            repo.updatePage(page.copy(stages = done, failed = null))
            return prior
        }
        val loaded = loader.load(repo.files.file(page.file))
            ?: throw EngineException(EngineFailure.Unavailable(cfg.engineId, UNREADABLE_PAGE))
        val out = try {
            val regions = bubbles.map { RegionScale.scale(regionOf(it), loaded.scale) }
            stages.translate(loaded.bitmap, regions, cfg, prior)
        } finally {
            loaded.bitmap.recycle()
        }
        val updated = bubbles.mapIndexed { i, b -> applied(b, out, i, loaded.scale) }
        repo.saveTranslation(page.copy(stages = done, failed = null), updated)
        return out.contextPairs
    }

    private fun set(run: ChapterRun) = _runs.update { it + (run.chapterId to run) }

    companion object {
        /** A re-read replaces the bubbles, so what was made from the old ones goes too. */
        private val CLEARED_BY_READING = setOf(StudioStage.DETECTED, StudioStage.READ, StudioStage.TRANSLATED, StudioStage.CLEANED, StudioStage.REVIEWED)
        const val UNREADABLE_PAGE = "Unreadable page"

        private val json = Json { ignoreUnknownKeys = true }

        fun seriesKey(mangaId: Long) = "studio/$mangaId"

        /**
         * A page needs reading when it never was, or when it was detected for an engine that reads the crops (no OCR
         * text) and the engine now needs the text.
         */
        fun needsReading(page: StudioPage, read: Boolean): Boolean =
            StudioStage.DETECTED !in page.stages || (read && StudioStage.READ !in page.stages)

        /**
         * Failures that would fail every page the same way stop the run at once: no key, no quota, no model, a missing
         * language pack, no network, an engine that cannot be used. A timeout or an unreadable reply is per page.
         */
        fun stopsTheRun(f: EngineFailure): Boolean = when (f) {
            is EngineFailure.Malformed -> false
            is EngineFailure.Unavailable -> !f.reason.startsWith("Watchdog") && f.reason != UNREADABLE_PAGE
            else -> true
        }

        fun encode(f: EngineFailure): String = json.encodeToString(EngineFailure.serializer(), f)
        fun decode(s: String?): EngineFailure? = s?.let { runCatching { json.decodeFromString(EngineFailure.serializer(), it) }.getOrNull() }

        /** A detected region as a bubble: the balloon (else the text box) as a rectangle; no glyph mask is stored. */
        fun bubblesOf(pageId: Long, regions: List<TextRegion>, sfxMode: SfxEditMode = SfxEditMode.OVERLAY): List<Bubble> = regions.mapIndexed { i, r ->
            val box = r.container ?: r.bbox
            val b = Bubble(
                pageId = pageId, order = i, kind = r.kind, polygon = rect(box),
                sourceText = if (r.unread) "" else r.text,
                region = r.copy(mask = null),
            )
            // A sound effect starts in the Settings' mode (Replace erases it at the clean stage).
            if (r.kind == RegionKind.SFX) b.copy(sfx = SfxRenderer.initial(b, sfxMode)) else b
        }

        fun rect(b: IntRect) = listOf(
            Pt(b.left.toFloat(), b.top.toFloat()), Pt(b.right.toFloat(), b.top.toFloat()),
            Pt(b.right.toFloat(), b.bottom.toFloat()), Pt(b.left.toFloat(), b.bottom.toFloat()),
        )

        /**
         * The region to translate for a bubble: its detected region with the source text as it is now (the user may
         * have corrected it); a bubble the user drew gets a region from its polygon.
         */
        fun regionOf(b: Bubble): TextRegion {
            val r = b.region
            if (r != null) return if (r.unread) r else r.copy(text = b.sourceText)
            val xs = b.polygon.map { it.x }
            val ys = b.polygon.map { it.y }
            val box = IntRect(xs.min().roundToInt(), ys.min().roundToInt(), xs.max().roundToInt(), ys.max().roundToInt())
            return TextRegion(bbox = box, kind = b.kind, text = b.sourceText, container = box)
        }

        /** The last two lines of a page that has them: continuity context for the next page. */
        fun contextOf(bubbles: List<Bubble>): List<ContextPair> = bubbles
            .filter { !it.ignored && it.sourceText.isNotBlank() && it.translatedText.isNotBlank() }
            .map { ContextPair(it.sourceText, it.translatedText) }
            .takeLast(2)
    }
}

/** An axis-aligned rectangle: the detected box, not a shape someone drew or traced. */
internal fun isBox(p: List<Pt>): Boolean = p.size == 4 && p.all { a -> p.count { it.x == a.x } == 2 && p.count { it.y == a.y } == 2 }
