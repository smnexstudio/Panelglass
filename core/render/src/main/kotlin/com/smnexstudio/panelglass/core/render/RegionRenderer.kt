package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.smnexstudio.panelglass.core.model.BubbleFont
import com.smnexstudio.panelglass.core.model.FreeTextMode
import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.raster.PixelSource
import com.smnexstudio.panelglass.core.ocr.raster.Gray
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Erases and redraws one region, returning a small WEBP patch positioned in page-percentage space.
 * Paints are per-thread so the two render workers never share mutable state.
 */
@Singleton
class RegionRenderer @Inject constructor(
    private val eraser: TextEraser,
    private val encoder: PatchEncoder,
) {
    private class Paints {
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    }
    private val paints = ThreadLocal.withInitial { Paints() }

    /**
     * @param energy Sobel energy map of the page at 1/8 scale, used only for FREE placement; null skips placement.
     */
    /**
     * @param avoid Boxes belonging to other regions (their text and balloons): a widened layout keeps out of them.
     */
    fun render(page: PixelSource, region: TextRegion, translated: String, cfg: TranslateConfig, energy: Gray?, avoid: List<IntRect> = emptyList(), debug: ((String) -> Unit)? = null): Patch? {
        val text = translated.trim()
        if (text.isEmpty()) return null
        when (region.kind) {
            RegionKind.FREE, RegionKind.IN_SCENE -> if (cfg.freeTextMode == FreeTextMode.OFF) return null
            RegionKind.SFX -> if (cfg.sfxMode == SfxMode.SKIP) return null
            else -> Unit
        }
        val pad = max(8, (region.fontSizePx * 0.4f).toInt())
        var cropRect = region.bbox.inflate(pad).clamp(page.width, page.height)
        // An effect's label lies along its axis but is never drawn on its side, so a tall effect gets a wide label:
        // give it a square crop, or the label is cut at the crop edge.
        if (region.kind == RegionKind.SFX) {
            val half = (max(region.bbox.width, region.bbox.height) * SFX_CROP_HALF).toInt()
            val cx = region.bbox.centerX.toInt(); val cy = region.bbox.centerY.toInt()
            cropRect = IntRect(cx - half, cy - half, cx + half, cy + half).clamp(page.width, page.height)
        }
        if (cropRect.isEmpty) return null

        // Free text may drift toward emptier art; expand the crop to include the destination.
        var target = region.bbox
        // Balloon text lays out in the balloon, inset so it stays off the outline and out of the tail corners.
        val balloon = region.container?.takeIf { region.kind == RegionKind.ENCLOSED && it.width > region.bbox.width }
        if (balloon != null) {
            val inset = (minOf(balloon.width, balloon.height) * BALLOON_INSET).toInt()
            var t = balloon.inflate(-inset).union(region.bbox)
            // Joined balloons come as one wide box: keep the layout near its own text, not across the neighbour's.
            val maxW = (region.bbox.width * BALLOON_MAX_WIDTH_X).toInt()
            if (t.width > maxW) {
                val left = (region.bbox.centerX - maxW / 2f).toInt().coerceIn(t.left, t.right - maxW)
                t = IntRect(left, t.top, left + maxW, t.bottom)
            }
            target = t.clamp(page.width, page.height)
            cropRect = cropRect.union(target.inflate(pad)).clamp(page.width, page.height)
        }
        if ((region.kind == RegionKind.FREE) && energy != null) {
            target = FreeTextPlacer.place(energy, ENERGY_SCALE, region.bbox, region.fontSizePx.toInt(), page.width, page.height)
            cropRect = cropRect.union(target.inflate(pad)).clamp(page.width, page.height)
        }
        // A vertical column of free text is a poor box for a horizontal sentence: the fit ends at a few pixels.
        // Only then does the box widen, by the least that makes the text legible, and the mask grows with it.
        val widened = if (balloon == null && region.vertical && region.kind != RegionKind.SFX) legibleBox(region, text, cfg, page.width, page.height, avoid) else null
        if (widened != null) cropRect = cropRect.union(widened.inflate(pad)).clamp(page.width, page.height)
        // Should the eraser refuse, the fallback is a mask over the source text's own footprint.
        val maskBox = (widened?.let { region.bbox.union(it) } ?: region.bbox).inflate(MASK_PAD).clamp(page.width, page.height)

        val crop = page.crop(cropRect)
        val wantErase = when (region.kind) {
            RegionKind.ENCLOSED, RegionKind.CAPTION -> true
            RegionKind.FREE, RegionKind.IN_SCENE -> cfg.freeTextMode == FreeTextMode.ERASE
            RegionKind.SFX -> cfg.sfxMode == SfxMode.REPLACE
        }
        val erase = if (wantErase || region.kind == RegionKind.SFX) eraser.erase(crop, cropRect, region) else null
        val raster = if (erase != null && erase.erased && wantErase) erase.raster else crop
        val erased = erase?.erased == true && wantErase
        // Repaint even when the pixels could not be restored: cover the source text with an opaque panel.
        // (FreeTextMode.OVERLAY skips the inpaint attempt and goes straight to the panel.)
        val panel = !erased && region.kind != RegionKind.SFX
        debug?.invoke("render crop=$cropRect target=$target balloon=$balloon erase=${erase?.method} erased=$erased panel=$panel maskBox=$maskBox")

        // The patch is transparent wherever the page is unchanged: only erased pixels, masks and lettering are
        // opaque, so overlapping patches never paint one bubble's surroundings over its neighbour's translation.
        val pixels = IntArray(raster.width * raster.height)
        if (erased) for (i in pixels.indices) if (raster.argb[i] != crop.argb[i]) pixels[i] = raster.argb[i]
        val bmp = Bitmap.createBitmap(pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
        try {
            val canvas = Canvas(bmp)
            // Free text moves toward calmer art only when it is drawn over the untouched art; once its footprint is
            // erased or masked, that is where it goes (moved away, it sat over the art above a wiped strip).
            val placed = if (balloon == null && (panel || erased)) region.bbox else target
            val local = (widened ?: placed).offset(-cropRect.left, -cropRect.top)
            // The translation takes the source text's place, at whatever size fits there: the art around it is untouched.
            if (panel) drawMask(canvas, maskBox.offset(-cropRect.left, -cropRect.top), region)
            val outline = !erased && !panel
            when (region.kind) {
                RegionKind.ENCLOSED, RegionKind.CAPTION -> drawFitted(canvas, local, text, region, cfg, outline = outline, onPanel = panel, debug = debug)
                RegionKind.FREE -> drawFitted(canvas, local, text, region, cfg, outline = outline, onPanel = panel, debug = debug)
                RegionKind.IN_SCENE -> {
                    canvas.save(); canvas.rotate(region.angle, local.centerX, local.centerY)
                    drawFitted(canvas, local, text, region, cfg, outline = outline, onPanel = panel, debug = debug); canvas.restore()
                }
                RegionKind.SFX -> if (cfg.sfxMode == SfxMode.GLOSS) drawGloss(canvas, local, text, region)
                else drawSfx(canvas, local, text, region, erase?.glyphMask)
            }
            val webp = encoder.encode(bmp, cfg.patchQuality)
            return Patch(
                xPct = cropRect.left * 100f / page.width, yPct = cropRect.top * 100f / page.height,
                wPct = cropRect.width * 100f / page.width, hPct = cropRect.height * 100f / page.height,
                webp = webp,
            )
        } finally { bmp.recycle() }
    }

    // ---- dialogue / captions / free text ---------------------------------------------------

    /**
     * Flat mask over the source text when its pixels could not be restored: the balloon's own colour inside a
     * balloon or caption box, plain white on open art. No border, no widening — the footprint of the original
     * lettering is all that changes.
     */
    private fun drawMask(canvas: Canvas, box: IntRect, region: TextRegion) {
        val p = paints.get()!!
        p.fill.color = maskColor(region)
        val r = (region.fontSizePx * 0.15f).coerceIn(1f, 6f)
        canvas.drawRoundRect(android.graphics.RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()), r, r, p.fill)
    }

    private fun maskColor(region: TextRegion): Int = when (region.kind) {
        RegionKind.ENCLOSED, RegionKind.CAPTION -> region.bgColor or 0xFF000000.toInt()
        else -> Color.WHITE
    }

    /** Ink that reads on the mask: the region's own when it contrasts, else black on a light mask, white on a dark one. */
    private fun panelInk(region: TextRegion): Int = panelInk(region.fgColor, maskColor(region))

    private fun drawFitted(canvas: Canvas, box: IntRect, text: String, region: TextRegion, cfg: TranslateConfig, outline: Boolean, onPanel: Boolean = false, debug: ((String) -> Unit)? = null) {
        val p = paints.get()!!
        val tp = p.text
        tp.typeface = Fonts.bubble(cfg.bubbleFont)
        tp.color = when { onPanel -> panelInk(region); outline -> darkOrLight(region.fgColor); else -> region.fgColor }
        val inset = max(1, (min(box.width, box.height) * 0.04f).toInt())
        val inner = box.inflate(-inset)
        // Latin lettering reads smaller than kanji of the same em; allow the fit to grow past the source size.
        val maxSize = max(10f, region.fontSizePx * (if (region.container != null) 1.3f else 1.2f))
        val why = if (debug != null) StringBuilder() else null
        val layout = fit(text, inner.width.coerceAtLeast(8), inner.height.coerceAtLeast(8), tp, maxSize, min(MIN_LEGIBLE_PX, maxSize), why)
        debug?.invoke("fit box=${inner.width}x${inner.height} max=${maxSize.toInt()} size=${tp.textSize.toInt()} lines=${layout.lineCount} tried:$why")
        val x = inner.left + (inner.width - layout.width) / 2f
        val y = inner.top + (inner.height - layout.height) / 2f
        canvas.save(); canvas.translate(x, y)
        if (outline) {
            val sp = p.stroke
            sp.typeface = tp.typeface; sp.textSize = tp.textSize
            sp.strokeWidth = (tp.textSize * 0.16f).coerceIn(3f, 4.5f)
            sp.color = if (Raster.lum(tp.color) < 128) Color.WHITE else Color.BLACK
            StaticLayout.Builder.obtain(text, 0, text.length, sp, layout.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, LINE_SPACING).setIncludePad(false).build().draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * The source box when the translation fits it at a legible size; otherwise the narrowest box, centred on the
     * source and no wider than [MAX_WIDEN] times it, in which the text lays out at that size within the source
     * height. Kept inside the page.
     */
    private fun legibleBox(region: TextRegion, text: String, cfg: TranslateConfig, pageW: Int, pageH: Int, avoid: List<IntRect>): IntRect? {
        val box = region.bbox
        val tp = paints.get()!!.text
        tp.typeface = Fonts.bubble(cfg.bubbleFont)
        val inset = max(1, (min(box.width, box.height) * 0.04f).toInt())
        val minLegible = max(MIN_LEGIBLE_PX, region.fontSizePx * MIN_LEGIBLE_OF_SOURCE)
        val w0 = box.width - 2 * inset; val h0 = box.height - 2 * inset
        tp.textSize = minLegible
        if (fitsAt(text, w0, h0, tp)) return null
        val maxW = (box.width * MAX_WIDEN).toInt().coerceAtMost(pageW)
        var w = box.width
        val step = max(4, box.width / 8)
        while (w < maxW) {
            w = min(maxW, w + step)
            if (fitsAt(text, w - 2 * inset, h0, tp)) break
        }
        var left = box.centerX.toInt() - w / 2
        left = left.coerceIn(0, max(0, pageW - w))
        var out = IntRect(left, box.top, min(pageW, left + w), box.bottom)
        // Never over a neighbour: trim the widened box back to the nearest other region on each side.
        for (o in avoid) {
            if (!o.intersects(out) || o.intersects(box)) continue
            if (o.right <= box.left) out = out.copy(left = max(out.left, o.right + MASK_PAD + 1))
            else if (o.left >= box.right) out = out.copy(right = min(out.right, o.left - MASK_PAD - 1))
        }
        return if (out.width > box.width) out else null
    }

    /**
     * Largest text size whose wrapped layout fits [w]×[h] with every word whole on its line; falls back to [minSize].
     *
     * Both tests only get harder as the size grows, so the binary search is sound. (Reading line widths and break
     * positions back from the layout was not: `getLineWidth` over-reported after wrapping and a break beside "..." or
     * a quote looked like a split word, so sizes failed at random and the search settled near the minimum.)
     */
    private fun fit(text: String, w: Int, h: Int, tp: TextPaint, maxSize: Float, minSize: Float, why: StringBuilder? = null): StaticLayout {
        var lo = minSize; var hi = maxSize
        var best: StaticLayout? = null
        repeat(8) {
            val mid = (lo + hi) / 2f
            tp.textSize = mid
            val l = layout(text, w, tp)
            val tall = l.height > h
            val wide = longestWord(text, tp) > w
            val fits = !tall && !wide
            why?.append(" ")?.append(mid.toInt())?.append(if (fits) "ok" else (if (tall) "T" else "") + (if (wide) "W" else ""))
            if (fits) { best = l; lo = mid } else hi = mid
        }
        if (best == null) { tp.textSize = minSize; best = layout(text, w, tp) } else tp.textSize = lo
        return best!!
    }

    /** Whether [text] at the paint's size lays out in [w]×[h] with no word split across lines. */
    private fun fitsAt(text: String, w: Int, h: Int, tp: TextPaint): Boolean =
        longestWord(text, tp) <= w && layout(text, w, tp).height <= h

    /** Width of the widest unbreakable run of [text] at the paint's current size. */
    private fun longestWord(text: String, tp: TextPaint): Float = longestRun(text) { s, e -> tp.measureText(text, s, e) }

    private fun layout(text: String, w: Int, tp: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, tp, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    // ---- sound effects ---------------------------------------------------------------------

    /** Small outlined label at the bottom edge of the effect: readable without pretending to be lettering. */
    private fun drawGloss(canvas: Canvas, box: IntRect, text: String, region: TextRegion) {
        val p = paints.get()!!
        val tp = p.text; val sp = p.stroke
        tp.typeface = Fonts.sfx(); sp.typeface = tp.typeface
        tp.textSize = max(11f, region.fontSizePx * 0.35f); sp.textSize = tp.textSize
        tp.color = Color.BLACK; sp.color = Color.WHITE; sp.strokeWidth = 3f
        tp.textAlign = Paint.Align.CENTER; sp.textAlign = Paint.Align.CENTER
        val label = text.uppercase()
        val y = box.bottom.toFloat() - tp.descent()
        canvas.drawText(label, box.centerX, y, sp)
        canvas.drawText(label, box.centerX, y, tp)
        tp.textAlign = Paint.Align.LEFT; sp.textAlign = Paint.Align.LEFT
    }

    /**
     * Overlay the English effect on the original lettering. Geometry comes from PCA over the glyph
     * mask: rotation from the principal axis, size from the extents, area matched to the original.
     */
    private fun drawSfx(canvas: Canvas, box: IntRect, text: String, region: TextRegion, mask: GlyphMask?) {
        val p = paints.get()!!
        val tp = p.text; val sp = p.stroke
        tp.typeface = Fonts.sfx(); sp.typeface = tp.typeface
        tp.textSkewX = -0.12f; sp.textSkewX = -0.12f
        tp.color = if (Raster.lum(region.fgColor) < 128) Color.BLACK else Color.WHITE
        sp.color = if (tp.color == Color.BLACK) Color.WHITE else Color.BLACK
        tp.textAlign = Paint.Align.CENTER; sp.textAlign = Paint.Align.CENTER
        val label = text.uppercase()

        val geom = mask?.let { MaskGeometry.of(it) }
        val cx = geom?.cx ?: box.centerX; val cy = geom?.cy ?: box.centerY
        var angle = geom?.angleDeg ?: (if (region.vertical) 90f else 0f)
        if (abs(angle) > 80f) angle = if (angle > 0) angle - 90f else angle + 90f // never lay text on its side
        val length = geom?.length ?: max(box.width, box.height).toFloat()
        val thickness = geom?.thickness ?: min(box.width, box.height).toFloat()

        // Start from the cross-axis extent and scale so the drawn glyph area approximates the mask area.
        tp.textSize = thickness.coerceIn(12f, 400f)
        var measured = tp.measureText(label)
        if (measured > length * 1.15f) tp.textSize *= length * 1.15f / measured
        if (geom != null) {
            measured = tp.measureText(label)
            val drawnArea = measured * tp.textSize * 0.55f
            val s = sqrt(geom.area / max(1f, drawnArea)).coerceIn(0.7f, 1.4f)
            tp.textSize *= s
        }
        sp.textSize = tp.textSize
        sp.strokeWidth = (tp.textSize * 0.14f).coerceIn(3f, 8f)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        val baseline = cy - (tp.ascent() + tp.descent()) / 2f
        if (geom != null && geom.isCurved) {
            val half = tp.measureText(label) / 2f + tp.textSize * 0.3f
            val bend = geom.curvature * half * half
            val path = Path().apply { moveTo(cx - half, baseline + bend); quadTo(cx, baseline - bend, cx + half, baseline + bend) }
            canvas.drawTextOnPath(label, path, 0f, 0f, sp)
            canvas.drawTextOnPath(label, path, 0f, 0f, tp)
        } else {
            canvas.drawText(label, cx, baseline, sp)
            canvas.drawText(label, cx, baseline, tp)
        }
        canvas.restore()
        tp.textSkewX = 0f; sp.textSkewX = 0f
        tp.textAlign = Paint.Align.LEFT; sp.textAlign = Paint.Align.LEFT
    }

    private fun darkOrLight(fg: Int) = if (Raster.lum(fg) < 128) Color.BLACK else Color.WHITE

    companion object {
        /**
         * [fg] when it stands out from [bg]; otherwise the opposite of [bg]. (Not [darkOrLight] of the background:
         * that matches its tone, and a light-grey caption on a white mask came out white on white — a blank bar.)
         */
        internal fun panelInk(fg: Int, bg: Int): Int =
            if (abs(Raster.lum(fg) - Raster.lum(bg)) >= 100) fg else if (Raster.lum(bg) < 128) Color.WHITE else Color.BLACK

        /**
         * The widest run a line break may not split, by [width] of `[start, end)`: words between whitespace, and each
         * kanji / kana / fullwidth mark alone (Japanese and Chinese break between any two of them). A hyphen ends a
         * run, since the layout may break after it.
         */
        internal fun longestRun(text: String, width: (Int, Int) -> Float): Float {
            var best = 0f
            var start = -1
            for (i in text.indices) {
                val c = text[i]
                val cjk = breaksAnywhere(c)
                if (c.isWhitespace() || cjk) {
                    if (start >= 0) { best = max(best, width(start, i)); start = -1 }
                    if (cjk) best = max(best, width(i, i + 1))
                } else {
                    if (start < 0) start = i
                    if (c == '-') { best = max(best, width(start, i + 1)); start = -1 }
                }
            }
            if (start >= 0) best = max(best, width(start, text.length))
            return best
        }

        private fun breaksAnywhere(c: Char): Boolean {
            val b = Character.UnicodeBlock.of(c) ?: return false
            return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA ||
                b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        }

        const val ENERGY_SCALE = 8
        const val LINE_SPACING = 0.95f
        /** Pixels the mask extends past the source text box, to cover anti-aliased edges. */
        const val MASK_PAD = 2
        /** Fraction of a balloon's short side kept clear inside its box: balloons are round, boxes are not. */
        const val BALLOON_INSET = 0.14f
        /** Widest the balloon layout may get, as a multiple of the source text box (vertical Japanese is narrow). */
        const val BALLOON_MAX_WIDTH_X = 3.5f
        /** A vertical column widens rather than letter the translation below this fraction of the source glyph size (nor [MIN_LEGIBLE_PX]). */
        const val MIN_LEGIBLE_OF_SOURCE = 0.55f
        const val MIN_LEGIBLE_PX = 12f
        /** How far a vertical free-text column may widen for a horizontal translation. */
        const val MAX_WIDEN = 2.5f
        /** Half-side of an effect's crop as a multiple of its longer side. */
        const val SFX_CROP_HALF = 0.75f
    }
}

object Fonts {
    private val cache = HashMap<String, Typeface>()
    private var ctx: android.content.Context? = null

    /** Call once from the Application or renderer constructor so resource fonts are available. */
    fun init(context: android.content.Context) { ctx = context.applicationContext }

    fun bubble(font: BubbleFont): Typeface = synchronized(cache) {
        cache.getOrPut(font.name) {
            when (font) {
                BubbleFont.PLUS_JAKARTA_SANS -> loadFont(com.smnexstudio.panelglass.core.render.R.font.plus_jakarta_sans)
                    ?: Typeface.create("sans-serif", Typeface.BOLD)
                BubbleFont.COMING_SOON -> loadFont(com.smnexstudio.panelglass.core.render.R.font.coming_soon)
                    ?: Typeface.create("casual", Typeface.BOLD)
                BubbleFont.LUCKIEST_GUY -> loadFont(com.smnexstudio.panelglass.core.render.R.font.luckiest_guy)
                    ?: Typeface.create("sans-serif-black", Typeface.BOLD)
            }
        }
    }
    fun sfx(): Typeface = synchronized(cache) { cache.getOrPut("sfx") { Typeface.create("sans-serif-black", Typeface.BOLD) } }

    private fun loadFont(resId: Int): Typeface? =
        ctx?.let { runCatching { androidx.core.content.res.ResourcesCompat.getFont(it, resId) }.getOrNull() }
}

