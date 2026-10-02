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
    /** Pages are being copied in; [total] is 0 when unknown (a CBZ is read as a stream). */
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
                    ImportSource.CBZ -> importer.cbz(uris.first(), progress)
                }
                prefill(staging)
                pages.value = staging.pages
                folders.value = if (appendTo == null) FolderChapters.of(staging.pages) else emptyList()
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
        var mangaId = destManga.value?.id
        var first: Long? = null
        for (g in groups) {
            val pagesOf = list.filter { FolderChapters.folderOf(it.name) == g.folder }.map { it.page }
            if (pagesOf.isEmpty()) continue
            val ch = Chapter(mangaId = 0, number = g.number.toFloatOrNull(), title = g.title)
            val id = if (mangaId == null) {
                repo.import(ImportTarget.NewManga(newManga.value.normalized(), ch), pagesOf).also { mangaId = repo.getChapter(it)?.mangaId }
            } else repo.import(ImportTarget.NewChapter(mangaId!!, ch), pagesOf)
            if (first == null) first = id
        }
        saved = true
        ui.value = ImportUi.Done(first ?: return)
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
            author = info.writer,
            artist = info.penciller,
            genres = info.genre,
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
        pages.value.isNotEmpty() && (appendTo != null || ((draft.valid || splitting()) && (dest != null || manga.title.isNotBlank())))

    /** Whether Save makes one chapter per folder. */
    fun splitting(): Boolean = split.value && folders.value.size > 1
}

/** A folder of an archive imported as its own chapter: the number and title read from the folder's name. */
data class FolderChapter(val folder: String, val number: String, val title: String, val pages: Int)

/** Pages grouped by the folder they came from, for an archive that holds one chapter per folder. */
internal object FolderChapters {
    fun folderOf(name: String): String = name.replace('\\', '/').substringBeforeLast('/', "")

    /**
     * The folders, in page order, when the pages sit in two or more of them (else none: one chapter as before). A
     * folder's number comes from its name (`Chapter 66`, `c067`, `第66話`, a bare `66`); what follows it is the title.
     */
    fun of(pages: List<StagedPage>): List<FolderChapter> {
        val order = LinkedHashMap<String, Int>()
        for (p in pages) order[folderOf(p.name)] = (order[folderOf(p.name)] ?: 0) + 1
        if (order.size < 2 || order.keys.any { it.isEmpty() }) return emptyList()
        return order.map { (folder, count) ->
            val leaf = folder.substringAfterLast('/')
            val g = ImportGuess.fromName(leaf)
            val title = if (g.number.isNotEmpty()) leaf.substringAfter(g.number, "").trim().trimStart('-', '–', '—', ':', '.', '_').trim() else leaf
            FolderChapter(folder, g.number, title, count)
        }
    }
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
