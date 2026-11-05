package com.smnexstudio.panelglass.feature.studio

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.ImportTarget
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.ComicInfo
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.MangaStatus
import com.smnexstudio.panelglass.feature.studio.importer.ImportRefused
import com.smnexstudio.panelglass.feature.studio.importer.ImportSource
import com.smnexstudio.panelglass.feature.studio.importer.SkipReason
import com.smnexstudio.panelglass.feature.studio.importer.StagedPage
import com.smnexstudio.panelglass.feature.studio.importer.Staging
import com.smnexstudio.panelglass.feature.studio.importer.StudioImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.concurrent.thread

sealed interface ImportUi {
    /** The system picker is open (or about to be). */
    data object Picking : ImportUi
    /** Pages are being copied in; [total] is 0 when unknown (an archive's page count is known only once it is read). */
    data class Reading(val done: Int, val total: Int) : ImportUi
    /** Staged pages shown for ordering, plus where they go. */
    data object Review : ImportUi
    data object Saving : ImportUi
    data class Done(val chapterId: Long) : ImportUi
    data class Failed(val reason: ImportRefused.Reason, val needed: Long = 0, val free: Long = 0) : ImportUi
}

/**
 * One import: the picked files are staged (copied into page directories), shown for ordering, then saved with their
 * metadata in one transaction. Leaving before saving deletes what was staged, so a cancelled import leaves no
 * half chapter.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: StudioImporter,
    private val repo: StudioRepository,
    private val settingsRepo: SettingsRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {
    val source: ImportSource = handle.get<String>("source")?.let { s -> ImportSource.entries.firstOrNull { it.name == s } } ?: ImportSource.IMAGES
    /** Set when importing more pages into an existing chapter. */
    val appendTo: Long? = handle.get<String>("chapter")?.toLongOrNull()
    private val startManga: Long? = handle.get<String>("manga")?.toLongOrNull()

    val ui = MutableStateFlow<ImportUi>(ImportUi.Picking)
    val pages = MutableStateFlow<List<StagedPage>>(emptyList())
    val skipped = MutableStateFlow<List<Pair<String, SkipReason>>>(emptyList())

    /** Destination: an existing manga, or null for a new one. */
    val destManga = MutableStateFlow<Manga?>(null)
    val mangas = MutableStateFlow<List<Manga>>(emptyList())
    val newManga = MutableStateFlow(Manga(title = ""))
    val chapter = MutableStateFlow(ChapterDraft())
    val appendChapter = MutableStateFlow<Chapter?>(null)

    /**
     * An archive with its pages in several folders (`Chapter 66/`, `Chapter 67/`): one chapter per folder, each numbered
     * from its folder's name. Empty when the pages are not split that way; [split] is whether to import them so.
     */
    val folders = MutableStateFlow<List<FolderChapter>>(emptyList())
    val split = MutableStateFlow(true)

    /**
     * A pack holding chapters of two or more series (Mihon's `<source>/<series>/<chapter>.cbz`): one manga per series,
     * each joining a manga of the same title already in the Studio. Empty otherwise; [perSeries] is whether to import
     * them so (else every chapter goes to the one manga chosen, as before).
     */
    val series = MutableStateFlow<List<SeriesGroup>>(emptyList())
    val perSeries = MutableStateFlow(true)

    /** Survives a configuration change, so the picker is not opened twice. */
    var pickerLaunched: Boolean
        get() = handle.get<Boolean>("picked") == true
        set(v) { handle["picked"] = v }

    private var job: Job? = null
    private var saved = false

    fun file(p: StagedPage): File = repo.files.file(p.page.file)

    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        ui.value = ImportUi.Reading(0, if (source == ImportSource.IMAGES) uris.size else 0)
        job = viewModelScope.launch {
            try {
                val progress: (Int, Int) -> Unit = { done, total -> ui.value = ImportUi.Reading(done, total) }
                val staging = when (source) {
                    ImportSource.IMAGES -> importer.images(uris, progress)
                    ImportSource.PDF -> importer.pdf(uris.first(), progress)
                    ImportSource.ARCHIVE -> importer.archive(uris.first(), progress)
                }
                prefill(staging)
                pages.value = staging.pages
                folders.value = if (appendTo == null) FolderChapters.of(staging.pages, staging.folderInfo) else emptyList()
                series.value = if (startManga == null) FolderChapters.series(folders.value, staging.folderInfo) else emptyList()
                skipped.value = staging.skipped
                ui.value = ImportUi.Review
            } catch (e: ImportRefused) {
                ui.value = ImportUi.Failed(e.reason, e.needed, e.free)
            }
        }
    }

    /** Stops reading; what was staged is deleted by the importer. */
    fun cancelReading() {
        job?.cancel()
        job = null
    }

    fun remove(p: StagedPage) {
        pages.value = pages.value - p
        importer.discard(listOf(p))
    }

    fun move(from: Any, to: Any) {
        val list = pages.value
        pages.value = list.moved(list.indexOfFirst { it.page.file == from }, list.indexOfFirst { it.page.file == to })
    }

    fun pickDestination(m: Manga?) {
        destManga.value = m
        viewModelScope.launch { if (m != null) suggestNumber(m) }
    }

    fun save() {
        val list = pages.value
        if (list.isEmpty() || ui.value != ImportUi.Review) return
        ui.value = ImportUi.Saving
        viewModelScope.launch {
            if (bySeries()) {
                saveSeries(list, series.value)
                return@launch
            }
            val groups = folders.value
            if (split.value && groups.size > 1) {
                saveFolders(list, groups)
                return@launch
            }
            val draft = chapter.value
            val target = when {
                appendTo != null -> ImportTarget.AppendTo(appendTo)
                destManga.value != null -> ImportTarget.NewChapter(destManga.value!!.id, draft.applyTo(Chapter(mangaId = 0)))
                else -> ImportTarget.NewManga(newManga.value.normalized(), draft.applyTo(Chapter(mangaId = 0)))
            }
            val id = repo.import(target, list.map { it.page })
            saved = true
            ui.value = ImportUi.Done(id)
        }
    }

    /**
     * One chapter per folder, in the archive's order: the first creates the manga (or joins the chosen one), the others
     * follow it. Pages keep the order shown, each with its folder.
     */
    private suspend fun saveFolders(list: List<StagedPage>, groups: List<FolderChapter>) {
        val first = saveChapters(list, groups, destManga.value?.id, newManga.value.normalized())
        saved = true
        ui.value = ImportUi.Done(first ?: return)
    }

    /**
     * One manga per series, in the archive's order: a manga already in the Studio with the series' title (any case)
     * gets its chapters, else a new one is made from the series' ComicInfo, with the languages chosen on this screen.
     */
    private suspend fun saveSeries(list: List<StagedPage>, groups: List<SeriesGroup>) {
        val existing = repo.allMangas()
        val base = newManga.value
        var first: Long? = null
        for (s in groups) {
            val info = s.info
            val template = Manga(
                title = s.title.trim(),
                altTitle = info?.localizedSeries.orEmpty().trim(),
                summary = info?.summary.orEmpty().trim(),
                author = info?.writer.orEmpty(),
                artist = info?.penciller.orEmpty(),
                genres = info?.genre.orEmpty(),
                status = info?.status ?: MangaStatus.UNKNOWN,
                srcLang = Lang.entries.firstOrNull { info != null && it.code.equals(info.languageIso, ignoreCase = true) } ?: base.srcLang,
                tgtLang = base.tgtLang,
            )
            val id = saveChapters(list, s.chapters, existing.firstOrNull { it.title.trim().equals(template.title, ignoreCase = true) }?.id, template)
            if (first == null) first = id
        }
        saved = true
        ui.value = ImportUi.Done(first ?: return)
    }

    /** [groups] as chapters of [mangaId], or of a new manga made from [newOne]; returns the first chapter's id. */
    private suspend fun saveChapters(list: List<StagedPage>, groups: List<FolderChapter>, mangaId: Long?, newOne: Manga): Long? {
        var manga = mangaId
        var first: Long? = null
        for (g in groups) {
            val pagesOf = list.filter { FolderChapters.folderOf(it.name) == g.folder }.map { it.page }
            if (pagesOf.isEmpty()) continue
            val ch = Chapter(mangaId = 0, number = g.number.toFloatOrNull(), title = g.title)
            val id = if (manga == null) {
                repo.import(ImportTarget.NewManga(newOne, ch), pagesOf).also { manga = repo.getChapter(it)?.mangaId }
            } else repo.import(ImportTarget.NewChapter(manga!!, ch), pagesOf)
            if (first == null) first = id
        }
        return first
    }

    private suspend fun prefill(s: Staging) {
        val settings = settingsRepo.settings.first()
        val all = repo.allMangas()
        mangas.value = all
        if (appendTo != null) {
            appendChapter.value = repo.getChapter(appendTo)
            return
        }
        val info = s.comicInfo ?: ComicInfo()
        val guessed = ImportGuess.fromName(s.sourceName.orEmpty())
        val series = info.series.ifBlank { guessed.series }
        newManga.value = Manga(
            title = series,
            altTitle = info.localizedSeries.trim(),
            // Mihon writes the series' description here (a chapter rarely has its own).
            summary = info.summary.trim(),
            author = info.writer,
            artist = info.penciller,
            genres = info.genre,
            status = info.status, // Mihon writes it into every chapter it downloads
            srcLang = Lang.entries.firstOrNull { it.code.equals(info.languageIso, ignoreCase = true) } ?: settings.defaultSourceLang,
            tgtLang = settings.defaultTargetLang,
        )
        chapter.value = ChapterDraft(
            number = info.number.ifBlank { guessed.number },
            volume = info.volume?.toString() ?: guessed.volume,
            title = info.title,
            releaseDate = info.year?.let { y -> "%04d-%02d-%02d".format(y, info.month ?: 1, info.day ?: 1) }.orEmpty(),
        )
        val dest = startManga?.let { id -> all.firstOrNull { it.id == id } }
            ?: all.firstOrNull { series.isNotBlank() && it.title.equals(series, ignoreCase = true) }
        destManga.value = dest
        if (dest != null && chapter.value.number.isBlank()) suggestNumber(dest)
    }

    /** The next chapter number after the manga's highest. */
    private suspend fun suggestNumber(m: Manga) {
        if (chapter.value.number.isNotBlank()) return
        val last = repo.chapterList(m.id).mapNotNull { it.number }.maxOrNull() ?: return
        chapter.value = chapter.value.copy(number = Chapter.formatNumber(kotlin.math.floor(last) + 1))
    }

    override fun onCleared() {
        job?.cancel()
        if (!saved) {
            val left = pages.value
            if (left.isNotEmpty()) thread(name = "studio-discard") { importer.discard(left) }
        }
    }

    private fun Manga.normalized() = copy(title = title.trim())

    /** A new manga needs a title; the chapter fields must parse. */
    fun canSave(dest: Manga?, draft: ChapterDraft, manga: Manga): Boolean =
        pages.value.isNotEmpty() && (appendTo != null || bySeries() || ((draft.valid || splitting()) && (dest != null || manga.title.isNotBlank())))

    /** Whether Save makes one chapter per folder. */
    fun splitting(): Boolean = split.value && folders.value.size > 1

    /** Whether Save makes one manga per series (each series' title comes from the archive: none to type). */
    fun bySeries(): Boolean = appendTo == null && perSeries.value && series.value.size > 1
}

