package com.smnexstudio.panelglass.feature.studio.review

import com.smnexstudio.panelglass.core.ui.fonts.FontChoices
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.smnexstudio.panelglass.core.ui.WidthClass
import com.smnexstudio.panelglass.core.ui.widthClass
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.model.BrushMode
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.render.FRect
import com.smnexstudio.panelglass.core.render.PolygonFit
import com.smnexstudio.panelglass.core.render.SfxRenderer
import com.smnexstudio.panelglass.core.ui.Jakarta
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.feature.studio.failureText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.smnexstudio.panelglass.core.ui.R as UiR

/**
 * The tool that is open: its options replace the bubble editor under the page. Add, reshape and brush also own
 * one-finger gestures on the canvas; text and fill leave it selecting, so another bubble can be styled next.
 */
private enum class Tool { NONE, ADD, TEXT, FILL, RESHAPE, BRUSH }

/**
 * The review editor (docs/STUDIO_PLAN.md › UI › ReviewScreen): the page on top with the layer toggle and the tool row
 * under it, the bubble editor below (55 / 45), or the page alone in focus mode. Every edit is saved as it is made and
 * can be undone.
 */
@Composable
fun ReviewScreen(onBack: () -> Unit, onOpenFonts: () -> Unit, vm: ReviewViewModel = hiltViewModel()) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    val manga by vm.manga.collectAsStateWithLifecycle()
    val pageIndex by vm.pageIndex.collectAsStateWithLifecycle()
    val bubbles by vm.bubbles.collectAsStateWithLifecycle()
    val selectedId by vm.selected.collectAsStateWithLifecycle()
    val original by vm.tiles.collectAsStateWithLifecycle()
    val clean by vm.cleanTiles.collectAsStateWithLifecycle()
    val final by vm.finalTiles.collectAsStateWithLifecycle()
    val layer by vm.layer.collectAsStateWithLifecycle()
    val cleaning by vm.cleaning.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failure by vm.failure.collectAsStateWithLifecycle()
    val draftSource by vm.draftSource.collectAsStateWithLifecycle()
    val draftTranslation by vm.draftTranslation.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val draftSfx by vm.draftSfx.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val tgt by vm.target.collectAsStateWithLifecycle()

    val canvas = remember { CanvasState(vm.memory.zoom(vm.chapterId)) }
    var tab by rememberSaveable { mutableStateOf(EditorTab.BUBBLE) }
    var focus by rememberSaveable { mutableStateOf(false) }
    var floating by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(Tool.NONE) }
    var inPlace by remember { mutableStateOf<Long?>(null) }
    var brushMode by remember { mutableStateOf(BrushMode.CLEAN) }
    var brushRadius by remember { mutableFloatStateOf(20f) }
    var addSfx by remember { mutableStateOf(false) }

    val shown = bubbles.filter { !it.ignored }
    val sfxSelected = bubbles.firstOrNull { it.id == selectedId && it.kind == com.smnexstudio.panelglass.core.model.RegionKind.SFX }
    val sfxFrames = remember(bubbles, draftSfx, tgt) {
        bubbles.filter { it.kind == com.smnexstudio.panelglass.core.model.RegionKind.SFX && !it.ignored }
            .mapNotNull { b -> SfxRenderer.frame(b.copy(sfx = draftSfx[b.id] ?: b.sfx), tgt)?.let { b.id to it } }.toMap()
    }
    val sfxFrame = if (tool == Tool.TEXT) sfxSelected?.let { b -> SfxRenderer.frame(b.copy(sfx = draftSfx[b.id] ?: b.sfx), tgt) } else null
    val selected = bubbles.firstOrNull { it.id == selectedId }
    fun texts(b: Bubble) = BubbleTexts(draftSource[b.id] ?: b.sourceText, draftTranslation[b.id] ?: b.translatedText)

    // A page opens on its first bubble in reading order; the canvas keeps the zoom and brings it into view.
    LaunchedEffect(page?.id, bubbles.isNotEmpty(), original) {
        if (selectedId == null && original != null) shown.firstOrNull()?.let { vm.select(it.id) }
    }
    // The canvas follows the selection (a list tap, ▲ ▼, a new page), keeping the zoom unless the bubble cannot fit.
    LaunchedEffect(selectedId, canvas.transform) {
        bubbles.firstOrNull { it.id == selectedId }?.let { canvas.follow(Box.of(it.polygon)) }
    }
    LaunchedEffect(canvas.zoom) { vm.memory.remember(vm.chapterId, canvas.zoom) }
    // Styling, filling and reshaping are previewed on the lettered page; the brush works on the clean layer.
    LaunchedEffect(tool) {
        when (tool) {
            Tool.TEXT, Tool.FILL, Tool.RESHAPE -> vm.setLayer(Layer.FINAL)
            Tool.BRUSH -> vm.setLayer(Layer.CLEAN)
            else -> Unit
        }
    }
    // A tool for the selected bubble closes when nothing is selected any more.
    LaunchedEffect(selected == null) { if (selected == null && tool in BUBBLE_TOOLS) tool = Tool.NONE }

    val width = widthClass()
    // Tablets and laptops lay the editor out side by side; focus mode is the page alone on any screen.
    val wideLayout = width.wide && !focus
    val keys = remember { FocusRequester() }
    LaunchedEffect(wideLayout) { if (wideLayout) runCatching { keys.requestFocus() } }

    /** The page with everything drawn over it. [layerOnCanvas]: the Original / Clean / Final switch sits on the page (phones). */
    val canvasArea: @Composable (Modifier, Boolean) -> Unit = { modifier, layerOnCanvas ->
        Box(modifier) {
            PageCanvas(
                state = canvas, tiles = vm.tilesFor(layer, original, clean, final), pageKey = page?.id, bubbles = bubbles, selectedId = selectedId,
                mode = when (tool) {
                    Tool.ADD -> CanvasMode.DRAW_BOX
                    Tool.RESHAPE -> CanvasMode.RESHAPE
                    Tool.BRUSH -> CanvasMode.BRUSH
                    Tool.TEXT -> if (sfxFrame != null) CanvasMode.SFX else CanvasMode.SELECT
                    Tool.NONE, Tool.FILL -> CanvasMode.SELECT
                },
                onSelect = { vm.select(it) },
                onEditInPlace = { inPlace = it },
                onBoxDrawn = { tool = Tool.NONE; vm.addBubble(it, sfx = addSfx) },
                showOutlines = layer != Layer.FINAL || tool == Tool.ADD || tool == Tool.RESHAPE || tool == Tool.BRUSH,
                strokes = strokes, brushRadius = brushRadius, brushMode = brushMode,
                onPolygonChanged = { id, poly -> vm.setPolygon(id, poly) },
                onPolygonPreview = { id, poly -> vm.previewPolygon(id, poly) },
                onStroke = { vm.addStroke(it) },
                sfxFrame = sfxFrame,
                sfxFrames = sfxFrames,
                onSfxMove = { dx, dy -> sfxSelected?.let { b -> val e = vm.sfxOf(b); vm.setSfx(b.id, e.copy(transform = e.transform.copy(cx = e.transform.cx + dx, cy = e.transform.cy + dy))) } },
                onSfxRotate = { deg -> sfxSelected?.let { b -> val e = vm.sfxOf(b); vm.setSfx(b.id, e.copy(transform = e.transform.copy(rotationDeg = ((deg + 540f) % 360f) - 180f))) } },
                onSfxScale = { f -> sfxSelected?.let { b -> val e = vm.sfxOf(b); val t = e.transform; vm.setSfx(b.id, e.copy(transform = t.copy(scaleX = (t.scaleX * f).coerceIn(0.2f, 4f), scaleY = (t.scaleY * f).coerceIn(0.2f, 4f)))) } },
                modifier = Modifier.fillMaxSize(),
            ) { toScreen ->
                val b = bubbles.firstOrNull { it.id == inPlace }
                if (b != null) InPlaceEditor(b, texts(b).translation, toScreen(Box.of(b.polygon)), onChange = { vm.editTranslation(b.id, it) }, onDone = { inPlace = null })
            }
            if (tool == Tool.ADD) AddHint(Modifier.align(Alignment.TopCenter))
            else if (layerOnCanvas) Box(Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Tokens.Card)) {
                Segmented(
                    Layer.entries, layer,
                    label = {
                        stringResource(
                            when (it) {
                                Layer.ORIGINAL -> UiR.string.studio_layer_original
                                Layer.CLEAN -> UiR.string.studio_layer_clean
                                Layer.FINAL -> UiR.string.studio_layer_final
                            },
                        )
                    },
                    onSelect = vm::setLayer,
                )
            }
            ZoomControls(canvas, Modifier.align(Alignment.BottomEnd).padding(10.dp))
            if (busy != null || cleaning) Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                if (cleaning && busy == null) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(UiR.string.studio_cleaning), color = Color.White, fontSize = 12.sp)
                }
            }
            failure?.let { f ->
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE6202024)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(failureText(f), color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = vm::clearFailure) { Icon(Icons.Filled.Close, stringResource(UiR.string.cd_dismiss), tint = Color.White) }
                }
            }
            if (focus && floating && selected != null) {
                FloatingEditor(
                    texts(selected),
                    onSource = { vm.editSource(selected.id, it) }, onTranslation = { vm.editTranslation(selected.id, it) },
                    onClose = { floating = false },
                    // Anchored away from the bubble: at the bottom when the bubble is in the top half, else at the top.
                    modifier = Modifier.align(if (bubbleInTopHalf(canvas, selected)) Alignment.BottomCenter else Alignment.TopCenter),
                )
            }
        }
    }
    val tools: @Composable (Boolean) -> Unit = { vertical ->
        ToolRow(
            vertical = vertical,
            tool = tool, hasSelection = selected != null,
            onEditor = { tool = Tool.NONE },
            onText = { tool = if (tool == Tool.TEXT) Tool.NONE else Tool.TEXT },
            onFill = { tool = if (tool == Tool.FILL) Tool.NONE else Tool.FILL },
            onReshape = { tool = if (tool == Tool.RESHAPE) Tool.NONE else Tool.RESHAPE },
            onBrush = { tool = if (tool == Tool.BRUSH) Tool.NONE else Tool.BRUSH },
            onAdd = { tool = if (tool == Tool.ADD) Tool.NONE else Tool.ADD; inPlace = null },
        )
    }
    /** The bubble editor or the open tool's options; [withPageBar]: phones end it with the page bar. */
    val editorPane: @Composable ColumnScope.(Boolean) -> Unit = { withPageBar ->
        if (tool == Tool.ADD) {
            Box(Modifier.weight(1f)) {
                ToolPanel(stringResource(UiR.string.studio_add_bubble), onDone = { tool = Tool.NONE }) { AddTextPanel(addSfx) { addSfx = it } }
            }
        } else if (tool != Tool.NONE) {
            // The open tool's options take the editor's place, so the page stays in view while they change it.
            Box(Modifier.weight(1f)) {
                ToolOptions(tool, selected, vm, tgt, brushMode, brushRadius, { brushMode = it }, { brushRadius = it }, onOpenFonts, onDone = { tool = Tool.NONE })
            }
        } else {
            EditorHeader(
                tab, { tab = it }, index = shown.indexOfFirst { it.id == selectedId }, count = shown.size,
                onPrev = { vm.step(-1) }, onNext = { vm.step(1) }, kind = selected?.let { kindName(it) },
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
                    EditorTab.BUBBLE -> BubbleTab(
                        bubble = selected, count = shown.size, texts = selected?.let(::texts),
                        src = manga?.srcLang ?: Lang.JA, tgt = tgt,
                        retranslating = (busy as? ReviewBusy.Retranslating)?.bubbleId == selectedId,
                        onSource = { t -> selected?.let { vm.editSource(it.id, t) } },
                        onTranslation = { t -> selected?.let { vm.editTranslation(it.id, t) } },
                        onRetranslate = { selected?.let { vm.retranslate(it.id) } },
                        onToggleIgnored = { selected?.let { vm.setIgnored(it.id, !it.ignored) } },
                        onDelete = { selected?.let { vm.deleteBubble(it.id) } },
                    )
                    EditorTab.PROOFREAD -> ProofreadTab(bubbles, ::texts, selectedId) { vm.select(it) }
                }
            }
            if (withPageBar) PageBar(
                index = pageIndex, count = pages.size, reviewed = page?.reviewed == true,
                onPrev = { vm.goToPage(pageIndex - 1) }, onNext = { vm.goToPage(pageIndex + 1) },
                onMarkReviewed = { vm.markReviewedAndNext() },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().background(Tokens.Bg).imePadding().navigationBarsPadding()
            // Keyboards (Chromebooks, tablets with a cover): Alt+arrows step bubbles and pages, Ctrl+Z / Ctrl+Shift+Z
            // undo and redo, Ctrl+Enter marks the page reviewed. None of them types, so a focused field keeps its keys.
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) editorShortcut(e, vm, pageIndex) else false }
            .focusRequester(keys).focusable(),
    ) {
        TopBar(
            chapterNumber = chapter?.numberLabel, index = pageIndex, count = pages.size, page = page, bubbles = shown.size,
            onBack = onBack,
            onFit = { canvas.fit() }, onFocus = { focus = !focus; floating = false; tool = Tool.NONE }, focus = focus,
            canUndo = canUndo, canRedo = canRedo, onUndo = vm::undo, onRedo = vm::redo,
            wide = if (wideLayout) WideTop(layer, vm::setLayer, reviewed = page?.reviewed == true, last = pageIndex >= pages.size - 1, onMarkReviewed = { vm.markReviewedAndNext() }) else null,
        )
        if (wideLayout) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (width == WidthClass.EXPANDED) PageColumn(pages, pageIndex, vm::pageFile, onPage = vm::goToPage)
                tools(true)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Border))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    canvasArea(Modifier.weight(1f).fillMaxWidth(), false)
                    if (width == WidthClass.MEDIUM) PageStrip(pages, pageIndex, vm::pageFile, onPage = vm::goToPage)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Border))
                Column(Modifier.width(if (width == WidthClass.EXPANDED) 420.dp else 360.dp).fillMaxHeight().background(Tokens.Card)) {
                    editorPane(false)
                }
            }
            if (width == WidthClass.EXPANDED) StatusBar(pageIndex, pages)
        } else {
            canvasArea(Modifier.weight(if (focus) 1f else 0.5f).fillMaxWidth(), true)
            if (focus) {
                FocusBar(selected?.let { texts(it).translation }, onOpen = { floating = true }, onExit = { focus = false; floating = false })
            } else {
                Column(Modifier.weight(0.5f).fillMaxWidth().background(Tokens.Card)) {
                    Grip(onSwipeDown = { focus = true })
                    tools(false)
                    HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
                    editorPane(true)
                }
            }
        }
    }
}

