package com.smnexstudio.panelglass.feature.studio

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.smnexstudio.panelglass.core.ui.widthClass
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.smnexstudio.panelglass.core.model.MangaStatus
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.db.ChapterSummary
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.IconTile
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PanelCard
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import com.smnexstudio.panelglass.feature.studio.cover.CoverSheet
import com.smnexstudio.panelglass.feature.studio.cover.CoverSource
import com.smnexstudio.panelglass.feature.studio.cover.Covers
import com.smnexstudio.panelglass.feature.studio.importer.ImportSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.smnexstudio.panelglass.core.ui.R as UiR

/** The manga's cover: the custom one, else the first page of the first chapter; null when there is no page yet. */
data class MangaCover(val file: File?, val custom: Boolean)

@HiltViewModel
class MangaViewModel @Inject constructor(
    private val repo: StudioRepository,
    val covers: Covers,
    handle: SavedStateHandle,
) : ViewModel() {
    val mangaId: Long = handle.get<String>("id")?.toLongOrNull() ?: 0L

    val manga: StateFlow<Manga?> = repo.manga(mangaId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The chapter order while a drag is in progress; null otherwise (the database order shows). */
    private val dragOrder = MutableStateFlow<List<Long>?>(null)

    val chapters: StateFlow<List<ChapterSummary>> = combine(repo.chapters(mangaId), dragOrder) { list, order ->
        if (order == null) list else {
            val rank = order.withIndex().associate { (i, id) -> id to i }
            list.sortedBy { rank[it.chapter.id] ?: Int.MAX_VALUE }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cover: StateFlow<MangaCover> = combine(repo.chapters(mangaId), covers.version) { list, _ ->
        val custom = covers.custom(mangaId)
        MangaCover(custom ?: list.firstNotNullOfOrNull { it.coverFile }?.let(repo.files::file), custom != null)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MangaCover(null, false))

    /** The picture picked for the cover could not be read. */
    val coverFailed = MutableStateFlow(false)

    /** Other manga a chapter can move to, loaded when the sheet opens. */
    val otherMangas = MutableStateFlow<List<Manga>>(emptyList())

    fun file(rel: String): File = repo.files.file(rel)

    fun move(from: Any, to: Any) {
        val ids = dragOrder.value ?: chapters.value.map { it.chapter.id }
        val a = ids.indexOf(from as Long)
        val b = ids.indexOf(to as Long)
        dragOrder.value = ids.moved(a, b)
    }

    fun drop() {
        val order = dragOrder.value ?: return
        viewModelScope.launch {
            repo.reorderChapters(mangaId, order)
            dragOrder.value = null
        }
    }

    /** Every chapter with its pages, for the cover sheet's page picker. */
    suspend fun coverChoices(): List<Pair<Chapter, List<StudioPage>>> =
        repo.chapterList(mangaId).map { it to repo.pageList(it.id) }.filter { it.second.isNotEmpty() }

    fun saveCover(source: CoverSource, biasX: Float, biasY: Float) = viewModelScope.launch {
        coverFailed.value = !covers.save(mangaId, source, biasX, biasY)
    }

    fun autoCover() = viewModelScope.launch { covers.clear(mangaId) }

    fun save(m: Manga) = viewModelScope.launch { repo.updateManga(m) }
    fun save(c: Chapter) = viewModelScope.launch { repo.updateChapter(c) }
    fun deleteChapter(c: Chapter) = viewModelScope.launch { repo.deleteChapter(c) }
    fun deleteManga(then: () -> Unit) = viewModelScope.launch { manga.value?.let { repo.deleteManga(it) }; then() }
    fun loadOthers() = viewModelScope.launch { otherMangas.value = repo.allMangas().filter { it.id != mangaId } }
    fun moveChapter(c: Chapter, to: Manga) = viewModelScope.launch { repo.moveChapter(c.id, to.id) }
}

private enum class ChapterAction { EDIT, MOVE, DELETE }

@Composable
fun MangaScreen(
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onImport: (ImportSource, mangaId: Long) -> Unit,
    onExport: (mangaId: Long) -> Unit,
    vm: MangaViewModel = hiltViewModel(),
) {
    val manga by vm.manga.collectAsStateWithLifecycle()
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val cover by vm.cover.collectAsStateWithLifecycle()
    val coverFailed by vm.coverFailed.collectAsStateWithLifecycle()
    val others by vm.otherMangas.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var changingCover by remember { mutableStateOf(false) }
    var choosingSource by remember { mutableStateOf(false) }
    var deletingManga by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Chapter?>(null) }
    var editingChapter by remember { mutableStateOf<Chapter?>(null) }
    var movingChapter by remember { mutableStateOf<Chapter?>(null) }
    var deletingChapter by remember { mutableStateOf<Chapter?>(null) }
    val m = manga ?: return

    val grid = rememberLazyGridState()
    val reorder = rememberGridReorder(grid, movable = { it is Long }, onMove = vm::move, onDrop = vm::drop)
    val pages = chapters.sumOf { it.pageCount }
    val reviewed = chapters.sumOf { it.reviewedCount }

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current)) {
        StudioTopBar("", onBack) {
            IconButton(onClick = { deletingManga = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(UiR.string.studio_delete_manga), tint = Tokens.InkSoft)
            }
        }
        // The manga's details: the whole top of the list on a phone, a column of their own beside the chapters on a
        // tablet or a laptop.
        val details: @Composable ColumnScope.() -> Unit = {
            Hero(m, cover, onChangeCover = { changingCover = true })
            if (m.genres.isNotBlank()) Genres(m.genres)
            Stats(chapters.size, pages, reviewed)
            SummaryCard(m, onEdit = { editing = true }, onChangeCover = { changingCover = true })
            if (coverFailed) Text(
                stringResource(UiR.string.studio_cover_failed), style = MaterialTheme.typography.bodySmall, color = Tokens.Error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(stringResource(UiR.string.studio_import_chapter), Icons.Outlined.FileDownload, filled = true, modifier = Modifier.weight(1f)) { choosingSource = true }
                BigButton(stringResource(UiR.string.studio_export), Icons.Outlined.FileUpload, filled = false, enabled = pages > 0, modifier = Modifier.weight(1f)) { onExport(m.id) }
            }
        }
        val chapterList: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
            item(key = "label") {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionLabel(stringResource(UiR.string.studio_chapters_section), Modifier.weight(1f))
                    if (chapters.size > 1) Text(
                        stringResource(UiR.string.studio_reorder_hint), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint,
                        modifier = Modifier.padding(end = 20.dp, bottom = 8.dp),
                    )
                }
            }
            if (chapters.isEmpty()) item(key = "noChapters") {
                Text(stringResource(UiR.string.studio_no_chapters), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            items(chapters, key = { it.chapter.id }) { c ->
                Box(reorder.itemModifier(c.chapter.id)) {
                    ChapterRow(c, vm::file, onOpen = { onOpenChapter(c.chapter.id) }, onMenu = { menuFor = c.chapter })
                }
            }
        }
        if (widthClass().wide) Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Column(Modifier.width(440.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { details() }
            LazyVerticalGrid(
                GridCells.Fixed(1), state = grid, modifier = Modifier.weight(1f).fillMaxHeight().reorderable(reorder),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) { chapterList() }
        } else LazyVerticalGrid(
            GridCells.Fixed(1), state = grid, modifier = Modifier.fillMaxSize().reorderable(reorder),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "details") { Column { details() } }
            chapterList()
        }
    }

    if (changingCover) {
        val choices by produceState<List<Pair<Chapter, List<StudioPage>>>?>(null) { value = vm.coverChoices() }
        choices?.let { list ->
            CoverSheet(
                covers = vm.covers,
                current = cover.file?.let { CoverSource.Page(it) },
                chapters = list, pageFile = vm::file,
                onSave = { src, bx, by -> changingCover = false; vm.saveCover(src, bx, by) },
                onAuto = { changingCover = false; vm.autoCover() },
                onDismiss = { changingCover = false },
            )
        }
    }
    if (editing) MangaSheet(m, stringResource(UiR.string.studio_edit_manga), onDismiss = { editing = false }) { edited, _ -> editing = false; vm.save(edited) }
    if (choosingSource) ImportSourceSheet(onPick = { onImport(it, m.id) }, onDismiss = { choosingSource = false })
    if (deletingManga) ConfirmDialog(
        stringResource(UiR.string.studio_delete_manga), stringResource(UiR.string.studio_delete_manga_confirm),
        stringResource(UiR.string.action_delete), onDismiss = { deletingManga = false },
    ) { vm.deleteManga(onBack) }
    menuFor?.let { c ->
        val labels = mapOf(
            ChapterAction.EDIT to stringResource(UiR.string.studio_edit_details),
            ChapterAction.MOVE to stringResource(UiR.string.studio_move_to_manga),
            ChapterAction.DELETE to stringResource(UiR.string.studio_delete_chapter),
        )
        ChoiceSheet<ChapterAction?>(
            chapterName(c), ChapterAction.entries, null, render = { it?.let(labels::getValue).orEmpty() },
            onPick = { a ->
                when (a) {
                    ChapterAction.EDIT -> editingChapter = c
                    ChapterAction.MOVE -> { vm.loadOthers(); movingChapter = c }
                    ChapterAction.DELETE -> deletingChapter = c
                    null -> Unit
                }
            },
            onDismiss = { menuFor = null },
        )
    }
    editingChapter?.let { c -> ChapterSheet(c, m.tgtLang, onDismiss = { editingChapter = null }) { editingChapter = null; vm.save(it) } }
    movingChapter?.let { c ->
        ChoiceSheet<Manga?>(
            stringResource(UiR.string.studio_move_to_manga), others, null, render = { it?.title.orEmpty() },
            onPick = { to -> to?.let { vm.moveChapter(c, it) } }, onDismiss = { movingChapter = null },
        )
    }
    deletingChapter?.let { c ->
        ConfirmDialog(
            stringResource(UiR.string.studio_delete_chapter), stringResource(UiR.string.studio_delete_chapter_confirm),
            stringResource(UiR.string.action_delete), onDismiss = { deletingChapter = null },
        ) { vm.deleteChapter(c) }
    }
}

