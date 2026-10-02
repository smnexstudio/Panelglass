package com.smnexstudio.panelglass.feature.studio.export

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.StudioPage
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** How a chapter is written: a folder of pages, or one archive (CBZ for comic readers, ZIP for anything else). */
enum class ExportFormat(val extension: String, val mime: String) {
    IMAGES("", ""),
    CBZ("cbz", "application/vnd.comicbook+zip"),
    ZIP("zip", "application/zip"),
}

/** How each page is written: lossless PNG, or a JPEG at quality 90 (a third of the size, or less). */
enum class PageQuality(val extension: String, val mime: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
}

/** One file or folder an export wrote: the chapter's archive or its folder of pages (`name/`), with its size. */
data class ExportedFile(val chapterId: Long, val name: String, val pages: Int, val bytes: Long)

/**
 * What an export of chosen chapters will do, for the confirm step: the destination's name ([folder]), the manga's
 * folder in it and the files it will hold, the page count, the size estimate, the free space (null when unknown),
 * which of the files are there already and how many pages are not reviewed yet.
 */
data class ExportPlan(
    val folder: String,
    val mangaFolder: String,
    val files: List<String>,
    val pages: Int,
    val bytes: Long,
    val freeBytes: Long?,
    val existing: List<String>,
    val unreviewed: Int,
)

enum class ExportPhase { RUNNING, DONE, FAILED, CANCELLED }

/** Why an export stopped. */
enum class ExportError {
    /** The folder's permission is gone (revoked, an SD card removed): pick it again. */
    FOLDER_GONE,
    /** A file could not be written (the provider refused). */
    WRITE_FAILED,
    /** A page's own file could not be read. */
    PAGE_UNREADABLE,
    /** Not enough free space: [ExportRun.needBytes] / [ExportRun.freeBytes] when known before writing. */
    NO_SPACE,
}

/**
 * Where an export is. [chapter] is the index in [chapterIds] being written, [page] / [pages] its pages, [written] the
 * pages written so far over every chapter; [where] is what was written (`Panelglass/Manga/Ch 012 - Title`, or the
 * manga's folder for several chapters) for the screen to name.
 */
data class ExportRun(
    val mangaId: Long,
    val chapterIds: List<Long>,
    val phase: ExportPhase,
    val format: ExportFormat = ExportFormat.IMAGES,
    val quality: PageQuality = PageQuality.PNG,
    val chapter: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
    val written: Int = 0,
    val where: String = "",
    val error: ExportError? = null,
    val needBytes: Long = 0,
    val freeBytes: Long = 0,
    /** Pages over every chapter. */
    val total: Int = 0,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** What was written, chapter by chapter, as each one finishes. */
    val files: List<ExportedFile> = emptyList(),
) {
    val active: Boolean get() = phase == ExportPhase.RUNNING
    val bytes: Long get() = files.sumOf { it.bytes }

    /** About how long is left, from the pace so far; null until a page is written. */
    fun remainingMs(now: Long): Long? =
        if (written == 0 || total == 0) null else (now - startedAt) * (total - written) / written
}

/**
 * Exports chapters (docs/STUDIO_PLAN.md › Export), by default into `Download/Panelglass`, or into a folder the user
 * picked for the manga: `<Manga>/<Ch 012 - Title>/001.png… + ComicInfo.xml`, or `<Manga>/<Ch 012 - Title>.cbz` (or
 * `.zip`) holding the same files. One page is rendered at a time. A file of the same name is replaced, and page files
 * an earlier export left beyond the chapter's last page are deleted. The export is refused before it writes anything
 * when the destination's free space cannot hold it. Only one export runs at a time; it outlives the screen.
 */
