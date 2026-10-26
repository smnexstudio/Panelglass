package com.smnexstudio.panelglass.feature.browser

import com.smnexstudio.panelglass.core.model.IntRect
import kotlin.math.abs

/**
 * The one place where a bubble's geometry is turned into numbers.
 *
 * Four coordinate spaces are in play, and mixing them is what made a restored translation come back the wrong
 * size and slightly to the left of where it was:
 *
 * | space | what it is | where it comes from |
 * |---|---|---|
 * | **percentage** | a fraction of the snapshot's own pixels | the pipeline's [com.smnexstudio.panelglass.core.model.Patch] |
 * | **view pixel** | what the reader sees on the phone | the capture's bitmap |
 * | **page CSS** | the WebView's content, zoom-independent | scroll offset ÷ scale |
 * | **normalized image** | a fraction of the image's own size | the image's art box |
 *
 * A patch is a fraction of the *snapshot*, and a snapshot is a fraction of the *viewport*, so a patch's place on
 * the page is only known when the snapshot's size, the page's scroll offset and the page's zoom are all of the
 * *same* capture. That is what [CaptureGeometry] carries: one immutable snapshot of those numbers, taken once,
 * used by every boundary.
 *
 * The rule the whole feature rests on: **percentages are never CSS pixels.** A percentage needs the viewport's
 * size to become a length, and the scale to become a position.
 */
object Geometry {

    /**
     * The numbers of one capture, in one place.
     *
     * They belong to a single snapshot of the page: the viewport's size, the scroll offset and the zoom at that
     * moment. Mixing them with the numbers of another moment is what produced the drift.
     */
    data class CaptureGeometry(
        /** The snapshot's width in view pixels. */
        val viewportWidthPx: Int,
        /** The snapshot's height in view pixels. */
        val viewportHeightPx: Int,
        /** The page's scroll offset at the capture, in page (content) pixels. */
        val scrollXPx: Int,
        /** The page's scroll offset at the capture, in page (content) pixels. */
        val scrollYPx: Int,
        /** The WebView's zoom at the capture. */
        val scale: Float,
    ) {
        /** The zoom is a real number: 1 is the page's own size, 2.6 is a pinch. */
        val usable: Boolean
            get() = scale.isFinite() && scale > 0f &&
                viewportWidthPx > 0 && viewportHeightPx > 0 &&
                scrollXPx.toLong() == scrollXPx.toLong() && scrollYPx.toLong() == scrollYPx.toLong()

        /** The snapshot's size in page CSS pixels. */
        val viewportWidthCss: Float get() = viewportWidthPx / scale
        val viewportHeightCss: Float get() = viewportHeightPx / scale
    }

    /**
     * A patch's place, in the snapshot's own pixels.
     *
     * The percentages are turned into view pixels with the snapshot's size — the only way a percentage can
     * become a length.
     */
    data class ViewRect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height