/** Cover on the left (with a camera button to change it), the manga's details on the right. */
@Composable
private fun Hero(m: Manga, cover: MangaCover, onChangeCover: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Box(Modifier.size(width = 132.dp, height = 198.dp)) {
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.matchParentSize().clip(shape).background(Tokens.Border).border(1.dp, Tokens.Border, shape).clickable(onClick = onChangeCover)) {
                if (cover.file != null) PageThumb(cover.file, Modifier.matchParentSize(), targetDp = 220)
                else Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { IconTile(m.title, size = 56, radius = 14) }
                if (cover.file != null && !cover.custom) Badge(stringResource(UiR.string.studio_cover_page_badge), Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp).clip(CircleShape).background(Tokens.Yellow)
                    .border(2.dp, Tokens.Card, CircleShape).clickable(onClick = onChangeCover),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(UiR.string.studio_cover_title), tint = Tokens.Ink, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 22.sp, lineHeight = 27.sp, color = Tokens.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (m.altTitle.isNotBlank()) Text(m.altTitle, fontFamily = Jakarta, fontSize = 14.sp, color = Tokens.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Detail(stringResource(UiR.string.studio_field_author), m.author, names = true)
                Detail(stringResource(UiR.string.studio_field_artist), m.artist, names = true)
                Detail(stringResource(UiR.string.studio_field_status), statusName(m.status), dot = statusColor(m.status))
            }
            Spacer(Modifier.height(12.dp))
            LangChips(m)
        }
    }
}

