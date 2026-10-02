package com.smnexstudio.panelglass.feature.studio.review

import kotlin.math.max
import kotlin.math.min

/**
 * Where the page sits in the canvas: screen = page × [scale] + ([tx], [ty]). [zoom] is relative to the fit (1 = the
 * whole page fits). Immutable, so gestures and "follow the selection" compose as plain functions ([CanvasTransform]).
 */
data class View(val scale: Float, val tx: Float, val ty: Float)

/**
 * The editor's view math for a page of [pageW] × [pageH] in a canvas of [viewW] × [viewH] (both in pixels). Zoom is
 * kept between [MIN_ZOOM] (the page fits) and [MAX_ZOOM]; a page smaller than the canvas on an axis is centred on it,
 * a larger one never shows empty space past its edges. Pure Kotlin: tested on the JVM (`CanvasTransformTest`).
 */
class CanvasTransform(val pageW: Float, val pageH: Float, val viewW: Float, val viewH: Float) {
    val fit: Float = min(viewW / pageW, viewH / pageH)

    fun zoom(v: View): Float = v.scale / fit

    fun fitView(): View = clamp(View(fit, 0f, 0f))

    fun toPage(v: View, sx: Float, sy: Float): Pair<Float, Float> = (sx - v.tx) / v.scale to (sy - v.ty) / v.scale
    fun toScreen(v: View, px: Float, py: Float): Pair<Float, Float> = px * v.scale + v.tx to py * v.scale + v.ty
    fun toScreen(v: View, b: Box): Box = Box(b.left * v.scale + v.tx, b.top * v.scale + v.ty, b.right * v.scale + v.tx, b.bottom * v.scale + v.ty)

    /** The part of the page on screen, in page pixels. */
    fun visiblePage(v: View): Box {
        val (l, t) = toPage(v, 0f, 0f)
        val (r, b) = toPage(v, viewW, viewH)
        return Box(max(0f, l), max(0f, t), min(pageW, r), min(pageH, b))
    }

    /** Zoom limits, then the pan: centred on an axis where the page is smaller than the view, else inside its edges. */
    fun clamp(v: View): View {
        val s = v.scale.coerceIn(fit * MIN_ZOOM, fit * MAX_ZOOM)
        val w = pageW * s
        val h = pageH * s
        val tx = if (w <= viewW) (viewW - w) / 2f else v.tx.coerceIn(viewW - w, 0f)
        val ty = if (h <= viewH) (viewH - h) / 2f else v.ty.coerceIn(viewH - h, 0f)
        return View(s, tx, ty)
    }

    /** A pinch: scaled by [factor] around the screen point ([fx], [fy]) that stays under the fingers, then panned. */
    fun transform(v: View, fx: Float, fy: Float, factor: Float, panX: Float, panY: Float): View {
        val s = (v.scale * factor).coerceIn(fit * MIN_ZOOM, fit * MAX_ZOOM)
        val k = s / v.scale
        return clamp(View(s, fx - (fx - v.tx) * k + panX, fy - (fy - v.ty) * k + panY))
    }

    fun pan(v: View, dx: Float, dy: Float): View = clamp(View(v.scale, v.tx + dx, v.ty + dy))

    /** [rect] (page pixels) centred and as large as fits with [margin] screen pixels around it, within the zoom limits. */
    fun fitRect(rect: Box, margin: Float): View {
        val s = min((viewW - 2 * margin) / max(1f, rect.width), (viewH - 2 * margin) / max(1f, rect.height))
            .coerceIn(fit * MIN_ZOOM, fit * MAX_ZOOM)
        return clamp(View(s, viewW / 2f - rect.centerX * s, viewH / 2f - rect.centerY * s))
    }

    /**
     * Brings [rect] on screen at the current zoom, panning as little as needed (a proofreader stepping through bubbles at
     * 3× stays at 3×). Only a rect too big for the view at this zoom makes it zoom out, to [fitRect].
     */
    fun follow(v: View, rect: Box, margin: Float): View {
        val w = rect.width * v.scale + 2 * margin
        val h = rect.height * v.scale + 2 * margin
        if (w > viewW || h > viewH) return fitRect(rect, margin)
        val r = toScreen(v, rect)
        var dx = 0f
        var dy = 0f
        if (r.left < margin) dx = margin - r.left else if (r.right > viewW - margin) dx = viewW - margin - r.right
        if (r.top < margin) dy = margin - r.top else if (r.bottom > viewH - margin) dy = viewH - margin - r.bottom
        return pan(v, dx, dy)
    }

    /** Double-tap: on a bubble, fit it; on the art, the fit if zoomed in, else [TAP_ZOOM] around the tap. */
    fun doubleTap(v: View, sx: Float, sy: Float, bubble: Box?, margin: Float): View = when {
        bubble != null -> fitRect(bubble, margin)
        zoom(v) > MIN_ZOOM + 0.05f -> fitView()
        else -> transform(v, sx, sy, TAP_ZOOM * fit / v.scale, 0f, 0f)
    }

    /**
     * The view after the canvas was resized (the keyboard opened, focus mode): the same page point at the centre and the
     * same scale, so what is being edited stays the size it was (only the zoom limits of the new canvas apply).
     */
    fun resized(v: View, old: CanvasTransform): View {
        val (cx, cy) = old.toPage(v, old.viewW / 2f, old.viewH / 2f)
        val s = v.scale.coerceIn(fit * MIN_ZOOM, fit * MAX_ZOOM)
        return clamp(View(s, viewW / 2f - cx * s, viewH / 2f - cy * s))
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f
        const val TAP_ZOOM = 2.5f
    }
}
