package com.smnexstudio.panelglass.feature.studio

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.smnexstudio.panelglass.core.ui.widthClass
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.feature.studio.translate.ChapterRun
import com.smnexstudio.panelglass.feature.studio.translate.StudioTranslator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.DashedRow
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.smnexstudio.panelglass.core.ui.R as UiR

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChapterViewModel @Inject constructor(
    private val repo: StudioRepository,
    private val translator: StudioTranslator,
    private val packs: LanguagePackStore,
    settingsRepo: SettingsRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    val chapterId: Long = handle.get<String>("id")?.toLongOrNull() ?: 0L

    val chapter: StateFlow<Chapter?> = repo.chapter(chapterId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val manga: StateFlow<Manga?> = chapter.filterNotNull().flatMapLatest { repo.manga(it.mangaId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val dragOrder = MutableStateFlow<List<Long>?>(null)
    val pages: StateFlow<List<StudioPage>> = combine(repo.pages(chapterId), dragOrder) { list, order ->
        if (order == null) list else {
            val rank = order.withIndex().associate { (i, id) -> id to i }
            list.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selected = MutableStateFlow<Set<Long>?>(null)
    /** Chapters of the same manga the selection can move to. */
    val otherChapters = MutableStateFlow<List<Chapter>>(emptyList())

    fun file(rel: String): File = repo.files.file(rel)

    fun move(from: Any, to: Any) {
        val ids = dragOrder.value ?: pages.value.map { it.id }
        dragOrder.value = ids.moved(ids.indexOf(from as Long), ids.indexOf(to as Long))
    }

    fun drop() {
        val order = dragOrder.value ?: return
        viewModelScope.launch {
            repo.reorderPages(chapterId, order)
            dragOrder.value = null
        }
    }

    fun toggle(id: Long) {
        val s = selected.value.orEmpty()
        selected.value = if (id in s) s - id else s + id
    }

    fun startSelecting() { selected.value = emptySet() }
    fun stopSelecting() { selected.value = null }

    fun deleteSelected() = viewModelScope.launch {
        repo.deletePages(selected.value.orEmpty())
        selected.value = null
    }

    fun loadOtherChapters() = viewModelScope.launch {
        val c = chapter.value ?: return@launch
        otherChapters.value = repo.chapterList(c.mangaId).filter { it.id != chapterId }
    }

    fun moveSelected(to: Chapter) = viewModelScope.launch {
        repo.movePages(pages.value.map { it.id }.filter { it in selected.value.orEmpty() }, to.id)
        selected.value = null
    }

    fun save(c: Chapter) = viewModelScope.launch { repo.updateChapter(c) }

    // ---- translation -----------------------------------------------------------------------------

    val engine: StateFlow<EngineId> = settingsRepo.settings.map { it.engineId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineId.GOOGLE)
    val run: StateFlow<ChapterRun?> = translator.runs.map { it[chapterId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun translate() = translator.start(chapterId)
    fun translateAgain() = translator.start(chapterId, redo = true)
    fun cancel() = translator.cancel(chapterId)

    /** The missing Google Translate packs, then the run again (it picks up where it stopped). */
    fun downloadPacks(tags: List<String>) = viewModelScope.launch {
        if (packs.downloadNow(tags)) translator.start(chapterId)
    }

    fun bubbles(pageId: Long): Flow<List<Bubble>> = repo.bubbles(pageId)
}

@Composable
fun ChapterScreen(
    onBack: () -> Unit,
    onAddImages: (chapterId: Long) -> Unit,
    /** Settings; with an engine, straight to that engine's key sheet. */
    onOpenSettings: (keyFor: EngineId?) -> Unit,
    /** The review editor at a page (0-based). */
    onReview: (chapterId: Long, pageIndex: Int) -> Unit,
    /** The export flow with this chapter ticked. */
    onExport: (mangaId: Long, chapterId: Long) -> Unit,
    vm: ChapterViewModel = hiltViewModel(),
) {
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    val manga by vm.manga.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val others by vm.otherChapters.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var confirmAgain by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val c = chapter ?: return

    val grid = rememberLazyGridState()
    val reorder = rememberGridReorder(grid, movable = { it is Long && selected == null }, onMove = vm::move, onDrop = vm::drop)

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current)) {
        StudioTopBar(chapterName(c), onBack, subtitle = manga?.title) {
            if (pages.isNotEmpty()) IconButton(onClick = { onExport(c.mangaId, c.id) }) {
                Icon(Icons.Outlined.FileUpload, contentDescription = stringResource(UiR.string.studio_export), tint = Tokens.InkSoft)
            }
            IconButton(onClick = { editing = true }) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(UiR.string.studio_edit_details), tint = Tokens.InkSoft)
            }
        }
        val sel = selected
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel == null) {
                Spacer(Modifier.weight(1f))
                if (pages.isNotEmpty()) TextAction(stringResource(UiR.string.studio_select), onClick = vm::startSelecting)
            } else {
                IconButton(onClick = vm::stopSelecting) { Icon(Icons.Filled.Close, stringResource(UiR.string.action_cancel), tint = Tokens.Ink) }
                Text(stringResource(UiR.string.studio_selected_count, sel.size), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.weight(1f))
                if (sel.isNotEmpty()) {
                    TextAction(stringResource(UiR.string.studio_move_to_chapter), onClick = { vm.loadOtherChapters(); moving = true })
                    TextAction(stringResource(UiR.string.action_delete), Tokens.Error, onClick = { deleting = true })
                }
            }
        }
        // A tablet or a laptop keeps the translation card and "Add images" in a column beside the pages.
        val wide = widthClass().wide
        val translation: @Composable () -> Unit = {
            manga?.let { m ->
                TranslationCard(
                    engine = engine, src = m.srcLang, tgt = c.language ?: m.tgtLang, pages = pages, run = run,
                    onTranslate = vm::translate, onTranslateAgain = { confirmAgain = true }, onCancel = vm::cancel,
                    onChangeEngine = { onOpenSettings(null) }, onAddKey = { onOpenSettings(it) }, onDownloadPacks = vm::downloadPacks,
                    onReview = { onReview(c.id, pages.firstOrNull { !it.reviewed }?.index ?: 0) },
                )
            }
        }
        Row(Modifier.fillMaxSize()) {
        if (wide && sel == null) Column(
            Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            translation()
            DashedRow(stringResource(UiR.string.studio_add_images_row), onClick = { onAddImages(c.id) })
        }
        LazyVerticalGrid(
            GridCells.Adaptive(if (wide) 132.dp else 104.dp), state = grid, modifier = Modifier.weight(1f).fillMaxHeight().reorderable(reorder),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // In the grid from the first frame, even before the manga has loaded: an item added above the first visible
            // one later would be scrolled out of sight (the grid keeps its first visible item where it is).
            if (sel == null && !wide) item(key = "translate", span = { GridItemSpan(maxLineSpan) }) { translation() }
            if (pages.size > 1 && sel == null) item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(UiR.string.studio_reorder_hint), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint, modifier = Modifier.padding(horizontal = 4.dp))
            }
            items(pages, key = { it.id }) { p ->
                Box(reorder.itemModifier(p.id)) {
                    PageTile(
                        p, vm.file(p.file), selected = sel?.contains(p.id),
                        onClick = { if (sel != null) vm.toggle(p.id) else onReview(c.id, p.index) },
                    )
                }
            }
            if (sel == null && !wide) item(key = "add", span = { GridItemSpan(maxLineSpan) }) {
                DashedRow(stringResource(UiR.string.studio_add_images_row), onClick = { onAddImages(c.id) }, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        }
    }

    if (editing) ChapterSheet(c, manga?.tgtLang ?: com.smnexstudio.panelglass.core.model.Lang.EN, onDismiss = { editing = false }) { editing = false; vm.save(it) }
    if (moving) {
        val names = others.associate { it.id to chapterName(it) }
        ChoiceSheet<Chapter?>(
            stringResource(UiR.string.studio_move_to_chapter), others, null, render = { it?.let { ch -> names[ch.id] }.orEmpty() },
            onPick = { to -> to?.let(vm::moveSelected) }, onDismiss = { moving = false },
        )
    }
    if (confirmAgain) ConfirmDialog(
        stringResource(UiR.string.studio_translate_again), stringResource(UiR.string.studio_translate_again_confirm),
        stringResource(UiR.string.studio_translate_again), onDismiss = { confirmAgain = false },
    ) { vm.translateAgain() }
    if (deleting) ConfirmDialog(
        stringResource(UiR.string.studio_delete_pages), stringResource(UiR.string.studio_delete_pages_confirm),
        stringResource(UiR.string.action_delete), onDismiss = { deleting = false },
    ) { vm.deleteSelected() }
}

@Composable
private fun stageLabel(p: StudioPage): Pair<String, Boolean>? = when {
    p.failed != null -> stringResource(UiR.string.studio_stage_failed) to true
    StudioStage.REVIEWED in p.stages -> stringResource(UiR.string.studio_stage_reviewed) to true
    StudioStage.CLEANED in p.stages -> stringResource(UiR.string.studio_stage_cleaned) to false
    StudioStage.TRANSLATED in p.stages -> stringResource(UiR.string.studio_stage_translated) to false
    else -> null
}

@Composable
private fun PageTile(p: StudioPage, file: File, selected: Boolean?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.7f).clip(shape)
                .then(if (selected == true) Modifier.border(3.dp, Tokens.Ink, shape) else Modifier)
                .clickable(onClick = onClick),
        ) {
            PageThumb(file, Modifier.matchParentSize())
            stageLabel(p)?.let { (text, strong) -> Badge(text, Modifier.align(Alignment.BottomStart).padding(6.dp), strong) }
            if (selected == true) Icon(
                Icons.Filled.CheckCircle, contentDescription = stringResource(UiR.string.cd_selected), tint = Tokens.Ink,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            Text((p.index + 1).toString(), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.sp, color = Tokens.InkSoft)
            Spacer(Modifier.width(0.dp))
        }
    }
}