        /** Whether the numbers could be drawn at all. */
        val sane: Boolean
            get() = width > 0 && height > 0
    }

    /**
     * A patch's place on the page, in page CSS pixels.
     *
     * This is what a [com.smnexstudio.panelglass.feature.browser.ScreenPatch] stores and what the overlay draws.
     */
    data class PageRect(val leftCss: Float, val topCss: Float, val widthCss: Float, val heightCss: Float) {
        val rightCss: Float get() = leftCss + widthCss
        val bottomCss: Float get() = topCss + heightCss

        /** Whether the numbers could be drawn at all. */
        val sane: Boolean
            get() = widthCss.isFinite() && heightCss.isFinite() && heightCss > 0f && widthCss > 0f &&
                leftCss.isFinite() && topCss.isFinite()
    }

    /**
     * A patch's place inside the image it belongs to, as fractions of that image's own size.
     *
     * This is what a saved record keeps: the bubble is the same fraction of the same picture, whatever zoom or
     * width the page is laid out at later.
     */
    data class NormalizedRect(val nx: Float, val ny: Float, val nw: Float, val nh: Float) {
        val sane: Boolean
            get() = nx.isFinite() && ny.isFinite() && nw.isFinite() && nh.isFinite() && nw > 0f && nh > 0f
    }

    /**
     * The patch's box in the snapshot's pixels.
     *
     * This is the conversion the live placement uses, and the one the save path must reuse: the percentages
     * become view pixels with the snapshot's own size, never with anything else.
     */
    fun viewRect(
        xPct: Float, yPct: Float, wPct: Float, hPct: Float,
        geo: CaptureGeometry,
    ): ViewRect? {
        if (!geo.usable) return null
        if (!xPct.isFinite() || !yPct.isFinite() || !wPct.isFinite() || !hPct.isFinite()) return null
        if (wPct <= 0f || hPct <= 0f) return null
        val w = (wPct / 100f * geo.viewportWidthPx).toInt().coerceAtLeast(1)
        val h = (hPct / 100f * geo.viewportHeightPx).toInt().coerceAtLeast(1)
        val left = (xPct / 100f * geo.viewportWidthPx).toInt()
        val top = (yPct / 100f * geo.viewportHeightPx).toInt()
        val r = ViewRect(left, top, w, h)
        return if (r.sane) r else null
    }

    /**
     * The patch's place on the page, in page CSS pixels.
     *
     * The snapshot's pixels are added to the scroll offset and divided by the zoom: that is the page's own
     * coordinate system, and the overlay draws in it.
     */
    fun pageRect(view: ViewRect, geo: CaptureGeometry): PageRect? {
        if (!geo.usable) return null
        val r = PageRect(
            leftCss = (geo.scrollXPx + view.left) / geo.scale,
            topCss = (geo.scrollYPx + view.top) / geo.scale,
            widthCss = view.width / geo.scale,
            heightCss = view.height / geo.scale,
        )
        return if (r.sane) r else null
    }

    /**
     * The patch's place inside the image it was found on, as fractions of that image's size.
     *
     * The image's box is in view pixels; the patch's box is in view pixels too, so the difference is the
     * patch's offset *within* the image, and the image's size is what makes it a fraction.
     *
     * A patch that hangs off the edge of its image keeps its negative offset: the artwork is simply not there
     * under that part of the bubble, and clipping it would move the text.
     */
    fun normalizedRect(
        view: ViewRect,
        image: IntRect,
        geo: CaptureGeometry,
    ): NormalizedRect? {
        if (!geo.usable || image.width <= 0 || image.height <= 0) return null
        val imgW = image.width / geo.scale
        val imgH = image.height / geo.scale
        if (imgW <= 0f || imgH <= 0f) return null
        val r = NormalizedRect(
            nx = (view.left - image.left) / geo.scale / imgW,
            ny = (view.top - image.top) / geo.scale / imgH,
            nw = view.width / geo.scale / imgW,
            nh = view.height / geo.scale / imgH,
        )
        return if (r.sane) r else null
    }

    /**
     * The patch's place on the page, from the fraction it is of its image.
     *
     * The inverse of [normalizedRect] and [pageRect]: the image's place on the page, plus the fraction of the
     * image the patch takes.
     */
    fun pageRectFromNormalized(
        norm: NormalizedRect,
        image: IntRect,
        layout: ImageLayout,
    ): PageRect? {
        if (!norm.sane) return null
        if (!layout.scale.isFinite() || layout.scale <= 0f) return null
        val imgWCss = image.width / layout.scale
        val imgHCss = image.height / layout.scale
        if (imgWCss <= 0f || imgHCss <= 0f) return null
        val r = PageRect(
            leftCss = (layout.scrollX + image.left) / layout.scale + norm.nx * imgWCss,
            topCss = (layout.scrollY + image.top) / layout.scale + norm.ny * imgHCss,
            widthCss = norm.nw * imgWCss,
            heightCss = norm.nh * imgHCss,
        )
        return if (r.sane) r else null
    }

    /**
     * The image's own size in page CSS pixels, at a given zoom.
     *
     * The image's box is in view pixels; the page's content is in CSS pixels, so the zoom divides it.
     */
    fun imageCssSize(image: IntRect, scale: Float): Pair<Float, Float>? {
        if (!scale.isFinite() || scale <= 0f) return null
        val w = image.width / scale
        val h = image.height / scale
        return if (w > 0f && h > 0f) Pair(w, h) else null
    }

    /**
     * Whether two boxes are the same place, to within a pixel of the screen.
     *
     * The tolerance is in view pixels, because a pixel of the screen is what the reader can see.
     */
    fun samePlace(a: PageRect, b: PageRect, scale: Float, tolerancePx: Float = 1f): Boolean {
        val tol = tolerancePx / scale
        return abs(a.leftCss - b.leftCss) <= tol && abs(a.topCss - b.topCss) <= tol &&
            abs(a.widthCss - b.widthCss) <= tol && abs(a.heightCss - b.heightCss) <= tol
    }
}