private val BUBBLE_TOOLS = setOf(Tool.TEXT, Tool.FILL, Tool.RESHAPE)

private fun bounds(b: Bubble): FRect = PolygonFit.bounds(b.polygon)

/** The open tool's options, in the editor pane. Style changes show on the page at once (saved when they pause). */
@Composable
private fun ToolOptions(
    tool: Tool, selected: Bubble?, vm: ReviewViewModel, tgt: Lang,
    brushMode: BrushMode, brushRadius: Float, onBrushMode: (BrushMode) -> Unit, onBrushRadius: (Float) -> Unit, onOpenFonts: () -> Unit, onDone: () -> Unit,
) {
    val draftStyle by vm.draftStyle.collectAsStateWithLifecycle()
    val favourites by vm.fontFavourites.collectAsStateWithLifecycle()
    val userFonts by vm.userFonts.collectAsStateWithLifecycle()
    val choices = FontChoices(favourites, userFonts, vm::toggleFavourite, onOpenFonts)
    val style = selected?.let { draftStyle[it.id] ?: it.style }
    val pageBubbles by vm.bubbles.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    // The colours already on the page, offered first in the colour picker.
    val pageColours = remember(pageBubbles) {
        pageBubbles.flatMap { b -> listOfNotNull(b.style.textColor, b.style.outlineColor, b.style.fillColor.takeIf { it ushr 24 != 0 }) }.distinct().take(8)
    }
    when (tool) {
        Tool.TEXT -> if (selected != null && selected.kind == com.smnexstudio.panelglass.core.model.RegionKind.SFX) {
            val sfxDrafts by vm.draftSfx.collectAsStateWithLifecycle()
            val remembered by vm.remembered.collectAsStateWithLifecycle()
            val edit = sfxDrafts[selected.id] ?: SfxRenderer.editOf(selected)
            ToolPanel(stringResource(UiR.string.studio_sfx_title), onDone) {
                SfxPanel(selected, edit, tgt, choices, remembered == selected.id, onEdit = { vm.setSfx(selected.id, it) }, onRemember = { vm.rememberEffect(selected) }, lamaHint = !vm.lamaAvailable)
            }
        } else if (selected != null && style != null) ToolPanel(stringResource(UiR.string.studio_tool_text), onDone) {
            TextStylePanel(style, tgt, choices, pageColours = pageColours, onStyle = { vm.setStyle(selected.id, it) }, onApplyAll = { s ->
                vm.setStyleForAll {
                    it.copy(
                        fontId = s.fontId, bold = s.bold, italic = s.italic, uppercase = s.uppercase, sizePx = s.sizePx,
                        textColor = s.textColor, outlineWidthPx = s.outlineWidthPx, outlineColor = s.outlineColor,
                    )
                }
            })
        }
        Tool.FILL -> if (selected != null && style != null) ToolPanel(stringResource(UiR.string.studio_tool_fill), onDone) {
            FillPanel(style, pageColours = pageColours, onStyle = { vm.setStyle(selected.id, it) }, onApplyAll = { s -> vm.setStyleForAll { it.copy(fillColor = s.fillColor) } })
        }
        Tool.RESHAPE -> ToolPanel(stringResource(UiR.string.studio_tool_reshape), onDone) {
            ReshapePanel(
                onRectangle = { selected?.let { b -> vm.setPolygon(b.id, PolygonFit.rectangle(bounds(b))) } },
                onEllipse = { selected?.let { b -> vm.setPolygon(b.id, PolygonFit.ellipse(bounds(b))) } },
                style = style, onStyle = { s -> selected?.let { vm.setStyle(it.id, s) } }, pageColours = pageColours,
            )
        }
        Tool.BRUSH -> ToolPanel(stringResource(UiR.string.studio_tool_brush), onDone) {
            BrushPanel(brushMode, brushRadius, onBrushMode, onBrushRadius, lamaHint = !vm.lamaAvailable, strokes = strokes.size, onUndo = vm::undo)
        }
        Tool.NONE, Tool.ADD -> Unit
    }
}

