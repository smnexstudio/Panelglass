package com.smnexstudio.panelglass.feature.studio

import com.smnexstudio.panelglass.core.ui.ToggleCardRow
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smnexstudio.panelglass.core.ui.Jakarta
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.smnexstudio.panelglass.core.ui.widthClass
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.ui.CardDivider
import com.smnexstudio.panelglass.core.ui.ChoiceSheet
import com.smnexstudio.panelglass.core.ui.LocalAppTheme
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.SectionLabel
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.TokenCard
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.core.ui.ValueRow
import com.smnexstudio.panelglass.core.ui.themeBackground
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.feature.studio.importer.ImportRefused
import com.smnexstudio.panelglass.feature.studio.importer.ImportSource
import com.smnexstudio.panelglass.feature.studio.importer.SkipReason
import com.smnexstudio.panelglass.feature.studio.importer.StagedPage
import com.smnexstudio.panelglass.core.ui.R as UiR

private val IMAGE_TYPES = arrayOf("image/png", "image/jpeg", "image/webp", "image/avif", "image/gif", "image/*")
private val PDF_TYPES = arrayOf("application/pdf")
/** File managers label `.cbz` inconsistently; the importer decides by the extension and refuses a plain `.zip`. */
private val CBZ_TYPES = arrayOf("application/vnd.comicbook+zip", "application/x-cbz", "application/zip", "application/octet-stream")

@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onDone: (chapterId: Long) -> Unit,
    vm: ImportViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) onBack() else vm.onPicked(uris)
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) onBack() else vm.onPicked(listOf(uri))
    }
    LaunchedEffect(Unit) {
        if (!vm.pickerLaunched) {
            vm.pickerLaunched = true
            when (vm.source) {
                ImportSource.IMAGES -> many.launch(IMAGE_TYPES)
                ImportSource.PDF -> one.launch(PDF_TYPES)
                ImportSource.CBZ -> one.launch(CBZ_TYPES)
            }
        }
    }
    LaunchedEffect(ui) { (ui as? ImportUi.Done)?.let { onDone(it.chapterId) } }
    BackHandler(ui is ImportUi.Reading) { vm.cancelReading(); onBack() }

    Column(Modifier.fillMaxSize().themeBackground(LocalAppTheme.current)) {
        StudioTopBar(stringResource(UiR.string.studio_import_title, sourceName(vm.source)), onBack = {
            vm.cancelReading()
            onBack()
        })
        when (val s = ui) {
            ImportUi.Picking, is ImportUi.Done -> Unit
            is ImportUi.Reading -> Reading(s) { vm.cancelReading(); onBack() }
            ImportUi.Review, ImportUi.Saving -> Review(vm, saving = s == ImportUi.Saving)
            is ImportUi.Failed -> Failed(s.reason, s.needed, s.free, onBack)
        }
    }
}

@Composable
private fun Reading(s: ImportUi.Reading, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        if (s.total > 0) {
            LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth(), color = Tokens.Ink, trackColor = Tokens.Border)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(UiR.string.studio_import_reading, s.done, s.total), color = Tokens.InkSoft, style = MaterialTheme.typography.bodyMedium)
        } else {
            CircularProgressIndicator(color = Tokens.Ink)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(UiR.string.studio_import_reading_count, s.done), color = Tokens.InkSoft, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))
        TextAction(stringResource(UiR.string.action_cancel), Tokens.InkSoft, onCancel)
    }
}

