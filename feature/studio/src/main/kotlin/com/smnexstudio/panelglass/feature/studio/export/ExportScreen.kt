package com.smnexstudio.panelglass.feature.studio.export

import androidx.compose.foundation.layout.widthIn
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.ui.CheckDisc
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.StickerDialog
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.themeBackground
import com.smnexstudio.panelglass.feature.studio.Badge
import com.smnexstudio.panelglass.feature.studio.PageThumb
import com.smnexstudio.panelglass.feature.studio.ProgressBar
import com.smnexstudio.panelglass.feature.studio.StorageSpace
import com.smnexstudio.panelglass.feature.studio.StudioTopBar
import com.smnexstudio.panelglass.feature.studio.chapterName
import com.smnexstudio.panelglass.feature.studio.noSpaceText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import com.smnexstudio.panelglass.core.ui.R as UiR

/** A chapter in the export list, with what decides whether it starts ticked and how big it is. */
data class ExportChapter(val summary: ChapterSummary, val translated: Int, val pngBytes: Long, val jpegBytes: Long) {
    val id: Long get() = summary.chapter.id
    fun bytes(q: PageQuality) = if (q == PageQuality.JPEG) jpegBytes else pngBytes
}

/** The two pop-ups over the chapter list. */
enum class ExportStep { CHOOSE, FORMAT, CONFIRM }

/**
 * The export flow (route `studio/export/{id}?chapter=`): tick the chapters, pick the format and page quality in a
 * pop-up, confirm the folder in another, then follow the export and see what it wrote. The export itself runs in the
 * app-scoped [StudioExporter], so leaving the screen does not stop it; coming back while it runs shows its progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repo: StudioRepository,
    private val exporter: StudioExporter,
    handle: SavedStateHandle,
) : ViewModel() {
    val mangaId: Long = handle.get<String>("id")?.toLongOrNull() ?: 0L
    private val preselect: Long? = handle.get<String>("chapter")?.toLongOrNull()

    val manga: StateFlow<Manga?> = repo.manga(mangaId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<ExportChapter>?> = repo.chapters(mangaId).mapLatest { list ->
        list.map { s ->
            val pages = repo.pageList(s.chapter.id)
            ExportChapter(
                s, pages.count { StudioStage.TRANSLATED in it.stages },
                exporter.estimate(pages, PageQuality.PNG), exporter.estimate(pages, PageQuality.JPEG),
            )
        }.also { if (selected.value == null) selected.value = defaultSelection(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ticked chapters; null until the list has loaded. */
    val selected = MutableStateFlow<Set<Long>?>(null)
    val step = MutableStateFlow(ExportStep.CHOOSE)
    val format = MutableStateFlow(ExportFormat.CBZ)
    val quality = MutableStateFlow(PageQuality.PNG)
    /** The destination's name; [custom] when it is a folder the user picked (else Downloads). */
    val folder = MutableStateFlow("")
    val custom = MutableStateFlow(false)
    val plan = MutableStateFlow<ExportPlan?>(null)
    /** The picked folder could not be kept. */
    val refused = MutableStateFlow(false)
    /** No app could open the folder. */
    val noFolderApp = MutableStateFlow(false)
    private var uri: String? = null

    /** The export of this manga, running or finished and not yet dismissed. */
    val run: StateFlow<ExportRun?> = exporter.run.map { it?.takeIf { r -> r.mangaId == mangaId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // A finished export of an earlier visit is not shown again: this visit starts fresh.
        if (exporter.run.value?.active != true) exporter.dismiss()
        viewModelScope.launch {
            format.value = exporter.format()
            quality.value = exporter.quality()
            loadFolder()
        }
    }

    private suspend fun loadFolder() {
        uri = exporter.folderFor(mangaId)
        custom.value = uri != null
        folder.value = exporter.folderLabel(uri) ?: uri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(':') }.orEmpty()
    }

    /** The one chapter the screen was opened for; else every chapter with something translated. */
    private fun defaultSelection(list: List<ExportChapter>): Set<Long> =
        preselect?.takeIf { id -> list.any { it.id == id } }?.let { setOf(it) }
            ?: list.filter { it.translated > 0 && it.summary.pageCount > 0 }.map { it.id }.toSet()

    fun toggle(id: Long) { selected.value = (selected.value ?: emptySet()).let { if (id in it) it - id else it + id } }

    fun toggleAll() {
        val all = chapters.value.orEmpty().filter { it.summary.pageCount > 0 }.map { it.id }.toSet()
        selected.value = if (selected.value == all) emptySet() else all
    }

    /** The ticked chapters in the manga's order, without empty ones. */
    private fun ids(): List<Long> {
        val sel = selected.value ?: return emptyList()
        return chapters.value.orEmpty().filter { it.id in sel && it.summary.pageCount > 0 }.map { it.id }
    }

    fun next() {
        if (ids().isNotEmpty()) step.value = ExportStep.FORMAT
    }

    fun toConfirm() {
        step.value = ExportStep.CONFIRM
        replan()
    }

    fun back() {
        step.value = when (step.value) {
            ExportStep.CONFIRM -> ExportStep.FORMAT
            else -> ExportStep.CHOOSE
        }
    }

    fun close() { step.value = ExportStep.CHOOSE }

    private fun replan() = viewModelScope.launch {
        plan.value = null
        plan.value = exporter.plan(mangaId, ids(), uri, format.value, quality.value)
    }

    fun setFormat(f: ExportFormat) { format.value = f }
    fun setQuality(q: PageQuality) { quality.value = q }

    /** Back to Downloads. */
    fun useDownloads() = viewModelScope.launch {
        exporter.choose(mangaId, null)
        refused.value = false
        loadFolder()
        replan()
    }

    /** The folder picker's answer: null when the user backed out. */
    fun picked(tree: Uri?) {
        if (tree == null) return
        viewModelScope.launch {
            refused.value = !exporter.choose(mangaId, tree.toString())
            loadFolder()
            replan()
        }
    }

    fun export() = viewModelScope.launch {
        val ids = ids()
        if (ids.isEmpty()) return@launch
        exporter.setFormat(format.value)
        exporter.setQuality(quality.value)
        step.value = ExportStep.CHOOSE
        exporter.start(mangaId, ids, uri, format.value, quality.value)
    }

    fun cancel() = exporter.cancel()

    /** Back to the chapter list for another export. */
    fun again() {
        exporter.dismiss()
        step.value = ExportStep.CHOOSE
    }

    fun done() = exporter.dismiss()

    fun openFolder(context: Context) = viewModelScope.launch {
        val opened = exporter.openIntents(mangaId, uri).any { intent ->
            try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        noFolderApp.value = !opened
    }

    fun file(rel: String): File = repo.files.file(rel)
}