private fun bubbleInTopHalf(canvas: CanvasState, b: Bubble): Boolean {
    val t = canvas.transform ?: return true
    val v = canvas.view ?: return true
    return t.toScreen(v, Box.of(b.polygon)).centerY < t.viewH / 2f
}

@Composable
private fun stageName(page: StudioPage?): String = stringResource(
    when {
        page == null -> UiR.string.studio_stage_new
        page.reviewed -> UiR.string.studio_stage_reviewed
        StudioStage.CLEANED in page.stages -> UiR.string.studio_stage_cleaned
        StudioStage.TRANSLATED in page.stages -> UiR.string.studio_stage_translated
        else -> UiR.string.studio_stage_new
    },
)

@Composable
private fun TopBar(
    chapterNumber: String?, index: Int, count: Int, page: StudioPage?, bubbles: Int, focus: Boolean,
    onBack: () -> Unit, onFit: () -> Unit, onFocus: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onUndo: () -> Unit, onRedo: () -> Unit,
    wide: WideTop? = null,
) {
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Tokens.Card).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.cd_back), tint = Tokens.Ink) }
        Column(Modifier.weight(1f)) {
            val pageOf = if (count > 0) stringResource(UiR.string.studio_page_of, index + 1, count) else ""
            Text(
                chapterNumber?.let { stringResource(UiR.string.studio_chapter_short, it) + " · " + pageOf } ?: pageOf,
                fontFamily = Jakarta, fontWeight = FontWeight.W800, fontSize = 16.sp, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                stageName(page) + " · " + pluralStringResource(UiR.plurals.studio_bubbles, bubbles, bubbles),
                fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft, maxLines = 1,
            )
        }
        wide?.let { w ->
            Segmented(
                Layer.entries, w.layer,
                label = {
                    stringResource(
                        when (it) {
                            Layer.ORIGINAL -> UiR.string.studio_layer_original
                            Layer.CLEAN -> UiR.string.studio_layer_clean
                            Layer.FINAL -> UiR.string.studio_layer_final
                        },
                    )
                },
                onSelect = w.onLayer, modifier = Modifier.padding(end = 8.dp),
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, stringResource(UiR.string.studio_undo), tint = if (canUndo) Tokens.Ink else Tokens.InkFaint)
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, stringResource(UiR.string.studio_redo), tint = if (canRedo) Tokens.Ink else Tokens.InkFaint)
        }
        wide?.let { w ->
            if (w.reviewed && w.last) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Check, null, tint = Tokens.Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(UiR.string.studio_stage_reviewed), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink)
            } else PrimaryPill("✓ " + stringResource(UiR.string.studio_mark_reviewed), onClick = w.onMarkReviewed, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, stringResource(UiR.string.cd_more), tint = Tokens.Ink) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(UiR.string.studio_fit_page)) }, onClick = { more = false; onFit() })
                DropdownMenuItem(
                    text = { Text(stringResource(if (focus) UiR.string.studio_exit_focus else UiR.string.studio_focus_mode)) },
                    onClick = { more = false; onFocus() },
                )
            }
        }
    }
}

