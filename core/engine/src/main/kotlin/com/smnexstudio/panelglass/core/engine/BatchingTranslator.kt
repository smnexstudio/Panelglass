package com.smnexstudio.panelglass.core.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.smnexstudio.panelglass.core.data.repo.SfxCacheRepository
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxCacheEntry
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TextNorm
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.TranslateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * One image's worth of regions to translate. [crops] are padded crops by region index: for SFX regions (read by a
 * vision engine from a montage) and for every [TextRegion.unread] region (the engine reads the lettering itself).
 */
class TranslateJob(
    val imageId: String,
    val cfg: TranslateConfig,
    val regions: List<TextRegion>,
    val crops: Map<Int, Bitmap> = emptyMap(),
    val priorContext: List<ContextPair> = emptyList(),
)

/** A region's translation, and what the engine read when the region was [TextRegion.unread]. */
class Reading(val text: String, val source: String?)

/**
 * The translate stage. Resolves SFX through dictionary → cache → engine, de-duplicates by
 * normalised text, and folds several images' regions into one engine call (cap [MAX_ITEMS])
 * because cloud latency is round-trip-dominated; the on-device model gets one image per call
 * because there the cost is tokens in a small cache, not round trips. Rate limits back
 * off automatically; every other failure surfaces typed — the engine the user picked is never
 * swapped for another behind their back. A caller cancelled while waiting withdraws its items, and
 * a batch nobody waits for any more is cancelled instead of running on and holding the engine.
 */