@Composable
fun ExportScreen(onBack: () -> Unit, vm: ExportViewModel = hiltViewModel()) {
    val manga by vm.manga.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val m = manga ?: return
    // On a tablet or a laptop the steps sit in a centred column: a chapter list as wide as the screen reads badly.
    Box(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
            val r = run
            when {
                r == null -> ChooseChapters(vm, m, onBack)
                r.active -> Progress(vm, r, onBack)
                else -> Finished(vm, r, onDone = { vm.done(); onBack() })
            }
        }
    }
}

// ---- step 1: chapters -----------------------------------------------------------------------------

@Composable
private fun ChooseChapters(vm: ExportViewModel, m: Manga, onBack: () -> Unit) {
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val list = chapters ?: return
    val sel = selected.orEmpty()
    val picked = list.filter { it.id in sel && it.summary.pageCount > 0 }
    val exportable = list.filter { it.summary.pageCount > 0 }

    StudioTopBar(stringResource(UiR.string.studio_export), onBack, subtitle = m.title)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(UiR.string.studio_export_which), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 19.sp, color = Tokens.Ink)
                        Text(stringResource(UiR.string.studio_export_which_note), fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft)
                    }
                    if (exportable.size > 1) TextAction(
                        stringResource(if (picked.size == exportable.size) UiR.string.studio_select_none else UiR.string.studio_select_all),
                        onClick = vm::toggleAll,
                    )
                }
            }
            if (exportable.isEmpty()) item {
                Text(stringResource(UiR.string.studio_no_chapters), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft)
            }
            items(list, key = { it.id }) { c -> ChapterChoice(c, c.id in sel, vm::file) { vm.toggle(c.id) } }
        }
        // The running total and Next, pinned under the list.
        Row(
            Modifier.fillMaxWidth().background(Tokens.Card).padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val pages = picked.sumOf { it.summary.pageCount }
                Text(
                    pluralStringResource(UiR.plurals.studio_chapters, picked.size, picked.size) + " · " + pluralStringResource(UiR.plurals.studio_pages, pages, pages),
                    fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink,
                )
                Text(
                    stringResource(UiR.string.studio_export_about, bytes(picked.sumOf { it.bytes(quality) })),
                    fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft,
                )
            }
            NextPill(stringResource(UiR.string.studio_next), enabled = picked.isNotEmpty(), onClick = vm::next)
        }
    }
    when (step) {
        ExportStep.FORMAT -> FormatDialog(vm, picked.size, picked.sumOf { it.summary.pageCount })
        ExportStep.CONFIRM -> ConfirmStep(vm)
        ExportStep.CHOOSE -> Unit
    }
}