/**
 * The tools under the page: bubbles (the bubble editor), text style, fill, reshape, brush, add text. Undo and redo
 * are in the top bar; "Not text" is with the bubble's own actions.
 */
@Composable
private fun ToolRow(
    tool: Tool, hasSelection: Boolean,
    onEditor: () -> Unit, onText: () -> Unit, onFill: () -> Unit, onReshape: () -> Unit, onBrush: () -> Unit, onAdd: () -> Unit,
    vertical: Boolean = false,
) {
    val buttons: @Composable () -> Unit = {
        ToolButton(Icons.Outlined.Translate, stringResource(UiR.string.studio_field_translation), active = tool == Tool.NONE, onClick = onEditor)
        ToolButton(Icons.Filled.TextFields, stringResource(UiR.string.studio_tool_text), active = tool == Tool.TEXT, enabled = hasSelection, onClick = onText)
        ToolButton(Icons.Outlined.FormatColorFill, stringResource(UiR.string.studio_tool_fill), active = tool == Tool.FILL, enabled = hasSelection, onClick = onFill)
        ToolButton(Icons.Outlined.Pentagon, stringResource(UiR.string.studio_tool_reshape), active = tool == Tool.RESHAPE, enabled = hasSelection, onClick = onReshape)
        ToolButton(Icons.Outlined.Brush, stringResource(UiR.string.studio_tool_brush), active = tool == Tool.BRUSH, onClick = onBrush)
        ToolButton(Icons.Outlined.AddBox, stringResource(UiR.string.studio_add_bubble), active = tool == Tool.ADD, onClick = onAdd)
    }
    if (vertical) Column(
        Modifier.width(68.dp).fillMaxHeight().background(Tokens.Card).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { buttons() }
    else Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) { buttons() }
}