/**
 * A folder of an archive imported as its own chapter: the number and title read from the folder's name (the
 * number from its ComicInfo.xml when the name has none). [series] is the series it belongs to, from its ComicInfo or
 * the folder holding it; empty when that can't be told.
 */
data class FolderChapter(val folder: String, val number: String, val title: String, val pages: Int, val series: String = "")

/** Chapters of one series in a pack holding several, imported as its own manga. [info]: one of its ComicInfo.xml. */
data class SeriesGroup(val title: String, val chapters: List<FolderChapter>, val info: ComicInfo? = null) {
    val pages: Int get() = chapters.sumOf { it.pages }
}

/** Pages grouped by the folder they came from, for an archive that holds one chapter per folder. */
internal object FolderChapters {
    fun folderOf(name: String): String = name.replace('\\', '/').substringBeforeLast('/', "")

    /**
     * The folders, in page order, when the pages sit in two or more of them (else none: one chapter as before). A
     * folder's number comes from its name (`Chapter 66`, `c067`, `第66話`, a bare `66`), or from its [infos] entry when
     * the name has none; what follows the number is the title.
     *
     * The series of each: its ComicInfo's, else the name of the folder holding the chapter folders (Mihon's
     * `<source>/<series>/<chapter>.cbz`) — but only when every chapter sits one level under such a folder and none of
     * those folders is a volume (`Vol 2/c012`) or a bare number: then it would be one series in parts.
     */
    fun of(pages: List<StagedPage>, infos: Map<String, ComicInfo> = emptyMap()): List<FolderChapter> {
        val order = LinkedHashMap<String, Int>()
        for (p in pages) order[folderOf(p.name)] = (order[folderOf(p.name)] ?: 0) + 1
        if (order.size < 2 || order.keys.any { it.isEmpty() }) return emptyList()
        val parents = order.keys.map { it.substringBeforeLast('/', "") }
        val depth = order.keys.map { k -> k.count { it == '/' } }.toSet()
        val parentsAreSeries = depth.size == 1 && parents.none { it.isEmpty() } &&
            parents.none { p -> p.substringAfterLast('/').let { leaf -> VOLUME.containsMatchIn(leaf) || leaf.all(Char::isDigit) } }
        return order.map { (folder, count) ->
            val leaf = folder.substringAfterLast('/')
            val g = ImportGuess.fromName(leaf)
            val title = if (g.number.isNotEmpty()) leaf.substringAfter(g.number, "").trim().trimStart('-', '–', '—', ':', '.', '_').trim() else leaf
            val info = infos[folder]
            val series = info?.series.orEmpty().trim().ifEmpty {
                if (parentsAreSeries) folder.substringBeforeLast('/').substringAfterLast('/').trim() else ""
            }
            FolderChapter(folder, g.number.ifEmpty { info?.number.orEmpty().trim() }, title, count, series)
        }
    }