@Composable
private fun ChapterChoice(c: ExportChapter, on: Boolean, file: (String) -> File, onToggle: () -> Unit) {
    val s = c.summary
    val enabled = s.pageCount > 0
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Tokens.Card)
            .border(if (on) 2.dp else 1.dp, if (on) Tokens.Ink else Tokens.Border, shape)
            .clickable(enabled = enabled, onClick = onToggle).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckDisc(on)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(width = 36.dp, height = 50.dp).clip(RoundedCornerShape(6.dp)).background(Tokens.Border)) {
            s.coverFile?.let { PageThumb(file(it), Modifier.matchParentSize(), targetDp = 50) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chapterName(s.chapter), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = if (enabled) Tokens.Ink else Tokens.InkFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(UiR.plurals.studio_pages, s.pageCount, s.pageCount) + " · " + stringResource(UiR.string.studio_reviewed, s.reviewedCount, s.pageCount),
                fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft, maxLines = 1,
            )
        }
        when {
            s.pageCount == 0 -> Unit
            s.reviewedCount == s.pageCount -> Box(Modifier.size(22.dp).clip(CircleShape).background(Tokens.YellowTint), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(UiR.string.studio_stat_reviewed), tint = Tokens.Ink, modifier = Modifier.size(14.dp))
            }
            c.translated == 0 -> Badge(stringResource(UiR.string.studio_export_untranslated))
            else -> Badge(stringResource(UiR.string.studio_export_check), strong = true)
        }
    }
}

// ---- step 2: format (pop-up) ----------------------------------------------------------------------

@Composable
private fun FormatDialog(vm: ExportViewModel, chapters: Int, pages: Int) {
    val format by vm.format.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    StickerDialog(onDismiss = vm::close) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StepHeader(2, stringResource(UiR.string.studio_export_as), pluralStringResource(UiR.plurals.studio_chapters, chapters, chapters) + " · " + pluralStringResource(UiR.plurals.studio_pages, pages, pages))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatOption(ExportFormat.ZIP, Icons.Outlined.FolderZip, stringResource(UiR.string.studio_export_zip_note), format, vm::setFormat)
                FormatOption(ExportFormat.IMAGES, Icons.Outlined.Image, stringResource(UiR.string.studio_export_images_note), format, vm::setFormat)
                FormatOption(ExportFormat.CBZ, Icons.AutoMirrored.Outlined.MenuBook, stringResource(UiR.string.studio_export_cbz_note), format, vm::setFormat)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(UiR.string.studio_export_quality), fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 12.5.sp, color = Tokens.InkSoft)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tokens.Ink.copy(alpha = 0.06f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QualityTab(stringResource(UiR.string.studio_export_best), quality == PageQuality.PNG, Modifier.weight(1f)) { vm.setQuality(PageQuality.PNG) }
                    QualityTab(stringResource(UiR.string.studio_export_smaller), quality == PageQuality.JPEG, Modifier.weight(1f)) { vm.setQuality(PageQuality.JPEG) }
                }
            }
            DialogButtons(stringResource(UiR.string.studio_back), vm::back) { NextPill(stringResource(UiR.string.studio_next), onClick = vm::toConfirm) }
        }
    }
}