/**
 * An icon tool with its name in a tooltip: shown on hover (mouse, stylus) and long-press, and for a moment after a tap,
 * so an icon is never a guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tip = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = tip,
    ) {
        IconButton(
            onClick = { onClick(); scope.launch { tip.show() } },
            enabled = enabled,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Tokens.Ink else Color.Transparent),
        ) {
            Icon(icon, contentDescription = label, tint = if (active) Tokens.Card else if (enabled) Tokens.InkSoft else Tokens.InkFaint, modifier = Modifier.size(22.dp))
        }
    }
}

/** While adding: how to draw the box, over the page (what it becomes is chosen in the panel). */
@Composable
private fun AddHint(modifier: Modifier) {
    Text(
        stringResource(UiR.string.studio_draw_box_hint), color = Color.White, fontFamily = Jakarta, fontWeight = FontWeight.W600, fontSize = 13.sp,
        modifier = modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0xE6000000)).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** The editor pane's handle: swipe it down for focus mode. */
@Composable
private fun Grip(onSwipeDown: () -> Unit) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(12.dp).pointerInputVertical(
            onDrag = { dragged += it },
            onEnd = { if (dragged > threshold) onSwipeDown(); dragged = 0f },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.Border))
    }
}

private fun Modifier.pointerInputVertical(onDrag: (Float) -> Unit, onEnd: () -> Unit) = this.then(
    Modifier.pointerInput(Unit) {
        detectVerticalDragGestures(onDragEnd = onEnd, onDragCancel = onEnd) { change, dy -> change.consume(); onDrag(dy) }
    },
)