/**
 * "AUTHOR  qwerty": a small capitalised label beside its value; nothing when the value is blank. With [names],
 * several names (`A, B`) read "A, B": the comma is what tells two names from one long name that wraps.
 */
@Composable
private fun Detail(label: String, value: String, dot: Color? = null, names: Boolean = false) {
    val text = if (names) People.split(value).joinToString(", ") else value.trim()
    if (text.isEmpty()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label.uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, letterSpacing = 1.sp,
            color = Tokens.InkFaint, maxLines = 1, modifier = Modifier.widthIn(min = 66.dp).padding(end = 10.dp, top = 3.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 20.sp, color = Tokens.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The status dot: green ongoing, blue completed, orange on hiatus, red cancelled, grey not set. */
@Composable
private fun statusColor(s: MangaStatus): Color = when (s) {
    MangaStatus.ONGOING -> Color(0xFF2E8B57)
    MangaStatus.COMPLETED -> Tokens.SkyDeep
    MangaStatus.HIATUS -> Tokens.Orange
    MangaStatus.CANCELLED -> Tokens.Error
    MangaStatus.UNKNOWN -> Tokens.InkFaint
}

/** `JA → EN`: the source language dark, the target yellow; read out as the full names. */
@Composable
private fun LangChips(m: Manga) {
    val pair = langPair(m)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = pair }, verticalAlignment = Alignment.CenterVertically) {
        LangChip(m.srcLang.code.uppercase(), Tokens.Ink, Tokens.Card)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Tokens.InkSoft, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
        LangChip(m.tgtLang.code.uppercase(), Tokens.Yellow, Tokens.Ink)
    }
}

@Composable
private fun LangChip(code: String, bg: Color, fg: Color) {
    Text(
        code, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp).clearAndSetSemantics {},
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Genres(genres: String) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        genres.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
            Text(
                g, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.Ink,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Tokens.Ink.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Stats(chapters: Int, pages: Int, reviewed: Int) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TokenCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Stat(chapters.toString(), stringResource(UiR.string.studio_stat_chapters), Modifier.weight(1f))
                    Stat(pages.toString(), stringResource(UiR.string.studio_stat_pages), Modifier.weight(1f))
                    Stat("$reviewed/$pages", stringResource(UiR.string.studio_stat_reviewed), Modifier.weight(1f))
                }
                if (pages > 0) ProgressBar(reviewed.toFloat() / pages, Modifier.padding(top = 10.dp), height = 6.dp)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 20.sp, color = Tokens.Ink)
        Text(label, fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
    }
}

