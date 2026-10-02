package com.smnexstudio.panelglass.core.render

import android.graphics.Bitmap
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.GlyphMask
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.ocr.Inpainter
import com.smnexstudio.panelglass.core.ocr.raster.Raster
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Studio's clean layer (docs/STUDIO_PLAN.md › Cleanup): the original page with the source text removed, then the
 * user's brush strokes applied. Rebuilt from scratch every time (original + bubbles + strokes), so it is never edited
 * in place and an undo only has to change the inputs.
 *
 * Per bubble, the reader's [TextEraser] runs on a crop around it (flood fill inside a balloon, halo fill or inpaint on
 * art) and only pixels inside the bubble's polygon are taken. Where the eraser refuses (busy art it would smear), the
 * text's footprint is masked flat inside the polygon, as the reader does. A brush stroke then inpaints what it covers
 * ([BrushMode.CLEAN]) or brings the original back ([BrushMode.RESTORE]); later strokes win. LaMa (phase 4b) will
 * replace the inpaint where it is installed.
 */
@Singleton
class PageCleaner @Inject constructor(private val eraser: TextEraser, private val inpainter: Inpainter) {

    fun clean(original: Bitmap, bubbles: List<Bubble>, strokes: List<BrushStroke>): Bitmap {
        val w = original.width
        val h = original.height
        val px = IntArray(w * h)
        original.getPixels(px, 0, w, 0, 0, w, h)
        val out = clean(Raster(w, h, px), bubbles, strokes)
        return Bitmap.createBitmap(out.argb, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The same on plain pixels: what the unit tests run. [original] is not changed. */
    fun clean(original: Raster, bubbles: List<Bubble>, strokes: List<BrushStroke>): Raster {
        val out = original.copy()
        for (b in bubbles) if (cleans(b)) cleanBubble(out, b)
        for (s in strokes) applyStroke(out, original, s)
        return out
    }

    /** Dismissed bubbles and sound effects not set to Replace keep their original pixels. */
    fun cleans(b: Bubble): Boolean =
        !b.ignored && b.polygon.size >= 3 && (b.kind != RegionKind.SFX || b.sfx?.mode == SfxEditMode.REPLACE)

    private fun cleanBubble(out: Raster, b: Bubble) {
        val poly = b.polygon
        val bounds = PolygonFit.bounds(poly)
        val shape = IntRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt())
            .clamp(out.width, out.height)
        if (shape.isEmpty) return
        // The detected region keeps the colours and glyph size the eraser needs; a drawn bubble gets one from its shape.
        val region = (b.region ?: TextRegion(bbox = shape, kind = b.kind, text = b.sourceText, container = shape))
            .let { r -> r.copy(bbox = (r.bbox.intersect(shape) ?: shape)) }
        val pad = max(8, (region.fontSizePx * 0.4f).roundToInt())
        val cropRect = shape.union(region.bbox).inflate(pad).clamp(out.width, out.height)
        val crop = RasterCrop.of(out, cropRect)
        val result = eraser.erase(crop, cropRect, region)
        // A plain balloon's flat fill is exact; anything over art (free text, a replaced effect, a halo or ray fill)
        // goes to LaMa when it is installed, on exactly the pixels the eraser decided were text.
        // A sound effect's lettering carries thick outlines the glyph test does not count: its mask grows with it.
        val grow = if (b.kind == RegionKind.SFX) max(LAMA_GROW, (region.fontSizePx * SFX_GROW_OF_GLYPH).roundToInt()) else LAMA_GROW
        if (inpainter.available && result.method != EraseResult.Method.CONTAINER_FILL && lama(out, crop, cropRect, poly, result, grow)) return
        if (result.erased) {
            forEachInside(poly, cropRect) { x, y -> out.set(x, y, result.raster[x - cropRect.left, y - cropRect.top]) }
        } else {
            // The flat mask: the balloon's own colour in a balloon or caption, white on art; never outside the shape.
            val color = when (b.kind) {
                RegionKind.ENCLOSED, RegionKind.CAPTION -> region.bgColor or 0xFF000000.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            val box = region.bbox.inflate(RegionRenderer.MASK_PAD).clamp(out.width, out.height)
            forEachInside(poly, box) { x, y -> out.set(x, y, color) }
        }
    }

    /**
     * Fills with LaMa what the eraser changed (or, when it refused, the text box), grown by [growBy] pixels and kept
     * inside the bubble's polygon. False when there was nothing to fill or the model did not run.
     */
    private fun lama(out: Raster, crop: Raster, cropRect: IntRect, poly: List<com.smnexstudio.panelglass.core.model.Pt>, result: EraseResult, growBy: Int): Boolean {
        val mask = GlyphMask.empty(0, 0, crop.width, crop.height)
        var any = false
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            val inside = PolygonFit.contains(poly, cropRect.left + x + 0.5f, cropRect.top + y + 0.5f)
            if (inside && (!result.erased || result.raster[x, y] != crop[x, y])) { mask.set(x, y, true); any = true }
        }
        if (!any) return false
        val grown = grow(mask, growBy)
        // Grown pixels stay inside the polygon too.
        for (y in 0 until crop.height) for (x in 0 until crop.width) {
            if (grown[x, y] && !PolygonFit.contains(poly, cropRect.left + x + 0.5f, cropRect.top + y + 0.5f)) grown.set(x, y, false)
        }
        val work = crop.copy()
        if (!inpainter.inpaint(work, grown)) return false
        for (y in 0 until crop.height) for (x in 0 until crop.width) if (grown[x, y]) out.set(cropRect.left + x, cropRect.top + y, work[x, y])
        return true
    }

    /** [m] dilated by [r] pixels (a square neighbourhood). */
    private fun grow(m: GlyphMask, r: Int): GlyphMask {
        val g = GlyphMask.empty(0, 0, m.width, m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) {
            for (dy in -r..r) for (dx in -r..r) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until m.width && ny in 0 until m.height) g.set(nx, ny, true)
            }
        }
        return g
    }

    /** Calls [f] for every pixel centre of [within] that lies inside [poly]. */
    private inline fun forEachInside(poly: List<com.smnexstudio.panelglass.core.model.Pt>, within: IntRect, f: (Int, Int) -> Unit) {
        for (y in within.top until within.bottom) for (x in within.left until within.right) {
            if (PolygonFit.contains(poly, x + 0.5f, y + 0.5f)) f(x, y)
        }
    }

    private fun applyStroke(out: Raster, original: Raster, s: BrushStroke) {
        val mask = StrokeMask.of(s, out.width, out.height) ?: return
        val area = mask.bounds
        when (s.mode) {
            BrushMode.RESTORE -> {
                for (y in 0 until mask.height) for (x in 0 until mask.width) {
                    if (mask[x, y]) out.set(area.left + x, area.top + y, original[area.left + x, area.top + y])
                }
            }
            BrushMode.CLEAN -> {
                // Inpaint a crop around the stroke (rays need unmasked pixels to reach), then write the stroke back.
                val reach = INPAINT_REACH
                val cropRect = area.inflate(reach).clamp(out.width, out.height)
                val crop = RasterCrop.of(out, cropRect)
                val local = GlyphMask.empty(0, 0, crop.width, crop.height)
                for (y in 0 until mask.height) for (x in 0 until mask.width) {
                    if (mask[x, y]) local.set(area.left + x - cropRect.left, area.top + y - cropRect.top, true)
                }
                // LaMa where it is installed (it sees the art around the stroke); the ray fill otherwise.
                if (!inpainter.inpaint(crop, local)) RayInpainter.inpaint(crop, local, reach)
                for (y in 0 until mask.height) for (x in 0 until mask.width) {
                    if (mask[x, y]) out.set(area.left + x, area.top + y, crop[area.left + x - cropRect.left, area.top + y - cropRect.top])
                }
            }
        }
    }

    companion object {
        /** How far an inpaint ray reaches for a pixel to copy (page pixels). */
        const val INPAINT_REACH = 64
        /** Pixels LaMa's mask reaches past the text the eraser found (anti-aliased edges, outlines). */
        const val LAMA_GROW = 3
        /** A sound effect's mask grows by this share of its glyph size (its outline is about that thick). */
        const val SFX_GROW_OF_GLYPH = 0.2f
    }
}