/** Focus mode's one line: the selected bubble's translation. Tap it to edit; swipe up (or the button) for the editor. */
@Composable
private fun FocusBar(translation: String?, onOpen: () -> Unit, onExit: () -> Unit) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier.fillMaxWidth().background(Tokens.Card)
            .pointerInputVertical(onDrag = { dragged += it }, onEnd = { if (dragged < -threshold) onExit(); dragged = 0f })
            .clickable(enabled = translation != null, onClick = onOpen).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            translation?.ifBlank { stringResource(UiR.string.studio_not_translated) } ?: stringResource(UiR.string.studio_select_bubble_hint),
            style = MaterialTheme.typography.bodyMedium, color = Tokens.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExit) { Icon(Icons.Filled.FullscreenExit, stringResource(UiR.string.studio_exit_focus), tint = Tokens.Ink) }
    }
}

/** Focus mode's editor card, floating over the canvas away from the bubble. */
@Composable
private fun FloatingEditor(texts: BubbleTexts, onSource: (String) -> Unit, onTranslation: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tokens.Card)
            .border(1.dp, Tokens.Border, RoundedCornerShape(16.dp)).padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, stringResource(UiR.string.cd_dismiss), tint = Tokens.InkSoft) }
        }
        reviewField(texts.source, onSource, stringResource(UiR.string.studio_field_source))
        Spacer(Modifier.height(6.dp))
        reviewField(texts.translation, onTranslation, stringResource(UiR.string.studio_field_translation), done = onClose)
    }
}

