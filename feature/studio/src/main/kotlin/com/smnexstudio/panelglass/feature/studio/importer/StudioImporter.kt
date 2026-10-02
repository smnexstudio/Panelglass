package com.smnexstudio.panelglass.feature.studio.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.NaturalOrder
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

enum class ImportSource { IMAGES, PDF, CBZ }

/** Pages staged from one pick, before the user confirms where they go. */
data class Staging(
    val pages: List<StagedPage>,
    val skipped: List<Pair<String, SkipReason>> = emptyList(),
    /** From a CBZ's ComicInfo.xml, to pre-fill the metadata. */
    val comicInfo: ComicInfo? = null,
    /** The picked file's name (PDF / CBZ), without its extension: a title suggestion. */
    val sourceName: String? = null,
)

/**
 * Imports images, a PDF or a CBZ picked through the system file picker (SAF). Pages are staged into their own
 * directories off the main thread; nothing is saved to the database here ([com.smnexstudio.panelglass.core.data.repo.StudioRepository.import]
 * does that once the user confirms). A cancelled or failed import deletes what it staged.
 */
@Singleton
class StudioImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    files: StudioFiles,
) {
    val limits = ImportLimits()
    private val stager = PageStager(files, AndroidTranscoder, limits)
    private val pagesDir = files.root

    /** Refuses the import before it stages anything when the app's storage cannot hold about [needed] bytes. */
    private fun ensureSpace(needed: Long) {
        val free = StorageSpace.free(pagesDir)
        if (!StorageSpace.enough(needed, free)) throw ImportRefused(ImportRefused.Reason.NO_SPACE, needed, free ?: 0)
    }

    private fun full() = ImportRefused(ImportRefused.Reason.NO_SPACE, 0, StorageSpace.free(pagesDir) ?: 0)

    suspend fun images(uris: List<Uri>, progress: (done: Int, total: Int) -> Unit): Staging = staging { staged, skipped ->
        if (uris.size > limits.maxPages) throw ImportRefused(ImportRefused.Reason.TOO_MANY_PAGES)
        ensureSpace(uris.sumOf { size(it) ?: 0L })
        val named = uris.map { it to (displayName(it) ?: it.lastPathSegment ?: "") }.sortedWith(compareBy(NaturalOrder) { it.second })
        named.forEachIndexed { i, (uri, name) ->
            coroutineContext.ensureActive()
            val result = try {
                context.contentResolver.openInputStream(uri)?.use { stager.stage(name, it) }
                    ?: PageStager.Result.Skipped(name, SkipReason.UNREADABLE)
            } catch (e: IOException) {
                // A full storage stops the import; anything else only skips this file.
                if (StorageSpace.isFull(e)) throw full()
                PageStager.Result.Skipped(name, SkipReason.UNREADABLE)
            } catch (e: SecurityException) {
                PageStager.Result.Skipped(name, SkipReason.UNREADABLE)
            }
            collect(result, staged, skipped)
            progress(i + 1, named.size)
        }
        Staging(staged.toList(), skipped.toList())
    }

    suspend fun cbz(uri: Uri, progress: (done: Int, total: Int) -> Unit): Staging = staging { staged, skipped ->
        val name = displayName(uri)
        if (!CbzReader.isCbzName(name)) throw ImportRefused(ImportRefused.Reason.NOT_CBZ)
        // The pages are mostly kept byte for byte: about the archive's own size.
        ensureSpace(size(uri) ?: 0L)
        val input = context.contentResolver.openInputStream(uri) ?: throw ImportRefused(ImportRefused.Reason.UNREADABLE)
        val ctx = coroutineContext
        val info = input.use { stream ->
            CbzReader(limits).read(stream.buffered()) { entry, body ->
                ctx.ensureActive() // a cancelled import stops at the next entry
                collect(stager.stage(entry, body), staged, skipped)
                progress(staged.size + skipped.size, 0)
            }
        }
        Staging(staged.sortedWith(compareBy(NaturalOrder) { it.name }), skipped.toList(), info, name?.substringBeforeLast('.'))
    }

    suspend fun pdf(uri: Uri, progress: (done: Int, total: Int) -> Unit): Staging = staging { staged, _ ->
        val name = displayName(uri)
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: SecurityException) {
            null
        } ?: throw ImportRefused(ImportRefused.Reason.UNREADABLE)
        pfd.use {
            val renderer = try {
                PdfRenderer(it)
            } catch (e: SecurityException) {
                throw ImportRefused(ImportRefused.Reason.ENCRYPTED_PDF) // password-protected
            } catch (e: IOException) {
                throw ImportRefused(ImportRefused.Reason.NOT_A_PDF)
            }
            renderer.use { r ->
                if (r.pageCount > limits.maxPages) throw ImportRefused(ImportRefused.Reason.TOO_MANY_PAGES)
                ensureSpace(r.pageCount * PDF_PAGE_BYTES)
                for (i in 0 until r.pageCount) {
                    coroutineContext.ensureActive()
                    val label = "%04d".format(i + 1)
                    val result = r.openPage(i).use { page ->
                        stager.stageRendered(label) { out -> renderPdfPage(page, out) }
                    }
                    collect(result, staged, mutableListOf())
                    progress(i + 1, r.pageCount)
                }
            }
        }
        Staging(staged.toList(), sourceName = name?.substringBeforeLast('.'))
    }

    fun discard(pages: Collection<StagedPage>) = stager.discard(pages)

    private fun renderPdfPage(page: PdfRenderer.Page, out: File): Pair<Int, Int> {
        val (w, h) = PdfSizing.renderSize(page.width, page.height, limits.maxDecodePixels)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bmp.eraseColor(Color.WHITE) // a PDF page is transparent where nothing is drawn
            val m = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            out.outputStream().buffered().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("png") }
        } finally {
            bmp.recycle()
        }
        return w to h
    }

    /** Runs [block] on the IO dispatcher; on failure or cancellation, what it staged so far is deleted. */
    private suspend fun staging(
        block: suspend (MutableList<StagedPage>, MutableList<Pair<String, SkipReason>>) -> Staging,
    ): Staging = withContext(Dispatchers.IO) {
        val staged = mutableListOf<StagedPage>()
        val skipped = mutableListOf<Pair<String, SkipReason>>()
        try {
            val result = block(staged, skipped)
            if (result.pages.isEmpty()) throw ImportRefused(ImportRefused.Reason.NO_PAGES)
            result
        } catch (e: Throwable) {
            stager.discard(staged)
            when (e) {
                is CancellationException, is ImportRefused -> throw e
                is IOException -> throw if (StorageSpace.isFull(e)) full() else ImportRefused(ImportRefused.Reason.UNREADABLE)
                is OutOfMemoryError -> throw ImportRefused(ImportRefused.Reason.TOO_LARGE)
                else -> throw e
            }
        }
    }

    private fun collect(r: PageStager.Result, staged: MutableList<StagedPage>, skipped: MutableList<Pair<String, SkipReason>>) {
        when (r) {
            is PageStager.Result.Staged -> staged += r.page
            is PageStager.Result.Skipped -> skipped += r.name to r.reason
        }
    }

    /** The picked file's size in bytes, when its provider tells. */
    private fun size(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

/** About what one rendered PDF page takes as PNG (2000 px wide), for the free space check. */
private const val PDF_PAGE_BYTES = 3L * 1024 * 1024

/** How big a PDF page is rendered: 2× its point size, at most 2000 px wide and [maxPixels] in all. */
object PdfSizing {
    const val SCALE = 2f
    const val MAX_WIDTH = 2000

    fun renderSize(widthPt: Int, heightPt: Int, maxPixels: Long): Pair<Int, Int> {
        var s = min(SCALE, MAX_WIDTH / widthPt.coerceAtLeast(1).toFloat())
        val px = widthPt.toLong() * heightPt * s * s
        if (px > maxPixels) s *= kotlin.math.sqrt(maxPixels / px.toDouble()).toFloat()
        return (widthPt * s).roundToInt().coerceAtLeast(1) to (heightPt * s).roundToInt().coerceAtLeast(1)
    }
}

/** The platform decoders: [ImageDecoder] reads AVIF and animated formats (first frame) on Android 12. */
internal object AndroidTranscoder : Transcoder {
    override fun bounds(src: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, o)
        if (o.outWidth > 0 && o.outHeight > 0) return o.outWidth to o.outHeight
        // AVIF: ImageDecoder reads the header; the decode is stopped there.
        var size: Pair<Int, Int>? = null
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(src)) { _, info, _ ->
                size = info.size.width to info.size.height
                throw HeaderOnly()
            }
        } catch (_: HeaderOnly) {
        } catch (_: IOException) {
        }
        return size
    }

    override fun toPng(src: File, dst: File): Pair<Int, Int>? {
        val bmp = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { d, _, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: BitmapFactory.decodeFile(src.path) ?: return null
        try {
            dst.outputStream().buffered().use { if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) return null }
            return bmp.width to bmp.height
        } finally {
            bmp.recycle()
        }
    }

    private class HeaderOnly : RuntimeException()
}