/** A brush stroke as a bit mask: discs of the stroke's radius stamped along its polyline. Pure. */
object StrokeMask {
    fun of(s: BrushStroke, pageW: Int, pageH: Int): GlyphMask? {
        if (s.points.isEmpty() || s.radius <= 0f) return null
        val r = s.radius
        val left = max(0, floor(s.points.minOf { it.x } - r).toInt())
        val top = max(0, floor(s.points.minOf { it.y } - r).toInt())
        val right = min(pageW, ceil(s.points.maxOf { it.x } + r).toInt() + 1)
        val bottom = min(pageH, ceil(s.points.maxOf { it.y } + r).toInt() + 1)
        if (right <= left || bottom <= top) return null
        val mask = GlyphMask.empty(left, top, right - left, bottom - top)
        fun stamp(cx: Float, cy: Float) {
            val x0 = max(left, floor(cx - r).toInt()); val x1 = min(right - 1, ceil(cx + r).toInt())
            val y0 = max(top, floor(cy - r).toInt()); val y1 = min(bottom - 1, ceil(cy + r).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                if (hypot(x + 0.5f - cx, y + 0.5f - cy) <= r) mask.set(x - left, y - top, true)
            }
        }
        stamp(s.points[0].x, s.points[0].y)
        for (i in 1 until s.points.size) {
            val a = s.points[i - 1]
            val b = s.points[i]
            val steps = max(1, (hypot(b.x - a.x, b.y - a.y) / max(1f, r / 2f)).toInt())
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                stamp(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return mask
    }
}

/** A copy of [rect] of a raster, in the crop's own coordinates. */
internal object RasterCrop {
    fun of(r: Raster, rect: IntRect): Raster {
        val out = IntArray(rect.width * rect.height)
        for (y in 0 until rect.height) System.arraycopy(r.argb, (rect.top + y) * r.width + rect.left, out, y * rect.width, rect.width)
        return Raster(rect.width, rect.height, out)
    }
}