@Composable
private fun formatName(f: ExportFormat): String = stringResource(
    when (f) {
        ExportFormat.IMAGES -> UiR.string.studio_export_images
        ExportFormat.CBZ -> UiR.string.studio_export_format_cbz
        ExportFormat.ZIP -> UiR.string.studio_export_format_zip
    },
)

@Composable
private fun FormatOption(f: ExportFormat, icon: ImageVector, note: String, current: ExportFormat, onPick: (ExportFormat) -> Unit) {
    val on = f == current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) Tokens.YellowTint.copy(alpha = 0.45f) else Tokens.Card)
            .border(if (on) 2.dp else 1.5.dp, if (on) Tokens.Ink else Tokens.Border, shape).clickable { onPick(f) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Tokens.Yellow else Tokens.Ink.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(formatName(f), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 15.sp, color = Tokens.Ink)
            Text(note, fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
        }
        Box(Modifier.size(22.dp).clip(CircleShape).border(if (on) 7.dp else 2.dp, if (on) Tokens.Ink else Tokens.InkFaint, CircleShape))
    }
}

@Composable
private fun QualityTab(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Tokens.Card else Tokens.Card.copy(alpha = 0f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = if (on) Tokens.Ink else Tokens.InkSoft, maxLines = 1) }
}

// ---- step 3: confirm (pop-up) ---------------------------------------------------------------------

@Composable
private fun ConfirmStep(vm: ExportViewModel) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val custom by vm.custom.collectAsStateWithLifecycle()
    val refused by vm.refused.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    var choosingDestination by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.picked(it) }

    StickerDialog(onDismiss = vm::close) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            StepHeader(3, stringResource(UiR.string.studio_export_save_to), null)
            val p = plan
            val fits = p == null || StorageSpace.enough(p.bytes, p.freeBytes)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(16.dp))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Tokens.Sky.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = Tokens.SkyDeep, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(folder, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 15.sp, color = Tokens.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        p?.freeBytes?.let { Text(stringResource(UiR.string.studio_export_free, bytes(it)), fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft) }
                    }
                    TextAction(stringResource(UiR.string.studio_export_change), onClick = { choosingDestination = true })
                }
                if (p != null) Box(Modifier.fillMaxWidth().background(Tokens.Ink.copy(alpha = 0.03f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(tree(p), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp, color = Tokens.Ink)
                }
            }
            if (refused) Text(stringResource(UiR.string.studio_export_folder_refused), style = MaterialTheme.typography.bodySmall, color = Tokens.Error)
            if (p == null) Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Tokens.Ink, strokeWidth = 2.dp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SummaryRow(stringResource(UiR.string.studio_stat_chapters), pluralStringResource(UiR.plurals.studio_chapters, p.files.size, p.files.size) + " · " + pluralStringResource(UiR.plurals.studio_pages, p.pages, p.pages))
                    SummaryRow(stringResource(UiR.string.studio_export_row_format), formatName(format) + " · " + quality.name)
                    SummaryRow(stringResource(UiR.string.studio_export_row_size), stringResource(UiR.string.studio_export_about, bytes(p.bytes)))
                }
                val warnings = buildList {
                    if (p.unreviewed > 0) add(pluralStringResource(UiR.plurals.studio_export_unreviewed, p.unreviewed, p.unreviewed))
                    if (p.existing.isNotEmpty()) add(pluralStringResource(UiR.plurals.studio_export_overwrite, p.existing.size, p.existing.size))
                    if (!fits) add(noSpaceText(p.bytes, p.freeBytes ?: 0))
                }
                if (warnings.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (fits) Tokens.YellowTint else Tokens.Error.copy(alpha = 0.1f)).padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = if (fits) Tokens.Ink else Tokens.Error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(warnings.joinToString(" "), fontFamily = Jakarta, fontSize = 12.sp, lineHeight = 17.sp, color = if (fits) Tokens.Ink else Tokens.Error)
                }
            }
            DialogButtons(stringResource(UiR.string.studio_back), vm::back) {
                PrimaryPill(stringResource(UiR.string.studio_export).uppercase(), enabled = p != null && fits, letterSpaced = true, onClick = { vm.export() })
            }
        }
    }
    if (choosingDestination) {
        val downloads = stringResource(UiR.string.studio_export_downloads)
        val pick = stringResource(UiR.string.studio_export_choose_folder)
        ChoiceSheet(
            stringResource(UiR.string.studio_export_change_folder), listOf(false, true), custom,
            render = { if (it) pick else downloads },
            onPick = { folderPicked -> if (folderPicked) runCatching { picker.launch(null) } else vm.useDownloads() },
            onDismiss = { choosingDestination = false },
        )
    }
}

