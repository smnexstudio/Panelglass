package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.IntRect
import kotlin.math.abs

/**
 * What one accepted translation is, in every coordinate space it needs, captured at the moment it was accepted.
 *
 * This is the record the save path works from. It is built from the **live** patch's geometry — the same numbers
 * the overlay is drawn from — so a saved translation and a displayed one cannot disagree about where the bubble
 * is or how big it is.
 *
 * The old code rebuilt a patch from the pipeline's percentages and used them as if they were CSS pixels. That is
 * the defect this type exists to make impossible: percentages are converted once, here, with the snapshot's own
 * size and zoom.
 */
data class AcceptedRegion(
    /** The image this bubble was found on, and which occurrence of it (see [occurrence]). */
    val imageKey: String,
    /** Which image of the same key this is: 0 is the first, 1 the second, and so on. */
    val occurrence: Int,
    /** The bubble's box in the snapshot's view pixels. */
    val view: Geometry.ViewRect,
    /** The bubble's box in page CSS pixels: where the overlay draws it. */
    val page: Geometry.PageRect,
    /** The bubble's box as a fraction of the image's own size: what the disk record keeps. */
    val normalized: Geometry.NormalizedRect,
    /** The image's box in view pixels at the capture. */
    val image: IntRect,
    /** The capture's numbers: the viewport's size, the scroll offset and the zoom. */
    val geo: Geometry.CaptureGeometry,
    /** The key the translation was made with. */
    val fingerprint: String,
    /** When it was accepted, in epoch milliseconds. */
    val createdAtMs: Long,
) {
    /** Whether the numbers could be drawn and saved at all. */
    val usable: Boolean
        get() = view.sane && page.sane && normalized.sane && geo.usable && image.width > 0 && image.height > 0
}

/**
 * The saved translations of one scope, and the geometry to put them back.
 *
 * The coordinator is what the reader uses; it is deliberately free of Android types, so the tests can exercise
 * the **same** code the app runs. A test of a parallel reimplementation proves nothing, and the bug this fixes was
 * exactly a parallel reimplementation of the live geometry.
 */
object RetentionCoordinator {

    /**
     * The record to write to disk, from an accepted region.
     *
     * Null when the bubble may not be kept: no image, an image that may not be kept, or numbers that could not be
     * drawn.
     */
    fun toSaved(region: AcceptedRegion, webp: ByteArray): Pair<String, SavedRegion>? {
        if (!region.usable) return null
        if (!PatchRetention.retainable(region.imageKey)) return null
        val rec = SavedRegion(
            schema = PatchRetention.GEOMETRY_SCHEMA,
            nx = region.normalized.nx,
            ny = region.normalized.ny,
            nw = region.normalized.nw,
            nh = region.normalized.nh,
            imgW = region.image.width / region.geo.scale,
            imgH = region.image.height / region.geo.scale,
            kind = 0,
            occurrence = region.occurrence,
            fingerprint = region.fingerprint,
            createdAtMs = region.createdAtMs,
            webp = webp,
        )
        return region.imageKey to rec
    }

    /**
     * The region an accepted patch makes, from the live numbers.
     *
     * [view] is the patch's box in the snapshot's own pixels, [image] the image's box in the same pixels, and
     * [geo] the capture's size, scroll offset and zoom. Everything else is derived from those three, so there is
     * one conversion and one answer.
     */
    fun accept(
        imageKey: String,
        occurrence: Int,
        view: Geometry.ViewRect,
        image: IntRect,
        geo: Geometry.CaptureGeometry,
        fingerprint: String,
        createdAtMs: Long,
    ): AcceptedRegion? {
        if (!geo.usable) return null
        if (!view.sane) return null
        val page = Geometry.pageRect(view, geo) ?: return null
        val norm = Geometry.normalizedRect(view, image, geo) ?: return null
        val r = AcceptedRegion(
            imageKey = imageKey, occurrence = occurrence, view = view, page = page, normalized = norm,
            image = image, geo = geo, fingerprint = fingerprint, createdAtMs = createdAtMs,
        )
        return if (r.usable) r else null
    }

    /**
     * The patches to put back on the screen, from the records of one image.
     *
     * A record is skipped when it was made with other settings, when the image's shape has changed (a different
     * aspect ratio means a different picture), when it lies outside the restore window, when the screen already
     * has that bubble, or when its bytes cannot be decoded.
     *
     * [occurrence] says which image of the same key this is: the same picture twice on one page is two images,
     * and a record belongs to the one it was made from.
     */
    fun restore(
        saved: List<SavedRegion>,
        key: String,
        occurrence: Int,
        layout: ImageLayout,
        viewportH: Int,
        restoreMarginPx: Int,
        fingerprint: String,
        existing: List<ScreenPatch>,
        decode: (ByteArray) -> ImageBitmap?,
    ): List<ScreenPatch> {
        val i = layout.occurrenceIndex(key, occurrence)
        if (i < 0) return emptyList()
        val img = layout.images[i]
        val scale = layout.scale
        val w = img.width / scale
        val h = img.height / scale
        if (w <= 0f || h <= 0f) return emptyList()

        val out = ArrayList<ScreenPatch>()
        for (rec in saved) {
            // A record of another image with the same key belongs to another picture.
            if (rec.occurrence != occurrence) continue
            // A record made with other settings is not shown; it is not deleted either.
            if (rec.fingerprint != fingerprint) continue
            // The broken v1 records had their sizes in percentages: they cannot be trusted.
            if (rec.schema < PatchRetention.GEOMETRY_SCHEMA) continue
            // A different shape means a different picture: the record is skipped.
            val savedRatio = if (rec.imgW > 0f && rec.imgH > 0f) rec.imgH / rec.imgW else 0f
            val nowRatio = h / w
            if (savedRatio <= 0f || abs(nowRatio - savedRatio) > 0.02f * savedRatio) continue

            val norm = Geometry.NormalizedRect(rec.nx, rec.ny, rec.nw, rec.nh)
            val page = Geometry.pageRectFromNormalized(norm, img, layout) ?: continue
            // Only what is near the viewport comes back.
            val topPx = page.topCss * scale
            val bottomPx = page.bottomCss * scale
            if (bottomPx < layout.scrollY - restoreMarginPx) continue
            if (topPx > layout.scrollY + viewportH + restoreMarginPx) continue

            val patch = ScreenPatch(
                image = decode(rec.webp) ?: continue,
                left = page.leftCss, top = page.topCss, width = page.widthCss, height = page.heightCss,
                // The anchor carries the fraction of the image as well as the CSS offset, so a later reanchor
                // moves and resizes the bubble by the same rule the restore used.
                anchor = Anchor(
                    key, norm.nx * w, norm.ny * h, w, h, occurrence,
                    norm.nw, norm.nh, norm.nx, norm.ny,
                ),
            )
            // The screen may already have this bubble: it was translated again in the meantime.
            if (existing.any { patch.duplicates(it, DUPLICATE_OVERLAP) }) continue
            out += patch
        }
        return out
    }
}
