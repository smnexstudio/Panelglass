package com.smnexstudio.panelglass.feature.studio.importer

import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.StudioPage
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Caps on what an import accepts: zip bombs, huge images and runaway archives are refused, never decoded. */
data class ImportLimits(
    /** Bytes of one image file or archive entry. */
    val maxFileBytes: Long = 64L * 1024 * 1024,
    /** Bytes unpacked from one archive in all. */
    val maxTotalBytes: Long = 1536L * 1024 * 1024,
    /** Entries in one archive, images or not. */
    val maxEntries: Int = 3000,
    /** Pixels of a page kept as it is (long strips included: later stages read it in bands). */
    val maxPixels: Long = 100_000_000,
    /** Pixels of a page that has to be decoded whole to be converted (GIF, animated WebP, AVIF). */
    val maxDecodePixels: Long = 40_000_000,
    /** Pages of one import. */
    val maxPages: Int = 1000,
)

/** Why a file was left out of an import. */
enum class SkipReason { NOT_AN_IMAGE, TOO_LARGE, TOO_MANY_PIXELS, UNREADABLE }

/** Why an import was refused as a whole; for [Reason.NO_SPACE], about how many bytes it [needed] and how many were [free] (0: unknown). */
class ImportRefused(val reason: Reason, val needed: Long = 0, val free: Long = 0) : IOException(reason.name) {
    enum class Reason { NOT_CBZ, NOT_A_ZIP, ENCRYPTED_PDF, NOT_A_PDF, TOO_LARGE, TOO_MANY_ENTRIES, TOO_MANY_PAGES, NO_PAGES, UNREADABLE, NO_SPACE }
}

/** A page written into its own directory, not in the database yet. [name] orders it and shows in the preview. */
data class StagedPage(val name: String, val page: StudioPage)

/** Decoding the platform does: bounds of a format the header does not size, and a first frame re-encoded as PNG. */
interface Transcoder {
    /** Width and height, or null when the file does not decode. */
    fun bounds(src: File): Pair<Int, Int>?
    /** Writes the first frame of [src] to [dst] as PNG; returns its size, or null when it does not decode. */
    fun toPng(src: File, dst: File): Pair<Int, Int>?
}

/**
 * Turns one image stream into a staged page: copied into a fresh page directory under the byte cap, sized from its
 * header, then kept as it is (PNG, JPEG, static WebP) or converted to PNG (GIF, animated WebP, AVIF). Anything
 * refused leaves no file behind.
 */
class PageStager(private val files: StudioFiles, private val transcoder: Transcoder, private val limits: ImportLimits) {

    sealed interface Result {
        data class Staged(val page: StagedPage) : Result
        data class Skipped(val name: String, val reason: SkipReason) : Result
    }

    fun stage(name: String, input: InputStream): Result {
        val (rel, dir) = files.newPageDir()
        val tmp = File(dir, "incoming")
        try {
            val copied = copyCapped(input, tmp, limits.maxFileBytes)
            if (copied < 0) return skip(rel, name, SkipReason.TOO_LARGE)
            val header = tmp.inputStream().buffered().use { ImageHeader.probe(it) }
                ?: return skip(rel, name, SkipReason.NOT_AN_IMAGE)
            if (header.pixels > limits.maxPixels) return skip(rel, name, SkipReason.TOO_MANY_PIXELS)
            if (header.copyAsIs) {
                val out = File(dir, "${StudioFiles.ORIGINAL}.${header.format.ext}")
                if (!tmp.renameTo(out)) return skip(rel, name, SkipReason.UNREADABLE)
                return staged(rel, out.name, name, header.width, header.height)
            }
            val (w, h) = (if (header.width > 0) header.width to header.height else transcoder.bounds(tmp))
                ?: return skip(rel, name, SkipReason.UNREADABLE)
            if (w.toLong() * h > limits.maxDecodePixels) return skip(rel, name, SkipReason.TOO_MANY_PIXELS)
            val out = File(dir, "${StudioFiles.ORIGINAL}.png")
            val size = transcoder.toPng(tmp, out) ?: return skip(rel, name, SkipReason.UNREADABLE)
            tmp.delete()
            return staged(rel, out.name, name, size.first, size.second)
        } catch (e: IOException) {
            discard(rel)
            throw e
        } catch (e: RuntimeException) {
            // A decoder can throw on a crafted file; that file is skipped, the import goes on.
            return skip(rel, name, SkipReason.UNREADABLE)
        }
    }

    /** A page rendered by the caller (a PDF page) into [write]'s file, `original.png`. */
    fun stageRendered(name: String, write: (File) -> Pair<Int, Int>): Result {
        val (rel, dir) = files.newPageDir()
        val out = File(dir, "${StudioFiles.ORIGINAL}.png")
        return try {
            val (w, h) = write(out)
            staged(rel, out.name, name, w, h)
        } catch (e: IOException) {
            discard(rel)
            throw e
        }
    }

    /** Deletes staged pages that will not be saved (a cancelled import, pages removed in the preview). */
    fun discard(pages: Collection<StagedPage>) {
        pages.forEach { discard(it.page.dir) }
    }

    private fun discard(rel: String) {
        files.deletePageDir(rel)
        files.settle(listOf(rel))
    }

    private fun skip(rel: String, name: String, reason: SkipReason): Result {
        discard(rel)
        return Result.Skipped(name, reason)
    }

    private fun staged(rel: String, file: String, name: String, w: Int, h: Int) =
        Result.Staged(StagedPage(name, StudioPage(chapterId = 0, index = 0, file = "$rel/$file", width = w, height = h)))

    companion object {
        /** Copies at most [max] bytes; returns the count, or -1 when the stream is longer (the partial file is left for the caller). */
        fun copyCapped(input: InputStream, out: File, max: Long): Long {
            var total = 0L
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    if (total > max) return -1
                    o.write(buf, 0, r)
                }
            }
            return total
        }
    }
}