/** A long-pressed bubble's translation, edited right on the bubble; Done commits (edits are saved as typed anyway). */
@Composable
private fun InPlaceEditor(b: Bubble, text: String, screen: Box?, onChange: (String) -> Unit, onDone: () -> Unit) {
    val r = screen ?: return
    val density = LocalDensity.current
    val minW = with(density) { 160.dp.toPx() }
    val w = maxOf(r.width, minW)
    val focus = remember { FocusRequester() }
    LaunchedEffect(b.id) { focus.requestFocus() }
    Box(
        Modifier.offset { IntOffset((r.centerX - w / 2f).roundToInt().coerceAtLeast(0), r.top.roundToInt().coerceAtLeast(0)) }
            .size(with(density) { w.toDp() }, with(density) { maxOf(r.height, 56.dp.toPx()).toDp() })
            .clip(RoundedCornerShape(10.dp)).background(Color.White).border(2.dp, Color(0xFFFFC21A), RoundedCornerShape(10.dp))
            .padding(6.dp),
    ) {
        BasicTextField(
            value = text, onValueChange = onChange,
            textStyle = TextStyle(color = Color.Black, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxSize().focusRequester(focus),
        )
    }
}

/** Page ‹ › and the page's own action: mark it reviewed and go to the next page. */
@Composable
private fun PageBar(index: Int, count: Int, reviewed: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onMarkReviewed: () -> Unit) {
    val last = index >= count - 1
    Column {
        HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = index > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(UiR.string.studio_previous_page), tint = if (index > 0) Tokens.Ink else Tokens.InkFaint)
            }
            Text(
                if (count > 0) stringResource(UiR.string.studio_page_of, index + 1, count) else "",
                fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            if (reviewed && last) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                Icon(Icons.Filled.Check, null, tint = Tokens.Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(UiR.string.studio_stage_reviewed), fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 13.sp, color = Tokens.Ink)
            } else PrimaryPill("✓ " + stringResource(UiR.string.studio_mark_reviewed), onClick = onMarkReviewed)
            IconButton(onClick = onNext, enabled = !last) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(UiR.string.studio_next_page), tint = if (!last) Tokens.Ink else Tokens.InkFaint)
            }
        }
    }
}

/** What the top bar adds on a wide screen: the layer switch and the page's Mark reviewed. */
private class WideTop(
    val layer: Layer, val onLayer: (Layer) -> Unit, val reviewed: Boolean, val last: Boolean, val onMarkReviewed: () -> Unit,
)

/** The editor's keyboard shortcuts; true when the key was one of them. */
private fun editorShortcut(e: KeyEvent, vm: ReviewViewModel, pageIndex: Int): Boolean = when {
    e.isAltPressed && e.key == Key.DirectionUp -> { vm.step(-1); true }
    e.isAltPressed && e.key == Key.DirectionDown -> { vm.step(1); true }
    e.isAltPressed && e.key == Key.DirectionLeft -> { vm.goToPage(pageIndex - 1); true }
    e.isAltPressed && e.key == Key.DirectionRight -> { vm.goToPage(pageIndex + 1); true }
    e.key == Key.PageUp -> { vm.goToPage(pageIndex - 1); true }
    e.key == Key.PageDown -> { vm.goToPage(pageIndex + 1); true }
    e.isCtrlPressed && e.key == Key.Z && e.isShiftPressed -> { vm.redo(); true }
    e.isCtrlPressed && e.key == Key.Z -> { vm.undo(); true }
    e.isCtrlPressed && e.key == Key.Y -> { vm.redo(); true }
    e.isCtrlPressed && e.key == Key.Enter -> { vm.markReviewedAndNext(); true }
    else -> false
}