@Composable
private fun Failed(reason: ImportRefused.Reason, needed: Long, free: Long, onBack: () -> Unit) {
    val limits = remember { com.smnexstudio.panelglass.feature.studio.importer.ImportLimits() }
    val msg = when (reason) {
        ImportRefused.Reason.NOT_CBZ -> stringResource(UiR.string.studio_error_not_cbz)
        ImportRefused.Reason.NOT_A_ZIP -> stringResource(UiR.string.studio_error_not_zip)
        ImportRefused.Reason.ENCRYPTED_PDF -> stringResource(UiR.string.studio_error_encrypted_pdf)
        ImportRefused.Reason.NOT_A_PDF -> stringResource(UiR.string.studio_error_not_pdf)
        ImportRefused.Reason.TOO_LARGE -> stringResource(UiR.string.studio_error_too_large)
        ImportRefused.Reason.TOO_MANY_ENTRIES -> stringResource(UiR.string.studio_error_too_many_entries)
        ImportRefused.Reason.TOO_MANY_PAGES -> stringResource(UiR.string.studio_error_too_many_pages, limits.maxPages)
        ImportRefused.Reason.NO_PAGES -> stringResource(UiR.string.studio_error_no_pages)
        ImportRefused.Reason.UNREADABLE -> stringResource(UiR.string.studio_error_unreadable)
        ImportRefused.Reason.NO_SPACE -> noSpaceText(needed, free)
    }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text(msg, color = Tokens.Ink, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        PrimaryPill(stringResource(UiR.string.studio_ok).uppercase(), letterSpaced = true, onClick = onBack)
    }
}

@Composable
private fun skipReason(r: SkipReason): String = stringResource(
    when (r) {
        SkipReason.NOT_AN_IMAGE -> UiR.string.studio_skip_not_image
        SkipReason.TOO_LARGE -> UiR.string.studio_skip_too_large
        SkipReason.TOO_MANY_PIXELS -> UiR.string.studio_skip_too_many_pixels
        SkipReason.UNREADABLE -> UiR.string.studio_skip_unreadable
    },
)

@Composable
private fun Review(vm: ImportViewModel, saving: Boolean) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val skipped by vm.skipped.collectAsStateWithLifecycle()
    val dest by vm.destManga.collectAsStateWithLifecycle()
    val mangas by vm.mangas.collectAsStateWithLifecycle()
    val newManga by vm.newManga.collectAsStateWithLifecycle()
    val draft by vm.chapter.collectAsStateWithLifecycle()
    val appendChapter by vm.appendChapter.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val split by vm.split.collectAsStateWithLifecycle()
    var pickDest by remember { mutableStateOf(false) }
    var pickSrc by remember { mutableStateOf(false) }
    var pickTgt by remember { mutableStateOf(false) }

    val grid = rememberLazyGridState()
    val reorder = rememberGridReorder(grid, movable = { it is String && it.startsWith("pages/") && !saving }, onMove = vm::move, onDrop = {})

    // Where the pages go and the chapter's number: above the pages on a phone, in a column beside them on a wide screen.
    val wide = widthClass().wide
    val form: @Composable () -> Unit = {
        Column {
            val append = appendChapter
            if (vm.appendTo != null) {
                if (append != null) Text(stringResource(UiR.string.studio_import_append_to, chapterName(append)), color = Tokens.Ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
            } else {
                SectionLabel(stringResource(UiR.string.studio_import_destination), inset = 0.dp)
                TokenCard {
                    ValueRow(stringResource(UiR.string.studio_field_manga), dest?.title ?: stringResource(UiR.string.studio_import_new_manga), onClick = { pickDest = true })
                    if (dest == null) {
                        CardDivider()
                        ValueRow(stringResource(UiR.string.lang_translate_from), newManga.srcLang.uiName(), onClick = { pickSrc = true })
                        CardDivider()
                        ValueRow(stringResource(UiR.string.lang_translate_to), newManga.tgtLang.uiName(), onClick = { pickTgt = true })
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (dest == null) {
                    StudioField(newManga.title, { vm.newManga.value = newManga.copy(title = it) }, stringResource(UiR.string.studio_field_manga_title))
                    Spacer(Modifier.height(8.dp))
                }
                if (folders.size > 1) FolderSplit(folders, split) { vm.split.value = it }
                if (folders.size < 2 || !split) ChapterNumberFields(draft) { vm.chapter.value = it }
            }
            SectionLabel(pluralStringResource(UiR.plurals.studio_pages, pages.size, pages.size), inset = 0.dp)
            if (pages.size > 1) Text(stringResource(UiR.string.studio_import_order_hint), style = MaterialTheme.typography.bodySmall, color = Tokens.InkFaint)
            if (skipped.isNotEmpty()) {
                val reasons = SkipReason.entries.associateWith { skipReason(it) }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(UiR.string.studio_import_skipped, skipped.joinToString(", ") { (n, r) -> n.substringAfterLast('/') + " (" + reasons.getValue(r) + ")" }),
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Error,
                )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            if (wide) Column(
                Modifier.width(380.dp).fillMaxHeight().verticalScroll(rememberScrollState()).imePadding().padding(start = 16.dp, bottom = 96.dp),
            ) { form() }
            LazyVerticalGrid(
                GridCells.Adaptive(if (wide) 132.dp else 96.dp), state = grid, modifier = Modifier.weight(1f).fillMaxHeight().reorderable(reorder).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (wide) 16.dp else 0.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!wide) item(key = "dest", span = { GridItemSpan(maxLineSpan) }) { form() }
                items(pages, key = { it.page.file }) { p ->
                    Box(reorder.itemModifier(p.page.file)) { StagedTile(p, vm, pages.indexOf(p) + 1, enabled = !saving) }
                }
            }
        }
        // The page count and Save, always in reach under the pages.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Tokens.Card).navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Border))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(UiR.plurals.studio_pages, pages.size, pages.size), fontFamily = Jakarta, fontWeight = FontWeight.W700,
                    fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.weight(1f),
                )
                if (saving) CircularProgressIndicator(color = Tokens.Ink, modifier = Modifier.size(32.dp))
                else PrimaryPill(
                    stringResource(if (vm.appendTo != null) UiR.string.studio_add_pages else UiR.string.studio_save_chapter).uppercase(), letterSpaced = true,
                    enabled = vm.canSave(dest, draft, newManga), onClick = vm::save,
                )
            }
        }
    }

    if (pickDest) {
        val newLabel = stringResource(UiR.string.studio_import_new_manga)
        ChoiceSheet<Manga?>(
            stringResource(UiR.string.studio_import_destination), listOf<Manga?>(null) + mangas, dest,
            render = { it?.title ?: newLabel }, onPick = vm::pickDestination, onDismiss = { pickDest = false },
        )
    }
    if (pickSrc) ChoiceSheet(stringResource(UiR.string.lang_source), Lang.sourceChoices, newManga.srcLang, { it.uiName() }, onPick = { vm.newManga.value = newManga.copy(srcLang = it) }, onDismiss = { pickSrc = false })
    if (pickTgt) ChoiceSheet(stringResource(UiR.string.lang_target), Lang.targetChoices, newManga.tgtLang, { it.uiName() }, onPick = { vm.newManga.value = newManga.copy(tgtLang = it) }, onDismiss = { pickTgt = false })
}