/** `Manga/` and the files it will hold, as a little tree; long lists end in "+ N more". */
@Composable
private fun tree(p: ExportPlan): String {
    val shown = if (p.files.size > MAX_TREE) p.files.take(MAX_TREE - 1) else p.files
    val more = p.files.size - shown.size
    val lines = shown.map { it } + if (more > 0) listOf(pluralStringResource(UiR.plurals.studio_export_more, more, more)) else emptyList()
    return p.mangaFolder + "/\n" + lines.mapIndexed { i, l -> (if (i == lines.lastIndex) "└─ " else "├─ ") + l }.joinToString("\n")
}

private const val MAX_TREE = 6

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink)
    }
}

@Composable
private fun StepHeader(n: Int, title: String, subtitle: String?) {
    Column {
        Text(stringResource(UiR.string.studio_export_step, n, 3).uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, letterSpacing = 1.4.sp, color = Tokens.InkFaint)
        Text(title, fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 21.sp, color = Tokens.Ink, modifier = Modifier.padding(top = 4.dp))
        subtitle?.let { Text(it, fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun DialogButtons(back: String, onBack: () -> Unit, primary: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { TextAction(back, Tokens.InkSoft, onBack) }
        primary()
    }
}

@Composable
private fun NextPill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.height(48.dp).clip(shape).background(if (enabled) Tokens.Yellow else Tokens.Border).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 14.sp, letterSpacing = 1.4.sp, color = if (enabled) Tokens.Ink else Tokens.InkFaint)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = if (enabled) Tokens.Ink else Tokens.InkFaint, modifier = Modifier.size(16.dp))
    }
}

// ---- progress -------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Progress(vm: ExportViewModel, run: ExportRun, onBack: () -> Unit) {
    val chapters by vm.chapters.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    BackHandler(onBack = onBack)
    val byId = chapters.orEmpty().associateBy { it.id }
    val fraction = if (run.total == 0) 0f else run.written.toFloat() / run.total

    StudioTopBar(stringResource(UiR.string.studio_exporting), onBack)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { fraction }, modifier = Modifier.size(150.dp), color = Tokens.YellowDeep,
                        trackColor = Tokens.Ink.copy(alpha = 0.08f), strokeWidth = 12.dp,
                    )
                    Text("${(fraction * 100).toInt()}%", fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 32.sp, color = Tokens.Ink)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(UiR.string.studio_export_pages_of, run.written, run.total), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 16.sp, color = Tokens.Ink)
                val current = run.chapterIds.getOrNull(run.chapter)?.let(byId::get)
                if (current != null) Text(
                    stringResource(UiR.string.studio_export_chapter_of, run.chapter + 1, run.chapterIds.size) + " · " + chapterName(current.summary.chapter),
                    fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                run.remainingMs(now)?.let {
                    Text(stringResource(UiR.string.studio_export_time_left, DateUtils.formatElapsedTime(maxOf(1, it / 1000))), fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft)
                }
            }
        }
        items(run.chapterIds.withIndex().toList(), key = { it.value }) { (i, id) ->
            val c = byId[id] ?: return@items
            val done = run.files.firstOrNull { it.chapterId == id }
            TokenCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(chapterName(c.summary.chapter), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val status = when {
                            done != null -> bytes(done.bytes)
                            i == run.chapter -> stringResource(UiR.string.studio_export_working, minOf(run.page + 1, run.pages), run.pages)
                            else -> stringResource(UiR.string.studio_export_waiting)
                        }
                        Text(status, fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft)
                        if (done == null && i == run.chapter && run.pages > 0) ProgressBar(run.page.toFloat() / run.pages, Modifier.padding(top = 6.dp))
                    }
                    if (done != null) Box(Modifier.size(22.dp).clip(CircleShape).background(Tokens.Yellow), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Tokens.Ink, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        item {
            Text(stringResource(UiR.string.studio_export_leave_note), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint, modifier = Modifier.padding(vertical = 8.dp))
        }
    }
    Row(Modifier.fillMaxWidth().background(Tokens.Card).padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { TextAction(stringResource(UiR.string.action_cancel), Tokens.Error, vm::cancel) }
        PrimaryPill(stringResource(UiR.string.studio_export_keep_background), onClick = onBack)
    }
}

