package com.smnexstudio.panelglass.feature.studio.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.EngineResolver
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.ContextPair
import com.smnexstudio.panelglass.core.model.PageStrokes
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.render.PageCleaner
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import com.smnexstudio.panelglass.core.ocr.MangaOcrRecognizer
import kotlinx.serialization.json.Json
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The two pipeline stages the Studio runs on a page. A seam, so [StudioTranslator] is tested with scripted stages. */
interface StudioStages {
    /** Detects and reads a page (regions in reading order, page pixels). */
    suspend fun detect(bitmap: Bitmap, cfg: TranslateConfig): List<TextRegion>

    /** Translates a page's regions; throws [com.smnexstudio.panelglass.core.model.EngineException] on an engine failure. */
    suspend fun translate(bitmap: Bitmap, regions: List<TextRegion>, cfg: TranslateConfig, prior: List<ContextPair>): TranslationPipeline.StudioTranslation

    /** Reads the text inside [rect] (bitmap pixels) as one region of [kind]. */
    suspend fun read(bitmap: Bitmap, rect: IntRect, cfg: TranslateConfig, kind: RegionKind): TextRegion

    /** The clean layer of [bitmap]: the bubbles' text removed, then the strokes applied (all in bitmap pixels). */
    fun clean(bitmap: Bitmap, bubbles: List<Bubble>, strokes: List<BrushStroke>): Bitmap

    /** A run's cleaning is over: whatever cleaning loaded can go. */
    fun cleaningDone() {}
}

@Singleton
class PipelineStages @Inject constructor(
    private val pipeline: TranslationPipeline,
    private val cleaner: PageCleaner,
    private val lama: LamaInpainter,
    private val locals: LocalEngines,
    private val mangaOcr: MangaOcrRecognizer,
) : StudioStages {
    /**
     * One heavy model at a time (docs/STUDIO_PLAN.md › Memory): before LaMa runs, an idle on-device translation model
     * and manga-ocr are released.
     */
    override fun clean(bitmap: Bitmap, bubbles: List<Bubble>, strokes: List<BrushStroke>): Bitmap {
        if (lama.available) {
            locals.releaseIfIdle()
            mangaOcr.releaseIfIdle()
        }
        return cleaner.clean(bitmap, bubbles, strokes)
    }

    override fun cleaningDone() = lama.releaseIfIdle()
    override suspend fun read(bitmap: Bitmap, rect: IntRect, cfg: TranslateConfig, kind: RegionKind) =
        pipeline.readStudioRegion(bitmap, rect, cfg, kind)
    override suspend fun detect(bitmap: Bitmap, cfg: TranslateConfig) = pipeline.detectStudioPage(bitmap, cfg)
    override suspend fun translate(bitmap: Bitmap, regions: List<TextRegion>, cfg: TranslateConfig, prior: List<ContextPair>) =
        pipeline.translateRegions(bitmap, regions, cfg, prior)
}

/** A page decoded for analysis, and how much it was shrunk to fit the pixel budget ([scale] ≤ 1). */
class LoadedPage(val bitmap: Bitmap, val scale: Float)

/** Decodes a page file. A seam for the same reason as [StudioStages]. */
interface PageLoader {
    fun load(file: File): LoadedPage?
}

/**
 * Decodes a page in full unless it is bigger than [MAX_PIXELS]: then by powers of two until it fits, and the regions
 * found are scaled back to page pixels (a strip is read in tiles, so its text stays large enough).
 */
@Singleton
class BitmapPageLoader @Inject constructor() : PageLoader {
    override fun load(file: File): LoadedPage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        return LoadedPage(bmp, bmp.width.toFloat() / bounds.outWidth)
    }

    companion object {
        /** ~96 MB as ARGB: a 1000 × 24000 strip decodes whole; anything larger is halved until it fits. */
        const val MAX_PIXELS = 24_000_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StudioTranslateModule {
    @Binds abstract fun stages(impl: PipelineStages): StudioStages
    @Binds abstract fun loader(impl: BitmapPageLoader): PageLoader
    @Binds abstract fun resolver(impl: EngineRegistry): EngineResolver
}

/** A page's brush strokes on disk (`strokes.json` beside the page). A missing or unreadable file is no strokes. */
object StrokeStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): List<BrushStroke> =
        runCatching { if (file.isFile) json.decodeFromString(PageStrokes.serializer(), file.readText()).strokes else emptyList() }
            .getOrDefault(emptyList())

    fun write(file: File, strokes: List<BrushStroke>) {
        if (strokes.isEmpty()) { file.delete(); return }
        val tmp = File(file.path + ".part")
        tmp.writeText(json.encodeToString(PageStrokes.serializer(), PageStrokes(strokes)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /** Strokes in another pixel space ([f] × page pixels). */
    fun scaled(strokes: List<BrushStroke>, f: Float): List<BrushStroke> =
        if (f == 1f) strokes else strokes.map { s -> s.copy(radius = s.radius * f, points = s.points.map { Pt(it.x * f, it.y * f) }) }
}

/** Regions between the decoded bitmap's pixels and the page's. The glyph mask does not scale and is dropped. */
internal object RegionScale {
    fun rect(x: IntRect, f: Float): IntRect =
        if (f == 1f) x else IntRect((x.left * f).roundToInt(), (x.top * f).roundToInt(), (x.right * f).roundToInt(), (x.bottom * f).roundToInt())

    fun scale(r: TextRegion, f: Float): TextRegion {
        if (f == 1f) return r
        fun rect(x: IntRect) = rect(x, f)
        return r.copy(
            bbox = rect(r.bbox),
            mask = null,
            container = r.container?.let(::rect),
            lines = r.lines.map { l -> l.copy(bbox = rect(l.bbox), fontSizePx = l.fontSizePx?.times(f)) },
        )
    }
}