@Composable
private fun SummaryCard(m: Manga, onEdit: () -> Unit, onChangeCover: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TokenCard {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
                Text(stringResource(UiR.string.studio_field_summary), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.InkSoft)
                Text(
                    m.summary.ifBlank { stringResource(UiR.string.studio_summary_empty) }, style = MaterialTheme.typography.bodyMedium,
                    color = if (m.summary.isBlank()) Tokens.InkFaint else Tokens.Ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                TextAction(stringResource(UiR.string.studio_edit_details), onClick = onEdit)
                TextAction(stringResource(UiR.string.studio_cover_title), onClick = onChangeCover)
            }
        }
    }
}

/** A wide button: yellow and filled, or outlined. */
@Composable
private fun BigButton(text: String, icon: ImageVector, filled: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val fg = if (enabled) Tokens.Ink else Tokens.InkFaint
    Row(
        modifier.height(50.dp).clip(shape)
            .then(if (filled) Modifier.background(if (enabled) Tokens.Yellow else Tokens.Border) else Modifier.border(1.5.dp, fg, shape))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 14.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChapterRow(c: ChapterSummary, file: (String) -> File, onOpen: () -> Unit, onMenu: () -> Unit) {
    PanelCard(onClick = onOpen) {
        Icon(Icons.Filled.DragIndicator, contentDescription = null, tint = Tokens.InkFaint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(Tokens.Border)) {
            c.coverFile?.let { PageThumb(file(it), Modifier.matchParentSize(), targetDp = 56) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chapterName(c.chapter), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.pageCount > 0 && c.reviewedCount == c.pageCount) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(18.dp).clip(CircleShape).background(Tokens.Yellow), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(UiR.string.studio_stat_reviewed), tint = Tokens.Ink, modifier = Modifier.size(12.dp))
                    }
                }
            }
            Text(
                pluralStringResource(UiR.plurals.studio_pages, c.pageCount, c.pageCount) + " · " +
                    stringResource(UiR.string.studio_reviewed, c.reviewedCount, c.pageCount) +
                    (c.chapter.volume?.let { " · " + stringResource(UiR.string.studio_volume_label, it) } ?: ""),
                fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (c.pageCount > 0) ProgressBar(c.reviewedCount.toFloat() / c.pageCount, Modifier.padding(top = 6.dp))
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(UiR.string.cd_more), tint = Tokens.InkSoft)
        }
    }
}