    /**
     * The series of [chapters], in the archive's order, when they belong to two or more (else none: one manga as
     * before). Series are told apart by name, ignoring case; a chapter whose series can't be told keeps the pack whole.
     */
    fun series(chapters: List<FolderChapter>, infos: Map<String, ComicInfo> = emptyMap()): List<SeriesGroup> {
        if (chapters.size < 2 || chapters.any { it.series.isBlank() }) return emptyList()
        val groups = chapters.groupBy { it.series.lowercase() }
        if (groups.size < 2) return emptyList()
        return groups.values.map { list -> SeriesGroup(list.first().series, list, list.firstNotNullOfOrNull { infos[it.folder] }) }
    }

    /** A volume folder's name: `Vol 2`, `Volume 3 - …`, `v02`, `Tome 4`, `Band 5`, `第3巻` (not `Band of …`, `Vinland …`). */
    private val VOLUME = Regex("(?i)^(?:vol(?:ume)?\\.?|v|tome|band)[._ -]?\\d+\\b|^vol(?:ume)?\\b|^第?\\d+\\s*巻")
}

/** Series, chapter and volume read from a file name: `Blue Box v02 c012.cbz`, `Chapter 12.5 - Title.pdf`. */
internal object ImportGuess {
    data class Guess(val series: String, val number: String, val volume: String)

    private val chapter = Regex("(?i)(?:\\bch(?:apter|ap)?|\\bc|第|#)\\s*[._-]?\\s*(\\d+(?:\\.\\d+)?)")
    private val volume = Regex("(?i)(?:\\bvol(?:ume)?|\\bv)\\s*[._-]?\\s*(\\d+)")
    private val trailingNumber = Regex("(\\d+(?:\\.\\d+)?)\\s*$")

    fun fromName(name: String): Guess {
        val base = name.replace('_', ' ').trim()
        val ch = chapter.find(base)
        val vol = volume.find(base)
        val trailing = if (ch == null && vol == null) trailingNumber.find(base) else null
        val number = (ch ?: trailing)?.groupValues?.get(1).orEmpty()
        val cut = listOfNotNull(ch?.range?.first, vol?.range?.first, trailing?.range?.first).minOrNull()
        val series = (if (cut != null) base.substring(0, cut) else base).trim().trimEnd('-', '–', '—', ',', '.', '(', '[', '#').trim()
        return Guess(series, plain(number), plain(vol?.groupValues?.get(1).orEmpty()))
    }

    /** `012` → `12`, `12.50` → `12.5`. */
    private fun plain(n: String): String = n.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString().orEmpty()
}