// ---- done -----------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.Finished(vm: ExportViewModel, run: ExportRun, onDone: () -> Unit) {
    val context = LocalContext.current
    val plan by vm.plan.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val noApp by vm.noFolderApp.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    val ok = run.phase == ExportPhase.DONE
    val where = plan?.let { "$folder/${it.mangaFolder}" } ?: run.where

    StudioTopBar(stringResource(UiR.string.studio_export), onDone)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(if (ok) Tokens.Yellow else Tokens.Error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (ok) Icons.Filled.Check else Icons.Outlined.Info, contentDescription = null, tint = if (ok) Tokens.Ink else Tokens.Error, modifier = Modifier.size(36.dp)) }
                Spacer(Modifier.height(12.dp))
                Text(
                    when (run.phase) {
                        ExportPhase.DONE -> pluralStringResource(UiR.plurals.studio_export_done_title, run.files.size, run.files.size)
                        ExportPhase.CANCELLED -> stringResource(UiR.string.studio_export_cancelled)
                        else -> stringResource(UiR.string.studio_export_stopped)
                    },
                    fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 21.sp, color = Tokens.Ink,
                )
                val pages = run.files.sumOf { it.pages }
                val took = DateUtils.formatElapsedTime(maxOf(0, (run.finishedAt - run.startedAt) / 1000))
                Text(
                    pluralStringResource(UiR.plurals.studio_pages, pages, pages) + " · " + bytes(run.bytes) + " · " + took,
                    fontFamily = Jakarta, fontSize = 13.sp, color = Tokens.InkSoft, modifier = Modifier.padding(top = 4.dp),
                )
                if (run.phase == ExportPhase.FAILED) Text(
                    when (run.error) {
                        ExportError.FOLDER_GONE -> stringResource(UiR.string.studio_export_error_folder)
                        ExportError.PAGE_UNREADABLE -> stringResource(UiR.string.studio_export_error_page)
                        ExportError.NO_SPACE -> if (run.needBytes > 0) noSpaceText(run.needBytes, run.freeBytes) else stringResource(UiR.string.studio_storage_full)
                        else -> stringResource(UiR.string.studio_export_error_write)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Error, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (run.files.isNotEmpty()) item {
            TokenCard {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, contentDescription = null, tint = Tokens.SkyDeep, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(where, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = Tokens.Ink, modifier = Modifier.weight(1f))
                }
            }
        }
        items(run.files, key = { it.chapterId }) { f ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.5.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(bytes(f.bytes), fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft)
            }
        }
        if (noApp) item {
            Text(stringResource(UiR.string.studio_export_no_app, where), style = MaterialTheme.typography.bodySmall, color = Tokens.InkSoft, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Column(Modifier.fillMaxWidth().background(Tokens.Card).padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (run.files.isNotEmpty()) PrimaryPill(stringResource(UiR.string.studio_export_open_folder).uppercase(), letterSpaced = true, modifier = Modifier.fillMaxWidth(), onClick = { vm.openFolder(context) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextAction(stringResource(UiR.string.studio_export_again), onClick = vm::again)
            TextAction(stringResource(UiR.string.studio_done), Tokens.Ink, onDone)
        }
    }
}

@Composable
private fun bytes(n: Long): String = Formatter.formatShortFileSize(LocalContext.current, n)
