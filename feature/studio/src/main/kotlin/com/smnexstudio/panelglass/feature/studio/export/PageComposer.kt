package com.smnexstudio.panelglass.feature.studio.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.render.StudioRenderer
import com.smnexstudio.panelglass.feature.studio.translate.PageLoader
import com.smnexstudio.panelglass.feature.studio.translate.StudioTranslator
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Writes one finished page as PNG or JPEG. A seam: tests check the exporter without decoding or drawing pixels. */
interface PageComposer {
    /** Writes [page] lettered in [tgt] to [out] in [quality]; false when the page cannot be read. */
    suspend fun write(page: StudioPage, tgt: Lang, out: OutputStream, quality: PageQuality = PageQuality.PNG): Boolean

    /** About how many bytes [write] will produce for [page], for the free space check and the size shown. */
    fun estimateBytes(page: StudioPage, quality: PageQuality = PageQuality.PNG): Long
}

/**
 * The exported page is the Final layer at full size: the clean layer (rebuilt first when an edit cleared it) with
 * every bubble lettered by [StudioRenderer], the same drawing the editor shows. A page that was never read is
 * written as it is. One page is in memory at a time, and its bitmap is recycled before the next.
 */
@Singleton
class RenderedPageComposer @Inject constructor(
    private val repo: StudioRepository,
    private val translator: StudioTranslator,
    private val loader: PageLoader,
    private val renderer: StudioRenderer,
) : PageComposer {
    override suspend fun write(page: StudioPage, tgt: Lang, out: OutputStream, quality: PageQuality): Boolean {
        if (StudioStage.DETECTED in page.stages && StudioStage.CLEANED !in page.stages) translator.cleanPage(page.id)
        val fresh = repo.getPage(page.id) ?: return false
        val original = repo.files.file(fresh.file)
        val pageW = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(original.path, it) }.outWidth
        if (pageW <= 0) return false
        val clean = repo.files.file(StudioFiles.layer(fresh.file, StudioFiles.CLEAN))
        val base = (if (StudioStage.CLEANED in fresh.stages && clean.isFile) decodeMutable(clean) else null)
            ?: loader.load(original)?.bitmap?.let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true).also { _ -> it.recycle() } }
            ?: return false
        try {
            // The clean layer is at the analysis resolution: full size, or 1/2ⁿ for a page over the pixel budget.
            val scale = base.width.toFloat() / pageW
            renderer.draw(Canvas(base), repo.bubbleList(page.id), tgt, scale, art = base)
            return when (quality) {
                PageQuality.PNG -> base.compress(Bitmap.CompressFormat.PNG, 100, out)
                PageQuality.JPEG -> base.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } finally {
            base.recycle()
        }
    }

    /**
     * The clean layer's PNG is the closest measure of the exported page (the same pixels, plus lettering); a page not
     * cleaned yet counts twice its original (a JPEG grows as PNG). A JPEG page counts a third of that: screentone
     * and lettering keep a page at quality 90 well above the usual tenth.
     */
    override fun estimateBytes(page: StudioPage, quality: PageQuality): Long {
        val clean = repo.files.file(StudioFiles.layer(page.file, StudioFiles.CLEAN))
        val png = if (clean.isFile) clean.length() else repo.files.file(page.file).length() * 2
        return if (quality == PageQuality.JPEG) png / 3 else png
    }

    private companion object {
        const val JPEG_QUALITY = 90
    }

    private fun decodeMutable(f: File): Bitmap? = BitmapFactory.decodeFile(
        f.path,
        BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}
