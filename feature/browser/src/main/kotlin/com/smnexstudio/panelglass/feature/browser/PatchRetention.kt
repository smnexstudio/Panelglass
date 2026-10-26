package com.smnexstudio.panelglass.feature.browser

import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.data.cache.SavedRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import kotlin.math.abs

/**
 * The saved translations: what may be kept, how a patch becomes a record, and how a record becomes a patch again.
 *
 * The whole point is that a bubble the reader has already paid for comes back without a detector, an OCR or an
 * engine call. That works because the record is stored as a **fraction of the image's own size**: the same bubble on
 * a page laid out at another zoom, or at another width after a rotation, is the same fraction of the same image.
 *
 * Everything here is pure: the bitmap decoder is injected, so the tests need no Android.
 */
object PatchRetention {
    /**
     * Whether a translation on this image may be kept.
     *
     * - A blank key names no image.
     * - A `canvas#` key is a paged canvas reader: the same key is reused for every page, so a record saved for one
     *   page would be shown on another. A canvas page is never restored.
     * - A `data:` key is a picture the page drew itself; `mt.js` shortens those keys, so two different pictures can
     *   share one.
     *
     * Everything else — an https image, a blob — may be kept.
     */
    fun retainable(key: String): Boolean {
        if (key.isBlank()) return false
        if (key.startsWith("canvas#")) return false
        if (key.startsWith("data:")) return false
        return true
    }

    /**
     * The record of a patch, and the image key it belongs to.
     *
     * Null when the patch cannot be kept: no anchor (it lies outside every image), an anchor whose key may not be
     * kept, or an image whose size is not known.
     */
    fun toSaved(patch: ScreenPatch, fingerprint: String, nowMs: Long, webp: ByteArray): Pair<String, SavedRegion>? {
        val anchor = patch.anchor ?: return null
        if (!retainable(anchor.key)) return null
        val imgW = anchor.imgW
        val imgH = anchor.imgH
        if (imgW <= 0f || imgH <= 0f) return null
        val region = SavedRegion(
            schema = 1,
            nx = anchor.dx / imgW,
            ny = anchor.dy / imgH,
            nw = patch.width / imgW,
            nh = patch.height / imgH,
            imgW = imgW,
            imgH = imgH,
            kind = 0,
            fingerprint = fingerprint,
            createdAtMs = nowMs,
            webp = webp,
        )
        return anchor.key to region
    }

    /**
     * The patches to put back on the screen, from the records of one image.
     *
     * A record is skipped when it was made with other settings ([fingerprint] differs), when the image's shape has
     * changed (a different aspect ratio means a different picture), when it lies outside the restore window, when
     * the screen already has that bubble, or when its bytes cannot be decoded.
     */
    fun restore(
        saved: List<SavedRegion>,
        key: String,
        layout: ImageLayout,
        viewportH: Int,
        restoreMarginPx: Int,
        fingerprint: String,
        existing: List<ScreenPatch>,
        decode: (ByteArray) -> ImageBitmap?,
    ): List<ScreenPatch> {
        val i = layout.keys.indexOf(key)
        if (i < 0) return emptyList()
        val img = layout.images[i]
        val scale = layout.scale
        val w = img.width / scale
        val h = img.height / scale
        if (w <= 0f || h <= 0f) return emptyList()

        val out = ArrayList<ScreenPatch>()
        for (rec in saved) {
            // A record made with other settings is not shown; it is not deleted either.
            if (rec.fingerprint != fingerprint) continue
            // A different shape means a different picture: the record is skipped.
            val savedRatio = if (rec.imgW > 0f && rec.imgH > 0f) rec.imgH / rec.imgW else 0f
            val nowRatio = h / w
            if (savedRatio <= 0f || abs(nowRatio - savedRatio) > 0.02f * savedRatio) continue

            val left = (layout.scrollX + img.left) / scale + rec.nx * w
            val top = (layout.scrollY + img.top) / scale + rec.ny * h
            val width = rec.nw * w
            val height = rec.nh * h
            // Only what is near the viewport comes back.
            val topPx = top * scale
            val bottomPx = (top + height) * scale
            if (bottomPx < layout.scrollY - restoreMarginPx) continue
            if (topPx > layout.scrollY + viewportH + restoreMarginPx) continue

            val patch = ScreenPatch(
                image = decode(rec.webp) ?: continue,
                left = left, top = top, width = width, height = height,
                anchor = Anchor(key, rec.nx * w, rec.ny * h, w, h),
            )
            // The screen may already have this bubble: it was translated again in the meantime.
            if (existing.any { patch.duplicates(it, DUPLICATE_OVERLAP) }) continue
            out += patch
        }
        return out
    }

    /**
     * How far outside the viewport a record may be and still be restored.
     *
     * It is 75% of the prune distance: a patch restored at the edge of the window is not pruned again straight away.
     */
    fun restoreMargin(screens: Int, viewportH: Int): Int = (0.75f * screens * viewportH).toInt()

    /**
     * What a translation was made with: the languages, the engine, the model, the font, the series and the pipeline.
     *
     * It must not hold anything that changes between two translations of the same page and nothing that is a secret:
     * no dialogue-context setting, no session id, no timestamp, no key.
     */
    fun fingerprint(cfg: TranslateConfig, modelTag: String, seriesDigest: String, pipelineVersion: Int): String =
        "v1|${cfg.src.code}|${cfg.tgt.code}|${cfg.engineId.name}|$modelTag|${cfg.fontId ?: "auto"}|" +
            seriesDigest.take(8) + "|$pipelineVersion"
}

/**
 * The fraction of overlap that marks a duplicate on the screen: the same bubble seen from two viewports.
 *
 * It lives here because both the reader's own check and the restore use it: a restored patch that duplicates one
 * already on the screen is not put there twice.
 */
internal const val DUPLICATE_OVERLAP = 0.5f
