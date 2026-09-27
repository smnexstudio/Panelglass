package com.smnexstudio.panelglass.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RegionKind { ENCLOSED, CAPTION, FREE, SFX, IN_SCENE }

/** One recognised line as produced by the detector stage, before clustering. */
@Serializable
data class TextLine(
    val bbox: IntRect,
    val text: String,
    val vertical: Boolean = false,
    val angle: Float = 0f,
    val confidence: Float = 1f,
    /** Glyph size when the recogniser knows it better than the box does (a whole-bubble reading spans several columns). */
    val fontSizePx: Float? = null,
) {
    companion object {
        /**
         * Text of a line nobody has read yet: the detector found a text box but OCR was skipped because the engine reads
         * the crop itself (see [EngineId.readsCrops]). Non-blank so clustering, classification and ordering keep it.
         */
        const val UNREAD = "[unread]"
    }
}

/** A clustered, classified region of source text. This is the unit the engine and renderer work on. */
@Serializable
data class TextRegion(
    val bbox: IntRect,
    val mask: GlyphMask? = null,
    val kind: RegionKind,
    val angle: Float = 0f,
    val vertical: Boolean = false,
    val text: String,
    val bgColor: Int = 0xFFFFFFFF.toInt(),
    val fgColor: Int = 0xFF000000.toInt(),
    val lines: List<TextLine> = emptyList(),
    /** The balloon around this text when a detector found one: the space the translation may use, in page pixels. */
    val container: IntRect? = null,
) {
    /** Built from [TextLine.UNREAD] lines: the engine reads this region from its crop. */
    val unread: Boolean get() = TextLine.UNREAD in text

    val fontSizePx: Float
        get() {
            val hinted = lines.mapNotNull { it.fontSizePx }
            return when {
                hinted.isNotEmpty() -> hinted.average().toFloat()
                lines.isEmpty() -> (if (vertical) bbox.width else bbox.height).toFloat()
                vertical -> lines.map { it.bbox.width }.average().toFloat()
                else -> lines.map { it.bbox.height }.average().toFloat()
            }
        }
}

/** Cached, engine-independent detection result for one image. */
@Serializable
data class DetectedPage(
    val width: Int,
    val height: Int,
    val regions: List<TextRegion>,
)