@Singleton
class BatchingTranslator @Inject constructor(
    private val registry: EngineRegistry,
    private val sfxDictionary: SfxDictionary,
    private val sfxCache: SfxCacheRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending(
        val item: RegionItem, val crop: Bitmap?, val src: Lang, val tgt: Lang, val glossary: Map<String, String>, val context: List<ContextPair>,
        /** The engine reads [crop] itself; [item] has no text. */
        val read: Boolean = false,
    ) {
        val result = CompletableDeferred<Reading>()
        /** Set when the caller stopped waiting; the batch it was flushed into is cancelled once every member is. */
        @Volatile var withdrawn = false
        @Volatile var batch: Batch? = null
    }

    private class Batch(val members: List<Pending>) {
        @Volatile var job: Job? = null
        fun abandoned() = members.all { it.withdrawn }
    }

    private class Lane(val engineId: EngineId) {
        val items = ArrayList<Pending>()
        var flushJob: Job? = null
    }

    private val lanes = HashMap<String, Lane>()
    private val lock = Mutex()

    /** Returns one translated string per region; null means "leave this region alone". */
    suspend fun translate(job: TranslateJob): List<String?> = translateReading(job).map { it?.text }

    /** As [translate], with what the engine read for [TextRegion.unread] regions. */
    suspend fun translateReading(job: TranslateJob): List<Reading?> {
        val cfg = job.cfg
        val out = arrayOfNulls<Reading>(job.regions.size)
        val pending = ArrayList<Pair<Int, Pending>>()

        // Tier 1 + 2 for SFX, and duplicate collapsing across the image.
        val sfxKeys = job.regions.mapIndexedNotNull { i, r -> if (r.kind == RegionKind.SFX) i to TextNorm.sfxKey(r.text) else null }
        val cached = sfxCache.lookup(sfxKeys.map { SfxCacheEntry.keyOf(it.second, cfg.src, cfg.tgt) }.toSet())
        val local = HashMap<String, Pending>()
        for ((i, region) in job.regions.withIndex()) {
            if (region.text.isBlank()) continue
            if (region.unread) {
                // Nothing to look up or de-duplicate: every unread region has the same placeholder text.
                val crop = job.crops[i] ?: continue
                pending += i to Pending(RegionItem(0, job.imageId, region.kind, ""), crop, cfg.src, cfg.tgt, cfg.glossary, job.priorContext, read = true)
                continue
            }
            if (region.kind == RegionKind.SFX) {
                if (cfg.sfxMode == SfxMode.SKIP) continue
                val norm = TextNorm.sfxKey(region.text)
                val hit = sfxDictionary.lookup(region.text, cfg.src, cfg.tgt) ?: cached[SfxCacheEntry.keyOf(norm, cfg.src, cfg.tgt)]
                if (hit != null) { out[i] = Reading(hit, null); continue }
            }
            val dedupeKey = region.kind.name + "|" + (if (region.kind == RegionKind.SFX) TextNorm.sfxKey(region.text) else region.text.trim())
            val p = local.getOrPut(dedupeKey) {
                Pending(RegionItem(0, job.imageId, region.kind, region.text.trim()), job.crops[i], cfg.src, cfg.tgt, cfg.glossary, job.priorContext)
            }
            pending += i to p
        }
        if (pending.isEmpty()) return out.toList()

        val mine = pending.map { it.second }.distinct()
        enqueue(cfg.engineId, mine)
        try {
            for ((i, p) in pending) out[i] = p.result.await()
        } catch (e: CancellationException) {
            withdraw(cfg.engineId, mine)
            throw e
        }

        // Tier 3 results feed the SFX cache so the next chapter costs nothing.
        val store = HashMap<String, String>()
        for ((i, _) in pending) {
            val r = out[i] ?: continue
            if (job.regions[i].kind != RegionKind.SFX || r.text.isBlank()) continue
            val read = if (job.regions[i].unread) r.source?.takeIf { it.isNotBlank() } ?: continue else job.regions[i].text
            store[SfxCacheEntry.keyOf(TextNorm.sfxKey(read), cfg.src, cfg.tgt)] = r.text
        }
        sfxCache.store(store)
        return out.toList()
    }

    private suspend fun enqueue(engineId: EngineId, items: List<Pending>) {
        val first = items.first()
        val laneKey = engineId.name + "|" + first.src.code + "|" + first.tgt.code
        lock.withLock {
            val lane = lanes.getOrPut(laneKey) { Lane(engineId) }
            lane.items += items
            val images = lane.items.map { it.item.imageId }.toSet().size
            if (lane.items.size >= MAX_ITEMS || images >= imagesPerCall(engineId)) {
                flushLocked(laneKey, lane)
            } else if (lane.flushJob == null) {
                lane.flushJob = scope.launch {
                    delay(WINDOW_MS)
                    lock.withLock { lanes[laneKey]?.let { if (it.items.isNotEmpty()) flushLocked(laneKey, it) } }
                }
            }
        }
    }

    private fun flushLocked(laneKey: String, lane: Lane) {
        val members = ArrayList(lane.items.take(MAX_ITEMS))
        lane.items.subList(0, members.size).clear()
        lane.flushJob?.cancel(); lane.flushJob = null
        if (lane.items.isEmpty()) lanes.remove(laneKey)
        val batch = Batch(members)
        members.forEach { it.batch = batch }
        batch.job = scope.launch { runBatch(lane.engineId, members) }
    }

    /** The caller went away: unsent items leave the lane, and a running batch with no waiter left is cancelled. */
    private suspend fun withdraw(engineId: EngineId, items: List<Pending>) {
        val first = items.first()
        val laneKey = engineId.name + "|" + first.src.code + "|" + first.tgt.code
        lock.withLock {
            lanes[laneKey]?.let { lane ->
                lane.items.removeAll(items.toSet())
                if (lane.items.isEmpty()) { lane.flushJob?.cancel(); lanes.remove(laneKey) }
            }
        }
        for (p in items) {
            p.withdrawn = true
            p.batch?.let { b -> if (b.abandoned()) b.job?.cancel() }
        }
    }

    /** One image per call for the on-device model: its cost is tokens in a 1280-token cache, not round trips. */
    private fun imagesPerCall(engineId: EngineId) = if (engineId.isLocalLlm) 1 else IMAGES_PER_CALL

    private suspend fun runBatch(engineId: EngineId, batch: List<Pending>) {
        val items = batch.mapIndexed { idx, p -> p.item.copy(i = idx) }
        val src = batch.first().src; val tgt = batch.first().tgt
        val glossary = HashMap<String, String>().apply { batch.forEach { putAll(it.glossary) } }
        val context = batch.flatMap { it.context }.distinct().takeLast(2)
        val engine = when (val r = registry.resolve(engineId)) {
            is EngineResolution.Ready -> r.engine
            is EngineResolution.Failed -> { batch.forEach { it.result.completeExceptionally(EngineException(r.failure)) }; return }
        }
        var montage: ByteArray? = null; var montageIdx: List<Int> = emptyList()
        var readImages: Map<Int, ByteArray> = emptyMap()
        if (engine.acceptsImages) {
            readImages = batch.withIndex().filter { it.value.read }.associate { it.index to CropJpeg.encode(it.value.crop!!) }
            val crops = batch.withIndex().filter { !it.value.read && it.value.item.kind == RegionKind.SFX && it.value.crop != null }
            if (crops.isNotEmpty()) {
                montage = SfxMontage.build(crops.map { it.value.crop!! })
                montageIdx = crops.map { it.index }
            }
        }
        val req = TranslateRequest(items, src, tgt, glossary, context, montage, montageIdx, readImages)

        var attempt = 0
        while (true) {
            try {
                val result = engine.translate(req).associateBy { it.i }
                batch.forEachIndexed { idx, p ->
                    val got = result[idx]
                    p.result.complete(Reading(got?.text?.takeIf { it.isNotBlank() } ?: p.item.text, got?.source))
                }
                return
            } catch (e: CancellationException) {
                batch.forEach { it.result.cancel(e) }
                throw e
            } catch (e: EngineException) {
                val wait = EngineRetry.delayFor(e.failure, ++attempt)
                if (wait != null) { delay(wait); continue }
                batch.forEach { it.result.completeExceptionally(e) }
                return
            } catch (e: Exception) {
                batch.forEach { it.result.completeExceptionally(EngineException(EngineFailure.Unavailable(engineId), e)) }
                return
            }
        }
    }

    companion object {
        const val MAX_ITEMS = 60
        const val IMAGES_PER_CALL = 3
        const val WINDOW_MS = 80L
    }
}

