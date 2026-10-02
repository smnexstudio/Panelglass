package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.LocaleList
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * The Studio's lettering (docs/STUDIO_PLAN.md › Styled rendering): each bubble's translation drawn into its polygon on
 * the clean layer. Per bubble: the polygon is filled with [BubbleStyle.fillColor] when it is not transparent, then the
 * text is fitted ([TextFit], the reader's two-test search) into the largest centred rectangle inside the polygon
 * ([PolygonFit.innerRect]), or set at a fixed size, and drawn clipped to the polygon. The reader's renderer is not used
 * or changed. [scale] maps page pixels to the canvas (a preview drawn at a smaller size).
 */
@Singleton
class StudioRenderer @Inject constructor() {
    private class Paints {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    }
    private val paints = ThreadLocal.withInitial { Paints() }

    /** A new bitmap: [clean] with every bubble lettered. [clean] is not changed. */
    fun render(clean: Bitmap, bubbles: List<Bubble>, tgt: Lang, scale: Float = 1f): Bitmap {
        val out = clean.copy(Bitmap.Config.ARGB_8888, true)
        draw(Canvas(out), bubbles, tgt, scale, art = clean)
        return out
    }

    /**
     * Letters [bubbles] on [canvas]. [art] is the clean layer under them (the canvas's own bitmap is fine: every text box
     * is measured before anything is drawn); with it, a balloon still shaped as its detected box gets its text box from
     * the balloon's paper ([BalloonFit]), not from the box, whose corners an oval does not reach.
     */
    fun draw(canvas: Canvas, bubbles: List<Bubble>, tgt: Lang, scale: Float = 1f, art: Bitmap? = null) {
        val boxes = if (art != null) bubbles.associate { it.id to textBox(it, art, scale) } else emptyMap()
        for (b in bubbles) drawBubble(canvas, b, tgt, scale, boxes[b.id])
    }

    /**
     * The text box of [b] in page pixels: inside the balloon's paper when [b] is a speech balloon still shaped as its
     * detected rectangle and the paper is found; else the largest rectangle inside its polygon. A shape the user drew
     * is theirs and is used as drawn.
     */
    fun textBox(b: Bubble, art: Bitmap?, scale: Float = 1f): FRect? {
        val inner = PolygonFit.innerRect(b.polygon) ?: return null
        // Text with no fill under it sits on the balloon's paper, so it is kept on the paper the clean layer shows: a
        // balloon cut by a panel border ends at that line even when its shape (the detected box, or a traced outline
        // that reached the line) does not. With a fill, the fill is the background and the shape is used as it is.
        if (art == null || b.kind != RegionKind.ENCLOSED || Color.alpha(b.style.fillColor) > 0) return inner
        val bx = PolygonFit.bounds(b.polygon)
        val l = (bx.left * scale).toInt().coerceIn(0, art.width - 1)
        val t = (bx.top * scale).toInt().coerceIn(0, art.height - 1)
        val r = (bx.right * scale).toInt().coerceIn(l + 1, art.width)
        val bt = (bx.bottom * scale).toInt().coerceIn(t + 1, art.height)
        val w = r - l
        val h = bt - t
        if (w < 8 || h < 8) return inner
        val px = IntArray(w * h)
        runCatching { art.getPixels(px, 0, w, l, t, w, h) }.onFailure { return inner }
        val paper = BalloonFit.interior(px, w, h) ?: return inner
        // Back to page pixels; never larger than the polygon's own rectangle.
        val fit = FRect(paper.left / scale + l / scale, paper.top / scale + t / scale, paper.right / scale + l / scale, paper.bottom / scale + t / scale)
        val out = FRect(maxOf(fit.left, inner.left), maxOf(fit.top, inner.top), minOf(fit.right, inner.right), minOf(fit.bottom, inner.bottom))
        return if (out.width >= 4f && out.height >= 4f) out else inner
    }



    /** The bubble's own border along its shape, over everything inside it (outside the clip, so the line is whole). */
    private fun drawBorder(canvas: Canvas, path: Path, style: com.smnexstudio.panelglass.core.model.BubbleStyle, scale: Float) {
        if (style.borderWidthPx <= 0f) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.style = android.graphics.Paint.Style.STROKE
            strokeWidth = style.borderWidthPx * scale
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = style.borderColor ?: Color.BLACK
        }
        canvas.drawPath(path, paint)
    }

    /**
     * The bubble's shape as a path in canvas pixels: its polygon, each corner replaced by a curve when [roundness] > 0.
     * A corner's curve starts and ends [roundness] × half the shorter of its two edges away from it, so neighbouring
     * curves never overlap, at any size.
     */
    internal fun shapePath(poly: List<com.smnexstudio.panelglass.core.model.Pt>, roundness: Float, scale: Float): Path {
        val path = Path()
        val n = poly.size
        val r = roundness.coerceIn(0f, 1f)
        if (r <= 0f || n < 3) {
            poly.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.x * scale, pt.y * scale) else path.lineTo(pt.x * scale, pt.y * scale) }
            path.close()
            return path
        }
        for (i in 0 until n) {
            val prev = poly[(i - 1 + n) % n]
            val cur = poly[i]
            val next = poly[(i + 1) % n]
            val dPrev = kotlin.math.hypot(prev.x - cur.x, prev.y - cur.y)
            val dNext = kotlin.math.hypot(next.x - cur.x, next.y - cur.y)
            val cut = r * min(dPrev, dNext) / 2f
            val ax = cur.x + if (dPrev > 0f) (prev.x - cur.x) / dPrev * cut else 0f
            val ay = cur.y + if (dPrev > 0f) (prev.y - cur.y) / dPrev * cut else 0f
            val bx = cur.x + if (dNext > 0f) (next.x - cur.x) / dNext * cut else 0f
            val by = cur.y + if (dNext > 0f) (next.y - cur.y) / dNext * cut else 0f
            if (i == 0) path.moveTo(ax * scale, ay * scale) else path.lineTo(ax * scale, ay * scale)
            path.quadTo(cur.x * scale, cur.y * scale, bx * scale, by * scale)
        }
        path.close()
        return path
    }

    /** Whether [b] is lettered at all: dismissed bubbles and empty translations are not. */
    fun letters(b: Bubble): Boolean = !b.ignored && b.polygon.size >= 3 && b.translatedText.isNotBlank()

    fun drawBubble(canvas: Canvas, b: Bubble, tgt: Lang, scale: Float = 1f, textBox: FRect? = null) {
        // Sound effects have their own modes and transform, and are not clipped to their polygon.
        if (SfxRenderer.handles(b)) return SfxRenderer.draw(canvas, b, tgt, scale)
        if (!letters(b)) return
        val p = paints.get()!!
        val style = b.style
        val path = shapePath(b.polygon, style.cornerRoundness, scale)
        canvas.save()
        canvas.clipPath(path)
        if (Color.alpha(style.fillColor) > 0) {
            p.fill.color = style.fillColor
            canvas.drawPath(path, p.fill)
        }
        val box = textBox ?: PolygonFit.innerRect(b.polygon) ?: run { canvas.restore(); drawBorder(canvas, path, style, scale); return }
        val w = (box.width * scale).toInt().coerceAtLeast(4)
        val h = (box.height * scale).toInt().coerceAtLeast(4)
        val text = b.translatedText.trim().let { if (style.uppercase) it.uppercase(Locale.forLanguageTag(tgt.code)) else it }
        val tp = p.text
        tp.typeface = StudioFonts.typeface(style.fontId, tgt, style.bold, style.italic)
        // The target language picks the glyph shapes of characters several scripts share (直 in Japanese vs Chinese).
        tp.textLocales = LocaleList(Locale.forLanguageTag(tgt.code))
        tp.letterSpacing = style.letterSpacing
        tp.color = style.textColor ?: inkFor(b)
        val rtl = tgt == Lang.AR
        val layout = if (style.sizePx != null) {
            tp.textSize = (style.sizePx!! * scale).coerceAtLeast(1f)
            TextFit.layout(text, w, tp, rtl = rtl)
        } else {
            val glyph = b.region?.fontSizePx ?: (min(box.width, box.height) / 4f)
            val maxSize = (glyph * MAX_OF_SOURCE).coerceIn(MIN_MAX_PX, MAX_PX) * scale
            TextFit.fit(text, w, h, tp, maxSize, min(MIN_PX * scale, maxSize), rtl = rtl)
        }
        val x = box.left * scale + (w - layout.width) / 2f
        val y = box.top * scale + (h - layout.height) / 2f
        canvas.translate(x, y)
        val outline = style.outlineWidthPx.takeIf { it > 0f } ?: if (b.kind == RegionKind.SFX) tp.textSize / scale * 0.14f else 0f
        if (outline > 0f) {
            val sp = p.stroke
            sp.typeface = tp.typeface; sp.textSize = tp.textSize; sp.letterSpacing = tp.letterSpacing; sp.textLocales = tp.textLocales
            sp.strokeWidth = outline * scale * 2f // the stroke is centred on the glyph edge: half of it shows
            sp.color = style.outlineColor ?: if (Raster.lum(tp.color) < 128) Color.WHITE else Color.BLACK
            StaticLayout.Builder.obtain(text, 0, text.length, sp, layout.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, TextFit.LINE_SPACING).setIncludePad(false)
                .build().draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
        drawBorder(canvas, path, style, scale)
        tp.letterSpacing = 0f
    }

    /** Ink that reads on what is under the text: the bubble's fill if it has one, else the balloon's own colour. */
    fun inkFor(b: Bubble): Int {
        val fill = b.style.fillColor
        val bg = if (Color.alpha(fill) >= 128) fill else (b.region?.bgColor ?: Color.WHITE)
        return RegionRenderer.panelInk(b.region?.fgColor ?: Color.BLACK, bg)
    }

    companion object {
        /** Auto size: never above this multiple of the source glyph (a short word should not fill a huge balloon). */
        const val MAX_OF_SOURCE = 1.4f
        const val MIN_MAX_PX = 14f
        const val MAX_PX = 140f
        /** The smallest auto size; a fixed size can go lower (8–120 px). */
        const val MIN_PX = 10f
    }
}
