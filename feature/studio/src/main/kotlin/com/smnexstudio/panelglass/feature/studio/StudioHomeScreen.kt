package com.smnexstudio.panelglass.feature.studio

import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.StudioStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.mapLatest
import com.smnexstudio.panelglass.core.ui.widthClass
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.db.MangaSummary
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.IconTile
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SearchField
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import com.smnexstudio.panelglass.core.ui.uiName
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.smnexstudio.panelglass.core.ui.R as UiR

/** How the library is ordered. */
enum class MangaSort { NEWEST, TITLE }

/** A chapter with pages still to review, and the first of them (0-based) to open the editor at. */
data class ContinueItem(val manga: Manga, val chapter: Chapter, val page: Int, val pages: Int, val reviewed: Int, val thumb: File)

/** A manga in the library grid: its summary and its cover (the custom one, else the first page; null when neither). */
data class MangaTile(val summary: MangaSummary, val cover: File?, val custom: Boolean)

@HiltViewModel
class StudioHomeViewModel @Inject constructor(
    private val repo: StudioRepository,
    private val covers: Covers,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(MangaSort.NEWEST)

    /** Null while loading; the whole library is [all], what the search and order leave is [mangas]. */
    private val all: StateFlow<List<MangaTile>?> = combine(repo.mangas, covers.version) { list, _ ->
        list.map { m ->
            val custom = covers.custom(m.manga.id)
            MangaTile(m, custom ?: m.coverFile?.let(repo.files::file), custom != null)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val total: StateFlow<Int?> = all.map { it?.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mangas: StateFlow<List<MangaTile>?> = combine(all, query, sort) { list, q, s ->
        list?.let { sorted(filtered(it, q), s) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * "Continue reviewing" on wide screens: up to three chapters with something translated and a page not reviewed
     * yet, newest manga first, each with the first such page.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val continueItems: StateFlow<List<ContinueItem>> = repo.mangas.mapLatest { list ->
        val out = ArrayList<ContinueItem>()
        for (m in list.sortedByDescending { it.manga.createdAt }) {
            for (c in repo.chapterList(m.manga.id)) {
                if (out.size == 3) return@mapLatest out
                val pages = repo.pageList(c.id)
                val next = pages.indexOfFirst { !it.reviewed }
                if (next < 0 || pages.none { StudioStage.TRANSLATED in it.stages }) continue
                out += ContinueItem(m.manga, c, next, pages.size, pages.count { it.reviewed }, repo.files.file(pages[next].file))
            }
        }
        out
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    init {
        // Pages a killed import left behind; nothing can be importing before the Studio tab is first opened.
        viewModelScope.launch { repo.sweepOrphanFiles() }
    }

    /** Creates the manga, saves the [cover] picked for it (when any), then opens it. */
    fun create(manga: Manga, cover: Uri?, then: (Long) -> Unit) = viewModelScope.launch {
        val id = repo.createManga(manga)
        cover?.let { covers.save(id, CoverSource.Picked(it), 0f, 0f) }
        then(id)
    }

    companion object {
        /** Title or alternative title containing [q], case-insensitive; everything for a blank query. */
        fun filtered(list: List<MangaTile>, q: String): List<MangaTile> {
            val t = q.trim()
            if (t.isEmpty()) return list
            return list.filter { it.summary.manga.title.contains(t, true) || it.summary.manga.altTitle.contains(t, true) }
        }

        fun sorted(list: List<MangaTile>, s: MangaSort): List<MangaTile> = when (s) {
            MangaSort.NEWEST -> list.sortedByDescending { it.summary.manga.createdAt }
            MangaSort.TITLE -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.summary.manga.title })
        }
    }
}

@Composable
internal fun sourceName(s: ImportSource): String = stringResource(
    when (s) {
        ImportSource.IMAGES -> UiR.string.studio_source_images
        ImportSource.PDF -> UiR.string.studio_source_pdf
        ImportSource.CBZ -> UiR.string.studio_source_cbz
    },
)

@Composable
internal fun sourceNote(s: ImportSource): String = stringResource(
    when (s) {
        ImportSource.IMAGES -> UiR.string.studio_source_images_note
        ImportSource.PDF -> UiR.string.studio_source_pdf_note
        ImportSource.CBZ -> UiR.string.studio_source_cbz_note
    },
)

/** Picks what to import from. */
@Composable
internal fun ImportSourceSheet(onPick: (ImportSource) -> Unit, onDismiss: () -> Unit) {
    val names = ImportSource.entries.associateWith { sourceName(it) }
    val notes = ImportSource.entries.associateWith { sourceNote(it) }
    ChoiceSheet<ImportSource?>(
        stringResource(UiR.string.studio_import_from), ImportSource.entries, null,
        render = { it?.let(names::getValue).orEmpty() }, subtitle = { it?.let(notes::getValue) },
        onPick = { it?.let(onPick) }, onDismiss = onDismiss,
    )
}

@Composable
fun StudioHomeScreen(
    onOpenManga: (Long) -> Unit,
    onImport: (ImportSource) -> Unit,
    onOpenFonts: () -> Unit,
    /** The review editor at a chapter's page (0-based), from "Continue reviewing". */
    onReview: (chapterId: Long, page: Int) -> Unit = { _, _ -> },
    vm: StudioHomeViewModel = hiltViewModel(),
) {
    val wide = widthClass().wide
    val continueItems by vm.continueItems.collectAsStateWithLifecycle()
    val mangas by vm.mangas.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var choosing by remember { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(UiR.string.nav_studio), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 22.sp, color = Tokens.Ink)
                Text(stringResource(UiR.string.studio_subtitle), fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkFaint)
            }
            // A wide screen has room for the search in the title row.
            if (wide && (total ?: 0) > 0) {
                SearchField(query, { vm.query.value = it }, stringResource(UiR.string.studio_search_hint), onGo = {}, modifier = Modifier.width(380.dp))
                IconButton(onClick = { sorting = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(UiR.string.studio_sort), tint = Tokens.Ink)
                }
            }
            IconButton(onClick = onOpenFonts) {
                Icon(Icons.Filled.TextFields, contentDescription = stringResource(UiR.string.studio_my_fonts), tint = Tokens.Ink)
            }
            PrimaryPill(stringResource(UiR.string.studio_import).uppercase(), letterSpaced = true, onClick = { choosing = true })
        }
        // Search and order as soon as the library has a manga.
        if (!wide && (total ?: 0) > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(query, { vm.query.value = it }, stringResource(UiR.string.studio_search_hint), onGo = {}, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { sorting = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(UiR.string.studio_sort), tint = Tokens.Ink)
            }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(if (wide) 150.dp else 100.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (wide && query.isBlank() && continueItems.isNotEmpty()) item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                ContinueStrip(continueItems, onReview)
            }
            if (query.isBlank()) item(key = "new") { NewMangaTile { creating = true } }
            val list = mangas
            if (list != null && list.isEmpty()) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (query.isBlank()) stringResource(UiR.string.studio_empty) else stringResource(UiR.string.studio_no_match, query.trim()),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(list.orEmpty(), key = { it.summary.manga.id }) { m -> MangaCard(m) { onOpenManga(m.summary.manga.id) } }
        }
    }

    if (choosing) ImportSourceSheet(onPick = onImport, onDismiss = { choosing = false })
    if (sorting) {
        val names = mapOf(MangaSort.NEWEST to stringResource(UiR.string.studio_sort_newest), MangaSort.TITLE to stringResource(UiR.string.studio_sort_title))
        ChoiceSheet(stringResource(UiR.string.studio_sort), MangaSort.entries, sort, { names.getValue(it) }, onPick = { vm.sort.value = it }, onDismiss = { sorting = false })
    }
    if (creating) MangaSheet(
        initial = Manga(title = "", srcLang = settings.defaultSourceLang, tgtLang = settings.defaultTargetLang),
        title = stringResource(UiR.string.studio_new_manga),
        withCover = true,
        onDismiss = { creating = false },
        onSave = { m, cover -> creating = false; vm.create(m, cover, onOpenManga) },
    )
}

/**
 * The first tile of the grid: a dashed frame with a plus, exactly as tall as a manga card. The card's cover and text
 * block are laid out invisibly underneath, so the two match at any font size.
 */
@Composable
private fun NewMangaTile(onClick: () -> Unit) {
    val stroke = Tokens.YellowDeep
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Tokens.YellowTint.copy(alpha = 0.5f)).clickable(onClick = onClick)) {
        Column(Modifier.alpha(0f)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            CardText(" ", " ", " ", 0f)
        }
        Canvas(Modifier.matchParentSize()) {
            val r = 10.dp.toPx()
            drawRoundRect(
                stroke, cornerRadius = CornerRadius(r, r),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
            )
        }
        Column(Modifier.matchParentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Tokens.Yellow), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Tokens.Ink)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(UiR.string.studio_new_manga), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.5.sp, color = Tokens.Ink, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MangaCard(t: MangaTile, onClick: () -> Unit) {
    val m = t.summary
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Tokens.Border).border(1.dp, Tokens.Border, RoundedCornerShape(10.dp))) {
            if (t.cover != null) PageThumb(t.cover, Modifier.matchParentSize(), targetDp = 160)
            else Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { IconTile(m.manga.title, size = 40, radius = 10) }
            if (t.cover != null && !t.custom) Badge(stringResource(UiR.string.studio_cover_page_badge), Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
        CardText(
            m.manga.title,
            pluralStringResource(UiR.plurals.studio_chapters, m.chapterCount, m.chapterCount),
            stringResource(UiR.string.studio_reviewed, m.reviewedCount, m.pageCount),
            if (m.pageCount > 0) m.reviewedCount.toFloat() / m.pageCount else 0f,
        )
    }
}