/** One region crop as a JPEG for an engine to read: legible lettering at the fewest image tokens. */
object CropJpeg {
    /** Longest side. A viewport bubble is 200-500 px; Gemini bills an image up to 384 px square as one tile. */
    private const val MAX_SIDE = 512

    fun encode(crop: Bitmap): ByteArray {
        val longest = maxOf(crop.width, crop.height)
        val scaled = if (longest <= MAX_SIDE) crop else {
            val s = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(crop, (crop.width * s).toInt().coerceAtLeast(1), (crop.height * s).toInt().coerceAtLeast(1), true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== crop) scaled.recycle()
        return out.toByteArray()
    }
}

/** Lays SFX crops into a numbered grid so a vision model can read them in the same call as the dialogue. */
object SfxMontage {
    private const val CELL = 160
    private const val PAD = 6

    fun build(crops: List<Bitmap>): ByteArray {
        val cols = ceil(sqrt(crops.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(crops.size / cols.toFloat()).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(cols * CELL, rows * CELL, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val border = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; textSize = 18f; isFakeBoldText = true }
        val img = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((k, crop) in crops.withIndex()) {
            val cx = (k % cols) * CELL; val cy = (k / cols) * CELL
            val inner = CELL - 2 * PAD - 18
            val scale = minOf(inner.toFloat() / crop.width, inner.toFloat() / crop.height, 1f)
            val w = (crop.width * scale).toInt().coerceAtLeast(1); val h = (crop.height * scale).toInt().coerceAtLeast(1)
            val left = cx + (CELL - w) / 2; val top = cy + 18 + (inner - h) / 2
            canvas.drawBitmap(crop, null, Rect(left, top, left + w, top + h), img)
            canvas.drawRect(cx + 1f, cy + 1f, cx + CELL - 1f, cy + CELL - 1f, border)
            canvas.drawText((k + 1).toString(), cx + 4f, cy + 16f, label)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
