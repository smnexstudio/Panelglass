package com.smnexstudio.panelglass.core.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.os.LocaleList
import android.text.TextPaint
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.FontRole
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxEdit
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.SfxTransform
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The Studio's sound effects (docs/STUDIO_PLAN.md › Sound effects): a detected effect is kept, glossed (a small label
 * beside it), overlaid on the original, or replaced (the original is erased by [PageCleaner] and the new effect drawn
 * in its place). The effect is the bubble's translation drawn with [SfxEdit]'s style and transform: centre, rotation,
 * scale (both axes), slant and an arc bend, on a quadratic path as the reader's effects are.
 */
object SfxRenderer {
    /** The edit a sound effect starts with: the Settings mode, centred on its box, upright unless the box stands tall. */
    fun initial(b: Bubble, mode: SfxEditMode): SfxEdit {
        val box = PolygonFit.bounds(b.polygon)
        val tall = box.height > box.width * 1.4f
        return SfxEdit(
            mode = mode,
            transform = SfxTransform(cx = box.centerX, cy = box.centerY, rotationDeg = if (tall) -75f else 0f),
            style = BubbleStyle(uppercase = true),
        )
    }

    /** The Studio mode for the Settings' sound-effect mode (Skip keeps the original). */
    fun modeOf(m: SfxMode): SfxEditMode = when (m) {
        SfxMode.SKIP -> SfxEditMode.KEEP
        SfxMode.GLOSS -> SfxEditMode.GLOSS
        SfxMode.OVERLAY -> SfxEditMode.OVERLAY
        SfxMode.REPLACE -> SfxEditMode.REPLACE
    }

    /** [b]'s effect: its own edit, or the overlay a sound effect had before edits existed. */
    fun editOf(b: Bubble): SfxEdit = b.sfx ?: initial(b, SfxEditMode.OVERLAY)

    private class Paints {
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    }
    private val paints = ThreadLocal.withInitial { Paints() }

    /** The text as drawn: the translation (uppercased when asked), or nothing. */
    private fun label(b: Bubble, e: SfxEdit, tgt: Lang): String =
        b.translatedText.trim().let { if (e.style.uppercase) it.uppercase(Locale.forLanguageTag(tgt.code)) else it }

    /** Sets up [p] for [b]: font, colours, and the base size that fits the effect's box before its own scale. */
    private fun setUp(p: TextPaint, b: Bubble, e: SfxEdit, tgt: Lang, text: String) {
        val s = e.style
        p.typeface = StudioFonts.typeface(s.fontId, tgt, s.bold, s.italic, FontRole.SFX)
        p.textLocales = LocaleList(Locale.forLanguageTag(tgt.code))
        p.letterSpacing = s.letterSpacing
        p.textSkewX = 0f
        val box = PolygonFit.bounds(b.polygon)
        val length = max(box.width, box.height) * 0.95f
        val thickness = min(box.width, box.height)
        p.textSize = thickness.coerceIn(12f, 400f)
        val measured = p.measureText(text)
        if (measured > length) p.textSize = max(10f, p.textSize * length / measured)
    }

    /** Fill and outline colours: the edit's, else ink that stands out from the original effect's own colour. */
    private fun colours(b: Bubble, s: BubbleStyle): Pair<Int, Int> {
        val fg = b.region?.fgColor ?: Color.BLACK
        val fill = s.textColor ?: if (Raster.lum(fg) < 128) Color.BLACK else Color.WHITE
        val outline = s.outlineColor ?: if (Raster.lum(fill) < 128) Color.WHITE else Color.BLACK
        return fill to outline
    }

    /** The transform as a matrix from the effect's own frame (text centred on the origin) to page pixels. */
    private fun matrix(t: SfxTransform, scale: Float): Matrix = Matrix().apply {
        postSkew(t.skewX, 0f)
        postScale(t.scaleX, t.scaleY)
        postRotate(t.rotationDeg)
        postTranslate(t.cx * scale, t.cy * scale)
    }

