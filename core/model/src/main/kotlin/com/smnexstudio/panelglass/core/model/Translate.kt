package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ContextPair(val source: String, val translation: String)

@Serializable
data class TranslateConfig(
    val src: Lang,
    val tgt: Lang,
    val engineId: EngineId,
    val sfxMode: SfxMode = SfxMode.OVERLAY,
    val freeTextMode: FreeTextMode = FreeTextMode.OVERLAY,
    /** The lettering font, a Studio font id; null is the target language's default ([Settings.readerFontId]). */
    val fontId: String? = null,
    /** 0..100; 100 means lossless WEBP patches. */
    val patchQuality: Int = 100,
    val seriesKey: String = "",
    val glossary: Map<String, String> = emptyMap(),
    val contextMode: ContextMode = ContextMode.BATCHED,
)

/** Where the pipeline gets its pixels: a platform bitmap (the reader's viewport snapshot) the pipeline module unwraps. */
sealed class ImageSource {
    class Native(val handle: Any) : ImageSource()
}

/** A rendered region as a percentage-positioned WEBP overlay. Never a whole image. */
class Patch(
    val xPct: Float,
    val yPct: Float,
    val wPct: Float,
    val hPct: Float,
    val webp: ByteArray,
)

data class PageResult(
    val imageId: String,
    val width: Int,
    val height: Int,
    val regionCount: Int,
    val patchCount: Int,
    val fromCache: Boolean,
    val skippedReason: SkipReason? = null,
    val timingMs: Long = 0,
    val contextPairs: List<ContextPair> = emptyList(),
    /** Text blocks left untranslated because the bitmap's edge cut them (viewport snapshots); in bitmap pixels. */
    val deferred: List<IntRect> = emptyList(),
)

enum class SkipReason { NO_REGIONS, TOO_SMALL }

@Serializable
data class RegionItem(val i: Int, val imageId: String, val kind: RegionKind, val text: String)

/** [source] is what the engine read, for items it was asked to read from an image ([TranslateRequest.readImages]). */
@Serializable
data class TranslatedItem(val i: Int, val text: String, val source: String? = null)

class TranslateRequest(
    val items: List<RegionItem>,
    val src: Lang,
    val tgt: Lang,
    val glossary: Map<String, String> = emptyMap(),
    val priorContext: List<ContextPair> = emptyList(),
    /** Indexed grid of SFX crops (JPEG bytes) for vision-capable engines; unused until the OCR step returns. */
    val sfxMontage: ByteArray? = null,
    /** Item indices (`i`) that appear in the montage, in cell order. */
    val sfxMontageIndices: List<Int> = emptyList(),
    /**
     * Items the engine reads itself, by `i`: JPEG crop of the region. Their `text` is empty; the reply carries the text
     * read ([TranslatedItem.source]) and its translation. Only for engines with [EngineId.readsCrops].
     */
    val readImages: Map<Int, ByteArray> = emptyMap(),
) {
    /** Subset request for retries/fallbacks. The montage is dropped: its cell numbering would no longer match. */
    fun withItems(subset: List<RegionItem>): TranslateRequest {
        val ids = subset.map { it.i }.toSet()
        return TranslateRequest(subset, src, tgt, glossary, priorContext, null, emptyList(), readImages.filterKeys { it in ids })
    }
}