@Composable
private fun StagedTile(p: StagedPage, vm: ImportViewModel, number: Int, enabled: Boolean) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).border(1.dp, Tokens.Border, RoundedCornerShape(10.dp))) {
            PageThumb(vm.file(p), Modifier.matchParentSize())
            Text(
                number.toString(), fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 11.sp, color = Tokens.Card,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Tokens.Ink).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            // A plain box: IconButton would grow the circle to its 48 dp touch minimum.
            if (enabled) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Tokens.Card.copy(alpha = 0.92f))
                    .clickable { vm.remove(p) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.cd_remove), tint = Tokens.Ink, modifier = Modifier.size(15.dp))
            }
        }
        Text(
            p.name.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, color = Tokens.InkFaint, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

/** An archive with one folder per chapter: the switch, and the chapters it will make. */
@Composable
private fun FolderSplit(folders: List<FolderChapter>, split: Boolean, onSplit: (Boolean) -> Unit) {
    TokenCard {
        ToggleCardRow(stringResource(UiR.string.studio_import_split), split, secondary = stringResource(UiR.string.studio_import_split_note, folders.size), onChange = onSplit)
        if (split) folders.forEach { f ->
            CardDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.number.takeIf { it.isNotEmpty() }?.let { stringResource(UiR.string.studio_chapter_label, it) + (if (f.title.isNotEmpty()) " · " + f.title else "") }
                        ?: f.folder.substringAfterLast('/'),
                    fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 14.sp, color = Tokens.Ink, modifier = Modifier.weight(1f),
                )
                Text(pluralStringResource(UiR.plurals.studio_pages, f.pages, f.pages), fontFamily = Jakarta, fontSize = 12.5.sp, color = Tokens.InkSoft)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