    /** The baseline path: straight, or an arc bent by [SfxTransform.curve] (−1..1). */
    private fun path(half: Float, baseline: Float, curve: Float): Path = Path().apply {
        val bend = curve * half * 0.5f
        moveTo(-half, baseline + bend)
        quadTo(0f, baseline - bend, half, baseline + bend)
    }

    /** Draws [b]'s effect per its mode; Keep draws nothing. [scale] maps page pixels to the canvas. */
    fun draw(canvas: Canvas, b: Bubble, tgt: Lang, scale: Float = 1f) {
        if (b.ignored || b.translatedText.isBlank()) return
        val e = editOf(b)
        when (e.mode) {
            SfxEditMode.KEEP -> return
            SfxEditMode.GLOSS -> drawGloss(canvas, b, e, tgt, scale)
            SfxEditMode.OVERLAY, SfxEditMode.REPLACE -> drawEffect(canvas, b, e, tgt, scale)
        }
    }

    private fun drawEffect(canvas: Canvas, b: Bubble, e: SfxEdit, tgt: Lang, scale: Float) {
        val p = paints.get()!!
        val text = label(b, e, tgt)
        setUp(p.fill, b, e, tgt, text)
        p.fill.textSize *= scale
        val (fill, outline) = colours(b, e.style)
        val outlineW = (if (e.style.outlineWidthPx > 0f) e.style.outlineWidthPx * scale else p.fill.textSize * 0.12f)
        val half = p.fill.measureText(text) / 2f + p.fill.textSize * 0.2f
        val baseline = -(p.fill.ascent() + p.fill.descent()) / 2f
        val pth = path(half, baseline, e.transform.curve)
        canvas.save()
        canvas.concat(matrix(e.transform, scale))
        p.stroke.set(p.fill)
        p.stroke.style = Paint.Style.STROKE
        p.stroke.strokeJoin = Paint.Join.ROUND
        e.style.outerOutlineColor?.let { outer ->
            p.stroke.color = outer
            p.stroke.strokeWidth = outlineW * 2f + (if (e.style.outerOutlineWidthPx > 0f) e.style.outerOutlineWidthPx * scale * 2f else outlineW * 2f)
            canvas.drawTextOnPath(text, pth, 0f, 0f, p.stroke)
        }
        if (Color.alpha(outline) > 0 && outlineW > 0f) {
            p.stroke.color = outline
            p.stroke.strokeWidth = outlineW * 2f // centred on the glyph edge: half of it shows
            canvas.drawTextOnPath(text, pth, 0f, 0f, p.stroke)
        }
        p.fill.color = fill
        canvas.drawTextOnPath(text, pth, 0f, 0f, p.fill)
        canvas.restore()
        p.fill.letterSpacing = 0f
    }

    /** Gloss: a small label below the effect (above it near the page's bottom), white with a dark outline. */
    private fun drawGloss(canvas: Canvas, b: Bubble, e: SfxEdit, tgt: Lang, scale: Float) {
        val p = paints.get()!!
        val text = label(b, e, tgt)
        val box = PolygonFit.bounds(b.polygon)
        p.fill.typeface = StudioFonts.typeface(e.style.fontId, tgt, true, false, FontRole.SFX)
        p.fill.textLocales = LocaleList(Locale.forLanguageTag(tgt.code))
        p.fill.letterSpacing = 0f
        p.fill.textSize = (min(box.width, box.height) * 0.35f).coerceIn(14f, 48f) * scale
        val (fill, outline) = colours(b, e.style)
        val below = box.bottom * scale + p.fill.textSize * 1.1f
        val y = if (below > canvas.height - 4) box.top * scale - p.fill.textSize * 0.3f else below
        p.stroke.set(p.fill)
        p.stroke.style = Paint.Style.STROKE
        p.stroke.strokeWidth = p.fill.textSize * 0.25f
        p.stroke.color = outline
        canvas.drawText(text, box.centerX * scale, y, p.stroke)
        p.fill.color = fill
        canvas.drawText(text, box.centerX * scale, y, p.fill)
    }