/** A page thumbnail with its number, outlined when it is the page in the editor and ticked when it is reviewed. */
@Composable
private fun PageCell(p: StudioPage, i: Int, current: Boolean, file: (StudioPage) -> java.io.File, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(width = size, height = size * 1.5f).clip(RoundedCornerShape(8.dp))
                .border(if (current) 3.dp else 1.dp, if (current) Tokens.Yellow else Tokens.Border, RoundedCornerShape(8.dp)),
        ) {
            com.smnexstudio.panelglass.feature.studio.PageThumb(file(p), Modifier.matchParentSize(), targetDp = 96)
            if (p.reviewed) Box(
                Modifier.align(Alignment.TopEnd).padding(3.dp).size(16.dp).clip(CircleShape).background(Tokens.Yellow),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = Tokens.Ink, modifier = Modifier.size(11.dp)) }
        }
        Text("${i + 1}", fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 11.sp, color = Tokens.Ink, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Tablets: the chapter's pages in a strip under the page. */
@Composable
private fun PageStrip(pages: List<StudioPage>, index: Int, file: (StudioPage) -> java.io.File, onPage: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem(maxOf(0, index - 2)) }
    Column(Modifier.fillMaxWidth().background(Tokens.Card)) {
        HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
        LazyRow(state = list, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p -> PageCell(p, i, i == index, file, 40.dp) { onPage(i) } }
        }
    }
}

/** Laptops: the chapter's pages in a column on the left. */
@Composable
private fun PageColumn(pages: List<StudioPage>, index: Int, file: (StudioPage) -> java.io.File, onPage: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(index) { if (index >= 0) list.animateScrollToItem(maxOf(0, index - 1)) }
    Row(Modifier.fillMaxHeight()) {
        LazyColumn(
            state = list, modifier = Modifier.width(104.dp).fillMaxHeight().background(Tokens.Card),
            contentPadding = PaddingValues(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.id }) { i, p -> PageCell(p, i, i == index, file, 64.dp) { onPage(i) } }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tokens.Border))
    }
}

/** Laptops: the page count, the review progress and the keyboard shortcuts. */
@Composable
private fun StatusBar(index: Int, pages: List<StudioPage>) {
    Column {
        HorizontalDivider(thickness = 1.dp, color = Tokens.Border)
        Row(Modifier.fillMaxWidth().background(Tokens.Card).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = pages.size
            Text(
                (if (count > 0) stringResource(UiR.string.studio_page_of, index + 1, count) + " · " else "") +
                    stringResource(UiR.string.studio_reviewed, pages.count { it.reviewed }, count),
                fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft,
            )
            Spacer(Modifier.weight(1f))
            Icon(Icons.Outlined.Keyboard, null, tint = Tokens.InkSoft, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(UiR.string.studio_shortcuts_hint), fontFamily = Jakarta, fontSize = 12.sp, color = Tokens.InkSoft, maxLines = 1)
        }
    }
}

/**
 * The zoom control on the page: a zoom icon that slides out −, the zoom, + and Fit, and folds back in when it is tapped
 * again, so the page is covered only while zooming. Two fingers still pinch to zoom anywhere.
 */
@Composable
private fun ZoomControls(canvas: CanvasState, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.clip(shape).background(Tokens.Card.copy(alpha = 0.95f)).border(1.dp, Tokens.Border, shape).animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { canvas.zoomBy(1f / 1.5f) }, enabled = canvas.zoom > CanvasTransform.MIN_ZOOM + 0.01f, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Remove, stringResource(UiR.string.studio_zoom_out), tint = Tokens.Ink, modifier = Modifier.size(20.dp))
                }
                Text(
                    "${(canvas.zoom * 100).roundToInt()}%", fontFamily = Jakarta, fontWeight = FontWeight.W700, fontSize = 12.sp, color = Tokens.Ink,
                    textAlign = TextAlign.Center, modifier = Modifier.width(44.dp).clickable { canvas.fit() },
                )
                IconButton(onClick = { canvas.zoomBy(1.5f) }, enabled = canvas.zoom < CanvasTransform.MAX_ZOOM - 0.01f, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Add, stringResource(UiR.string.studio_zoom_in), tint = Tokens.Ink, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { canvas.fit() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.FitScreen, stringResource(UiR.string.studio_fit_page), tint = Tokens.Ink, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.width(1.dp).height(22.dp).background(Tokens.Border))
            }
        }
        // The toggle: dark while the controls are out.
        IconButton(
            onClick = { open = !open },
            modifier = Modifier.size(44.dp).padding(2.dp).clip(CircleShape).background(if (open) Tokens.Ink else Color.Transparent),
        ) {
            Icon(
                Icons.Filled.ZoomIn, stringResource(if (open) UiR.string.studio_zoom_hide else UiR.string.studio_zoom_show),
                tint = if (open) Tokens.Card else Tokens.Ink, modifier = Modifier.size(22.dp),
            )
        }
    }
}