@Singleton
class StudioExporter @Inject constructor(
    private val repo: StudioRepository,
    private val targets: ExportTargets,
    private val composer: PageComposer,
    private val settings: SettingsRepository,
) {
    /** The clock, swapped in tests. */
    internal var now: () -> Long = System::currentTimeMillis
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _run = MutableStateFlow<ExportRun?>(null)
    val run: StateFlow<ExportRun?> = _run.asStateFlow()

    /** The manga's export folder; null is the default, Downloads. */
    suspend fun folderFor(mangaId: Long): String? = repo.getManga(mangaId)?.exportTreeUri

    fun folderLabel(uri: String?): String? = targets.label(uri)

    /** Exports [mangaId] to [uri] from now on (null: back to Downloads); false when a picked folder cannot be kept. */
    suspend fun choose(mangaId: Long, uri: String?): Boolean {
        if (uri != null && !targets.adopt(uri)) return false
        repo.getManga(mangaId)?.let { repo.updateManga(it.copy(exportTreeUri = uri)) }
        return true
    }

    suspend fun format(): ExportFormat =
        settings.studioExportFormat()?.let { n -> ExportFormat.entries.firstOrNull { it.name == n } } ?: ExportFormat.IMAGES

    suspend fun setFormat(f: ExportFormat) = settings.setStudioExportFormat(f.name)

    suspend fun quality(): PageQuality =
        settings.studioExportQuality()?.let { n -> PageQuality.entries.firstOrNull { it.name == n } } ?: PageQuality.PNG

    suspend fun setQuality(q: PageQuality) = settings.setStudioExportQuality(q.name)

    /** About how many bytes [pages] come to in [quality]. */
    fun estimate(pages: List<StudioPage>, quality: PageQuality): Long = pages.sumOf { composer.estimateBytes(it, quality) }

    /** Ways to open the manga's export folder in a files app. */
    suspend fun openIntents(mangaId: Long, uri: String?): List<android.content.Intent> {
        val manga = repo.getManga(mangaId) ?: return emptyList()
        return targets.openIntents(uri, ExportNames.mangaFolder(manga))
    }

    /** What exporting [chapterIds] to [uri] in [format] would write; null when the manga is gone. */
    suspend fun plan(mangaId: Long, chapterIds: List<Long>, uri: String?, format: ExportFormat, quality: PageQuality): ExportPlan? {
        val manga = repo.getManga(mangaId) ?: return null
        val order = repo.chapterList(mangaId)
        val chapters = chapterIds.mapNotNull { id -> order.firstOrNull { it.id == id } }
        val pagesOf = chapters.associate { it.id to repo.pageList(it.id) }
        val names = chapters.map { c -> fileName(c, order.indexOf(c) + 1, format) }
        val there = existingIds(mangaId, chapterIds, uri, format).toSet()
        return ExportPlan(
            folder = targets.label(uri).orEmpty(),
            mangaFolder = ExportNames.mangaFolder(manga),
            files = names,
            pages = pagesOf.values.sumOf { it.size },
            bytes = pagesOf.values.sumOf { list -> list.sumOf { composer.estimateBytes(it, quality) } },
            freeBytes = targets.freeBytes(uri),
            existing = chapters.indices.filter { chapters[it].id in there }.map { names[it] },
            unreviewed = pagesOf.values.sumOf { list -> list.count { !it.reviewed } },
        )
    }

    /** The chapter's archive, or its folder (with a trailing `/`). */
    private fun fileName(c: Chapter, pos: Int, format: ExportFormat): String =
        if (format == ExportFormat.IMAGES) ExportNames.chapterFolder(c, pos) + "/" else ExportNames.archiveFile(c, pos, format)

    /** How many of [chapterIds] were exported to [uri] in [format] already (an overwrite to confirm first). */
    suspend fun existing(mangaId: Long, chapterIds: List<Long>, uri: String?, format: ExportFormat): Int =
        existingIds(mangaId, chapterIds, uri, format).size

    private suspend fun existingIds(mangaId: Long, chapterIds: List<Long>, uri: String?, format: ExportFormat): List<Long> {
        val target = targets.open(uri) ?: return emptyList()
        val manga = repo.getManga(mangaId) ?: return emptyList()
        return runCatching {
            val dir = target.find(target.root, ExportNames.mangaFolder(manga))?.takeIf { it.isDir } ?: return emptyList()
            val order = repo.chapterList(mangaId)
            val inDir = target.list(dir.id)
            chapterIds.filter { id ->
                val c = order.firstOrNull { it.id == id } ?: return@filter false
                val pos = order.indexOf(c) + 1
                if (format == ExportFormat.IMAGES) {
                    val sub = inDir.firstOrNull { it.isDir && it.name == ExportNames.chapterFolder(c, pos) }
                    sub != null && target.list(sub.id).isNotEmpty()
                } else inDir.any { !it.isDir && it.name == ExportNames.archiveFile(c, pos, format) }
            }
        }.getOrDefault(emptyList())
    }

    fun start(mangaId: Long, chapterIds: List<Long>, uri: String?, format: ExportFormat, quality: PageQuality) {
        if (job?.isActive == true || chapterIds.isEmpty()) return
        _run.value = ExportRun(mangaId, chapterIds, ExportPhase.RUNNING, format, quality, startedAt = now())
        job = scope.launch { export(mangaId, chapterIds, uri, format, quality) }
    }

    fun cancel() {
        job?.cancel()
        _run.value?.let { if (it.active) _run.value = it.copy(phase = ExportPhase.CANCELLED, finishedAt = now()) }
    }

    /** Forgets a finished export's status (its message was read). */
    fun dismiss() {
        if (_run.value?.active != true) _run.value = null
    }

    private class PageUnreadable : IOException()

    /** The export itself; tests call it directly. */
    internal suspend fun export(mangaId: Long, chapterIds: List<Long>, uri: String?, format: ExportFormat, quality: PageQuality = PageQuality.PNG) {
        var state = ExportRun(mangaId, chapterIds, ExportPhase.RUNNING, format, quality, startedAt = now())
        fun set(s: ExportRun) {
            state = if (s.phase != ExportPhase.RUNNING && s.finishedAt == 0L) s.copy(finishedAt = now()) else s
            _run.value = state
        }
        set(state)
        val target = targets.open(uri) ?: return set(state.copy(phase = ExportPhase.FAILED, error = ExportError.FOLDER_GONE))
        val label = targets.label(uri).orEmpty()
        try {
            val manga = repo.getManga(mangaId) ?: return set(state.copy(phase = ExportPhase.DONE))
            val order = repo.chapterList(mangaId)
            val chapters = chapterIds.mapNotNull { id -> order.firstOrNull { it.id == id } }
            val pagesOf = chapters.associate { it.id to repo.pageList(it.id) }

            // Refused before anything is written when it cannot fit (archives hold the same bytes as the pages).
            set(state.copy(total = pagesOf.values.sumOf { it.size }))
            val need = pagesOf.values.sumOf { list -> list.sumOf { composer.estimateBytes(it, quality) } }
            val free = targets.freeBytes(uri)
            if (!StorageSpace.enough(need, free)) {
                return set(state.copy(phase = ExportPhase.FAILED, error = ExportError.NO_SPACE, needBytes = need, freeBytes = free ?: 0))
            }

            val mangaName = ExportNames.mangaFolder(manga)
            val mangaDir = target.folder(target.root, mangaName)
            for ((ci, chapter) in chapters.withIndex()) {
                val pages = pagesOf.getValue(chapter.id)
                val pos = order.indexOf(chapter) + 1
                val tgt = chapter.language ?: manga.tgtLang
                val info = ExportNames.comicInfo(manga, chapter, pages.size).toXml().toByteArray(Charsets.UTF_8)
                if (format == ExportFormat.IMAGES) {
                    val name = ExportNames.chapterFolder(chapter, pos)
                    set(state.copy(chapter = ci, page = 0, pages = pages.size, where = "$label/$mangaName/$name"))
                    val dir = target.folder(mangaDir, name)
                    val files = pages.indices.map { ExportNames.pageFile(it, pages.size, quality.extension) }.toSet()
                    target.list(dir).filter { !it.isDir && ExportNames.isPageFile(it.name) && it.name !in files }.forEach(target::delete)
                    var bytes = 0L
                    for ((i, page) in pages.withIndex()) {
                        coroutineContext.ensureActive()
                        target.write(dir, ExportNames.pageFile(i, pages.size, quality.extension), quality.mime) { out ->
                            val counted = CountingOutputStream(out)
                            if (!composer.write(page, tgt, counted, quality)) throw PageUnreadable()
                            bytes += counted.count
                        }
                        set(state.copy(page = i + 1, written = state.written + 1))
                    }
                    target.write(dir, ExportNames.COMIC_INFO, "text/xml") { it.write(info) }
                    set(state.copy(files = state.files + ExportedFile(chapter.id, "$name/", pages.size, bytes + info.size)))
                } else {
                    val name = ExportNames.archiveFile(chapter, pos, format)
                    set(state.copy(chapter = ci, page = 0, pages = pages.size, where = "$label/$mangaName/$name"))
                    var bytes = 0L
                    target.write(mangaDir, name, format.mime) { out ->
                        // Pages are compressed already: the fastest deflate level costs little and saves nothing to wait for.
                        val counted = CountingOutputStream(out)
                        val zip = ZipOutputStream(counted).apply { setLevel(Deflater.BEST_SPEED) }
                        for ((i, page) in pages.withIndex()) {
                            coroutineContext.ensureActive()
                            zip.putNextEntry(ZipEntry(ExportNames.pageFile(i, pages.size, quality.extension)))
                            if (!composer.write(page, tgt, zip, quality)) throw PageUnreadable()
                            zip.closeEntry()
                            set(state.copy(page = i + 1, written = state.written + 1))
                        }
                        zip.putNextEntry(ZipEntry(ExportNames.COMIC_INFO))
                        zip.write(info)
                        zip.closeEntry()
                        zip.finish()
                        bytes = counted.count
                    }
                    set(state.copy(files = state.files + ExportedFile(chapter.id, name, pages.size, bytes)))
                }
            }
            // Several chapters went into the manga's folder: name the folder, not the last chapter.
            val where = if (chapters.size > 1) "$label/$mangaName" else state.where
            set(state.copy(phase = ExportPhase.DONE, where = where))
        } catch (e: CancellationException) {
            set(state.copy(phase = ExportPhase.CANCELLED))
            throw e
        } catch (_: PageUnreadable) {
            set(state.copy(phase = ExportPhase.FAILED, error = ExportError.PAGE_UNREADABLE))
        } catch (_: SecurityException) {
            set(state.copy(phase = ExportPhase.FAILED, error = ExportError.FOLDER_GONE))
        } catch (e: IOException) {
            set(state.copy(phase = ExportPhase.FAILED, error = if (StorageSpace.isFull(e)) ExportError.NO_SPACE else ExportError.WRITE_FAILED))
        } catch (_: IllegalArgumentException) {
            // A provider reports a document that vanished (the folder deleted meanwhile) this way.
            set(state.copy(phase = ExportPhase.FAILED, error = ExportError.FOLDER_GONE))
        }
    }
}

/** Counts the bytes that pass through, for the sizes the finished export lists. */
internal class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    // The wrapped stream belongs to the target, which closes it.
    override fun close() = flush()
}