    /**
     * The four corners of the drawn effect in page pixels (the editor's transform box): the text's own box, through
     * the transform. Null when there is nothing to draw.
     */
    fun frame(b: Bubble, tgt: Lang): List<Pt>? {
        val e = editOf(b)
        if (e.mode == SfxEditMode.KEEP || e.mode == SfxEditMode.GLOSS) return null
        val text = label(b, e, tgt).ifEmpty { b.sourceText.ifEmpty { return null } }
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        setUp(p, b, e, tgt, text)
        val half = p.measureText(text) / 2f + p.textSize * 0.2f
        val up = p.textSize * 0.6f + kotlin.math.abs(e.transform.curve) * half * 0.5f
        val pts = floatArrayOf(-half, -up, half, -up, half, up, -half, up)
        matrix(e.transform, 1f).mapPoints(pts)
        return (0 until 4).map { Pt(pts[it * 2], pts[it * 2 + 1]) }
    }

    /** Whether [b] is a sound effect drawn by this renderer rather than fitted into its polygon. */
    fun handles(b: Bubble): Boolean = b.kind == RegionKind.SFX
}

/**
 * Starting points for a sound effect's look (the plan's presets). Each sets the font, colours, outlines, spacing and
 * slant; everything stays editable after. For a target whose script the preset's font cannot draw, that script's
 * sound-effect default stands in.
 */
enum class SfxPreset {
    IMPACT, PUNCH, HORROR, SOFT, RUMBLE, MATCH;

    fun apply(e: SfxEdit, b: Bubble, tgt: Lang): SfxEdit {
        fun font(id: String): String? {
            val f = StudioFonts.find(id) ?: return null
            return if (StudioFonts.fits(f, tgt)) id else null
        }
        val base = e.style.copy(outerOutlineColor = null, outerOutlineWidthPx = 0f, outlineWidthPx = 0f, letterSpacing = 0f, uppercase = true, bold = false, italic = false)
        val t = e.transform
        return when (this) {
            IMPACT -> e.copy(
                preset = name,
                style = base.copy(fontId = font("cat:bangers"), textColor = 0xFFFFD21F.toInt(), outlineColor = Color.BLACK),
                transform = t.copy(skewX = -0.15f),
            )
            PUNCH -> e.copy(
                preset = name,
                style = base.copy(fontId = font("cat:luckiest_guy"), textColor = Color.WHITE, outlineColor = Color.BLACK),
                transform = t.copy(skewX = 0f),
            )
            HORROR -> e.copy(
                preset = name,
                style = base.copy(fontId = font("cat:bangers"), textColor = 0xFF8B0000.toInt(), outlineColor = Color.BLACK, outerOutlineColor = Color.WHITE),
                transform = t.copy(skewX = 0.22f),
            )
            SOFT -> e.copy(
                preset = name,
                style = base.copy(fontId = font("cat:comic_neue"), bold = true, uppercase = false, textColor = Color.BLACK, outlineColor = Color.TRANSPARENT),
                transform = t.copy(skewX = 0f),
            )
            RUMBLE -> e.copy(
                preset = name,
                style = base.copy(fontId = font("cat:rubik_mono_one"), textColor = 0xFF333333.toInt(), outlineColor = Color.WHITE, letterSpacing = 0.15f),
                transform = t.copy(skewX = 0f),
            )
            MATCH -> {
                val fg = b.region?.fgColor ?: Color.BLACK
                val bg = b.region?.bgColor ?: Color.WHITE
                e.copy(
                    preset = name,
                    style = base.copy(fontId = null, textColor = fg or 0xFF000000.toInt(), outlineColor = bg or 0xFF000000.toInt()),
                    transform = t.copy(skewX = 0f),
                )
            }
        }
    }
}
