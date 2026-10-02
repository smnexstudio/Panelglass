package com.smnexstudio.panelglass.core.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * The Studio: chapters imported from images, a PDF or a CBZ, translated page by page, reviewed and exported as image
 * files (docs/STUDIO_PLAN.md). A manga holds chapters, a chapter holds pages, a page holds bubbles. The rows are Room
 * entities like [Site]; lists and styles are stored as JSON by `core:data`'s `StudioConverters`.
 */

/** Publication status, shown on the manga card (ComicInfo.xml has no field for it). */
@Serializable
enum class MangaStatus { UNKNOWN, ONGOING, COMPLETED, HIATUS, CANCELLED }

@Serializable
@Entity(tableName = "studio_manga")
data class Manga(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val altTitle: String = "",
    val author: String = "",
    val artist: String = "",
    val summary: String = "",
    /** Comma-separated, as ComicInfo's `Genre`. */
    val genres: String = "",
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val srcLang: Lang = Lang.JA,
    val tgtLang: Lang = Lang.EN,
    /** Persisted SAF tree URI the chapters are exported to; null until the first export. */
    val exportTreeUri: String? = null,
    val createdAt: Long = 0,
)

@Serializable
@Entity(
    tableName = "studio_chapter",
    foreignKeys = [ForeignKey(entity = Manga::class, parentColumns = ["id"], childColumns = ["mangaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mangaId")],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mangaId: Long,
    /** Chapter number as printed (12, 12.5); null when the chapter has none (a one-shot, an extra). */
    val number: Float? = null,
    val volume: Int? = null,
    val title: String = "",
    /** Language the chapter is exported in; null = the manga's target language. */
    val language: Lang? = null,
    /** ISO date (`yyyy-MM-dd`) or blank. */
    val releaseDate: String = "",
    val notes: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
) {
    /** The printed number (`12`, `12.5`), or null when the chapter has none. */
    val numberLabel: String? get() = number?.let(::formatNumber)

    companion object {
        /** `12`, `12.5`: never `12.0`. */
        fun formatNumber(n: Float): String =
            if (n == n.toLong().toFloat()) n.toLong().toString() else n.toString()
    }
}

/**
 * Pipeline stages a page has been through. Not `Stage` (the reader pipeline has one). Each can be cleared and re-run
 * on its own: editing a polygon clears [CLEANED], a new translation clears [REVIEWED].
 */
@Serializable
enum class StudioStage { DETECTED, READ, TRANSLATED, CLEANED, REVIEWED }

/**
 * Which stages an edit invalidates. The clean layer is built from the bubbles' shapes and the brush strokes, so a
 * reshape, a stroke, a dismissed / restored / added bubble clears [StudioStage.CLEANED]; text and style live only in the
 * lettering drawn over it, so editing them clears nothing.
 */
object StageRules {
    fun afterCleanInputChange(stages: Set<StudioStage>): Set<StudioStage> = stages - StudioStage.CLEANED
    fun afterTextOrStyleEdit(stages: Set<StudioStage>): Set<StudioStage> = stages
}

@Serializable
@Entity(
    tableName = "studio_page",
    foreignKeys = [ForeignKey(entity = Chapter::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chapterId")],
)
data class StudioPage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    /** 0-based position in the chapter. */
    val index: Int,
    /**
     * The imported image, relative to the Studio root (`pages/<key>/original.<ext>`). A page keeps its directory
     * when it is reordered or moved to another chapter; the layers (`clean.png`, `mask.png`) sit beside it.
     */
    val file: String,
    val width: Int,
    val height: Int,
    val stages: Set<StudioStage> = emptySet(),
    /** Why the last stage run failed on this page, shown on its tile; null when it did not. */
    val failed: String? = null,
) {
    val reviewed: Boolean get() = StudioStage.REVIEWED in stages
    /** Directory of this page's files, relative to the Studio root. */
    val dir: String get() = file.substringBeforeLast('/')
}

/** A point in page pixels. */
@Serializable
data class Pt(val x: Float, val y: Float)

/** Styling of one bubble's text (and of an effect's, [SfxEdit.style]). Stored as JSON: fields can be added freely. */
@Serializable
data class BubbleStyle(
    /** Key into the font catalogue (`cat:<id>`, `user:<sha256>`); null or unknown = the language's default. */
    val fontId: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val uppercase: Boolean = false,
    /** Fixed size in page pixels; null = fitted to the bubble. */
    val sizePx: Float? = null,
    /** ARGB; null = picked from the art under the bubble. */
    val textColor: Int? = null,
    val outlineColor: Int? = null,
    val outlineWidthPx: Float = 0f,
    /** A second, outer outline (the white-then-black comic stroke). */
    val outerOutlineColor: Int? = null,
    val outerOutlineWidthPx: Float = 0f,
    val letterSpacing: Float = 0f,
    /** ARGB fill under the text inside the polygon; alpha 0 keeps the cleaned art. */
    val fillColor: Int = 0,
    /** A line drawn along the bubble's shape (page pixels; 0 = none), e.g. to redraw a border the cleanup took away. */
    val borderWidthPx: Float = 0f,
    /** ARGB of that line; null = black. */
    val borderColor: Int? = null,
    /**
     * How round the shape's corners are drawn: 0 sharp, 1 as round as the corner allows (a curve over half of the
     * shorter edge either side). Fill, lettering clip and border all follow the rounded shape.
     */
    val cornerRoundness: Float = 0f,
)

@Serializable
enum class SfxEditMode { KEEP, GLOSS, OVERLAY, REPLACE }

/** Where and how a replacement sound effect is drawn, in page pixels. */
@Serializable
data class SfxTransform(
    val cx: Float,
    val cy: Float,
    val rotationDeg: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val skewX: Float = 0f,
    /** Arc bend, -1..1. */
    val curve: Float = 0f,
)

@Serializable
data class SfxEdit(
    val mode: SfxEditMode = SfxEditMode.OVERLAY,
    val text: String = "",
    val transform: SfxTransform,
    val style: BubbleStyle = BubbleStyle(),
    val preset: String? = null,
)

@Serializable
@Entity(
    tableName = "studio_bubble",
    foreignKeys = [ForeignKey(entity = StudioPage::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pageId")],
)
data class Bubble(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    /** Reading order on the page. */
    val order: Int,
    val kind: RegionKind,
    /** The shape the text is fitted into, in page pixels (at least 3 points). */
    val polygon: List<Pt>,
    val sourceText: String = "",
    val translatedText: String = "",
    val style: BubbleStyle = BubbleStyle(),
    /** The detector's region (colours, glyph size, mask) that cleanup needs; null for a bubble the user drew. */
    val region: TextRegion? = null,
    val sfx: SfxEdit? = null,
    /** A false detection the user dismissed: kept so a re-detect does not bring it back, never drawn. */
    val ignored: Boolean = false,
)

/** What a brush stroke does to the clean layer: removes what is under it, or brings the original back. */
@Serializable
enum class BrushMode { CLEAN, RESTORE }

/** One brush stroke on a page, in page pixels: a polyline painted with a round brush of [radius]. */
@Serializable
data class BrushStroke(val mode: BrushMode, val radius: Float, val points: List<Pt>)

/**
 * A page's brush strokes, oldest first (later strokes win where they overlap). Stored beside the page as
 * `strokes.json`, so the clean layer can be rebuilt at any time and an undo is just a shorter list.
 */
@Serializable
data class PageStrokes(val strokes: List<BrushStroke> = emptyList())

/** Role of a font in the picker. */
@Serializable
enum class FontRole { DIALOGUE, SFX, CAPTION }

/** A font the user added (docs/STUDIO_PLAN.md › My fonts). Files live in `filesDir/fonts/user/`. */
@Serializable
@Entity(tableName = "user_font")
data class UserFont(
    /** `user:<sha256 of the regular file>`. */
    @PrimaryKey val id: String,
    val displayName: String,
    val family: String,
    /** Style (`regular`, `bold`, `italic`, `bolditalic`) → file name in the user font directory. */
    val files: Map<String, String>,
    /** Catalogue script ids the font draws (`latin`, `ja`, …). */
    val scripts: Set<String> = emptySet(),
    val role: FontRole = FontRole.DIALOGUE,
    val tags: List<String> = emptyList(),
    val addedAt: Long = 0,
)