/**
 * The text under a cover, always the same height so every tile in a row lines up: the title on two lines (a short
 * one keeps its second line empty), the chapters and the reviewed count on a line each (a third of the screen has
 * no room for both on one), and the progress bar.
 */
@Composable
private fun CardText(title: String, chapters: String, reviewed: String, progress: Float) {
    Spacer(Modifier.height(6.dp))
    Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, lineHeight = 16.sp, color = Tokens.Ink, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(chapters, fontFamily = Jakarta, fontSize = 11.sp, lineHeight = 14.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(reviewed, fontFamily = Jakarta, fontSize = 11.sp, lineHeight = 14.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    ProgressBar(progress, Modifier.padding(top = 4.dp), height = 3.dp)
}

/** Tablets and laptops: the chapters waiting for review, each opening the editor at its next page. */
@Composable
private fun ContinueStrip(items: List<ContinueItem>, onReview: (Long, Int) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(
            stringResource(UiR.string.studio_continue_reviewing).uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp,
            letterSpacing = 1.2.sp, color = Tokens.InkFaint, modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { c ->
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Tokens.Card).border(1.dp, Tokens.Border, RoundedCornerShape(16.dp))
                        .clickable { onReview(c.chapter.id, c.page) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(8.dp)).background(Tokens.Border)) {
                        PageThumb(c.thumb, Modifier.matchParentSize(), targetDp = 66)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.manga.title, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            chapterName(c.chapter) + " · " + stringResource(UiR.string.studio_continue_page, c.page + 1, c.pages),
                            fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (c.pages > 0) ProgressBar(c.reviewed.toFloat() / c.pages, Modifier.padding(top = 6.dp))
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Tokens.InkFaint)
                }
            }
            // Keep each card a third wide even when fewer than three chapters wait.
            repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** "Japanese → English" (the arrow follows the layout direction). */
@Composable
internal fun langPair(m: Manga): String {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return m.srcLang.uiName() + (if (rtl) " ← " else " → ") + m.tgtLang.uiName()
}
