package com.smnexstudio.panelglass.feature.studio.review

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.model.BrushStroke
import com.smnexstudio.panelglass.core.model.Bubble
import com.smnexstudio.panelglass.core.model.BubbleStyle
import com.smnexstudio.panelglass.core.model.Chapter
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.UserFont
import com.smnexstudio.panelglass.core.model.SfxEdit
import com.smnexstudio.panelglass.core.model.SfxEditMode
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.render.SfxRenderer
import com.smnexstudio.panelglass.core.ocr.LamaInpainter
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.feature.studio.fonts.UserFontStore
import com.smnexstudio.panelglass.core.model.Manga
import com.smnexstudio.panelglass.core.model.Pt
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.StageRules
import com.smnexstudio.panelglass.core.model.StudioPage
import com.smnexstudio.panelglass.core.model.StudioStage
import com.smnexstudio.panelglass.core.render.StudioRenderer
import com.smnexstudio.panelglass.feature.studio.translate.StrokeStore
import com.smnexstudio.panelglass.feature.studio.translate.StudioTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The zoom each chapter was last reviewed at (relative to the fit), for this app session. */
@Singleton
class ReviewMemory @Inject constructor() {
    private val zooms = HashMap<Long, Float>()
    fun zoom(chapterId: Long): Float = zooms[chapterId] ?: 1f
    fun remember(chapterId: Long, zoom: Float) { zooms[chapterId] = zoom }
}

/** What the editor is waiting for, if anything: a bubble being translated again, one being added, a page clean. */
sealed interface ReviewBusy {
    data class Retranslating(val bubbleId: Long) : ReviewBusy
    data object Adding : ReviewBusy
}

/** Which version of the page the canvas shows (docs/STUDIO_PLAN.md › layer toggles, as in Koharu). */
enum class Layer { ORIGINAL, CLEAN, FINAL }

/**
 * The review editor of one chapter: its pages, the current page's layers and bubbles, the selection, and every edit.
 * Text edits are saved as the user types (debounced), so leaving the screen never loses work. Every edit is one step
 * of an undo / redo stack kept per page. An edit to what the clean layer is built from (a bubble's shape, a dismissed
 * or added bubble, a brush stroke) clears the page's [StudioStage.CLEANED] and rebuilds the layer in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ReviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: StudioRepository,
    private val translator: StudioTranslator,
    private val renderer: StudioRenderer,
    val memory: ReviewMemory,
    private val settings: SettingsRepository,
    private val glossary: GlossaryRepository,
    private val lama: LamaInpainter,
    userFontStore: UserFontStore,
    handle: SavedStateHandle,
) : ViewModel() {
    /** Favourite fonts (ordered) and the user's fonts, for the font browser. */
    val fontFavourites: StateFlow<List<String>> = settings.fontFavourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val userFonts: StateFlow<List<UserFont>> = userFontStore.fonts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun toggleFavourite(id: String) = viewModelScope.launch { settings.toggleFontFavourite(id) }
    /** Whether cleanup over artwork uses LaMa (else the editor suggests installing it where it matters). */
    val lamaAvailable: Boolean get() = lama.available

    val chapterId: Long = handle.get<String>("chapterId")?.toLongOrNull() ?: 0L

    val chapter: StateFlow<Chapter?> = repo.chapter(chapterId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val manga: StateFlow<Manga?> = chapter.filterNotNull().flatMapLatest { repo.manga(it.mangaId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pages: StateFlow<List<StudioPage>> = repo.pages(chapterId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pageIndex = MutableStateFlow(handle.get<String>("page")?.toIntOrNull() ?: 0)

    val page: StateFlow<StudioPage?> = combine(pages, pageIndex) { list, i -> list.getOrNull(i.coerceIn(0, (list.size - 1).coerceAtLeast(0))) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pageId = page.map { it?.id }.distinctUntilChanged()

    val bubbles: StateFlow<List<Bubble>> = pageId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.bubbles(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selected = MutableStateFlow<Long?>(null)
    val busy = MutableStateFlow<ReviewBusy?>(null)
    /** The last failure of a single-bubble action, until the user sees it. */
    val failure = MutableStateFlow<EngineFailure?>(null)

    /** Text typed but not saved yet (bubble id → text), so the fields never jump back while the database catches up. */
    val draftSource = MutableStateFlow<Map<Long, String>>(emptyMap())
    val draftTranslation = MutableStateFlow<Map<Long, String>>(emptyMap())
    private val saves = HashMap<Long, Job>()
    /** Saves and recycling that must finish after the screen is left, when [viewModelScope] is already cancelled. */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * A style being changed (slider dragged, colour picked) and a shape whose corner is being dragged, not saved yet:
     * the Final layer previews them at once, and a style is saved as one undo step when the changes pause.
     */
    val draftStyle = MutableStateFlow<Map<Long, BubbleStyle>>(emptyMap())
    val draftPolygon = MutableStateFlow<Map<Long, List<Pt>>>(emptyMap())
    private val styleSaves = HashMap<Long, Job>()
    /** A sound effect's mode, look or transform being changed, previewed and saved like a style. */
    val draftSfx = MutableStateFlow<Map<Long, SfxEdit>>(emptyMap())
    private val sfxSaves = HashMap<Long, Job>()

    // ---- layers --------------------------------------------------------------------------------

    val layer = MutableStateFlow(Layer.FINAL)
    /** The original page's pixels (downsampled base + zoom tiles), opened off the main thread. */
    val tiles = MutableStateFlow<PageTiles?>(null)
    /** The clean layer's, when the page has one. */
    val cleanTiles = MutableStateFlow<PageTiles?>(null)
    /** The lettered page, rendered from the clean layer's base as the bubbles change. */
    val finalTiles = MutableStateFlow<PageTiles?>(null)
    /** A clean layer is being rebuilt. */
    val cleaning = MutableStateFlow(false)
    private val tileBudget = PageTiles.budgetFor(context)
    private var cleanJob: Job? = null

    /** The language the page is lettered in. */
    val target: StateFlow<Lang> = combine(chapter, manga) { c, m -> c?.language ?: m?.tgtLang ?: Lang.EN }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Lang.EN)

    // ---- strokes and undo ----------------------------------------------------------------------

    val strokes = MutableStateFlow<List<BrushStroke>>(emptyList())

    private class Edit(val undo: suspend () -> Unit, val redo: suspend () -> Unit)
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    val canUndo = MutableStateFlow(false)
    val canRedo = MutableStateFlow(false)

    init {
        // A new page: its tiles, strokes and an empty undo history.
        viewModelScope.launch {
            // collectLatest: a page left before it finished loading is dropped, never applied late. The clean layer is
            // not touched here: the collector below owns it.
            page.map { it?.id to it?.file }.distinctUntilChanged().collectLatest { (id, file) ->
                val old = listOfNotNull(tiles.value, finalTiles.value)
                tiles.value = null; finalTiles.value = null
                selected.value = null
                undoStack.clear(); redoStack.clear(); flags()
                recycleLater(old)
                if (id != null && file != null) {
                    val opened = withContext(Dispatchers.IO) {
                        PageTiles.open(repo.files.file(file), tileBudget) to StrokeStore.read(repo.files.file(StudioFiles.layer(file, StudioFiles.STROKES)))
                    }
                    tiles.value = opened.first
                    strokes.value = opened.second
                }
            }
        }
        // The clean layer is (re)opened whenever the page gains Cleaned: a run or an edit just rebuilt it. While the
        // same page is being cleaned again its old layer stays on screen; another page never shows it.
        viewModelScope.launch {
            var shownFor: Long? = null
            page.map { p -> p?.let { Triple(it.id, it.file, StudioStage.CLEANED in it.stages) } }.distinctUntilChanged().collectLatest { key ->
                if (key != null && !key.third && key.first == shownFor) return@collectLatest
                val opened = if (key == null || !key.third) null else withContext(Dispatchers.IO) {
                    repo.files.file(StudioFiles.layer(key.second, StudioFiles.CLEAN)).takeIf { it.isFile }?.let { PageTiles.open(it, tileBudget) }
                }
                val old = cleanTiles.value
                cleanTiles.value = opened
                shownFor = key?.first
                if (old != null && old !== opened) recycleLater(listOf(old))
            }
        }
        // A page without a clean layer (an edit cleared it, or it was never cleaned) gets one.
        viewModelScope.launch {
            page.collect { p ->
                if (p == null || StudioStage.DETECTED !in p.stages) return@collect
                // A page cleaned before balloons were traced (their shape still the detected rectangle) is cleaned again
                // once, which traces them: fill, border and text then follow each balloon.
                val untraced = repo.bubbleList(p.id).any { it.kind == com.smnexstudio.panelglass.core.model.RegionKind.ENCLOSED && com.smnexstudio.panelglass.feature.studio.translate.isBox(it.polygon) }
                if (StudioStage.CLEANED !in p.stages || untraced) scheduleClean(p.id)
            }
        }
        // The Final layer follows every change: bubbles, unsaved text, style and shape, the clean layer, the target language.
        viewModelScope.launch {
            val previewed = combine(bubbles, draftTranslation, draftStyle, draftPolygon, draftSfx) { b, text, style, poly, sfx ->
                b.map { x ->
                    if (x.id !in text && x.id !in style && x.id !in poly && x.id !in sfx) x
                    else x.copy(
                        translatedText = text[x.id] ?: x.translatedText,
                        style = style[x.id] ?: x.style,
                        polygon = poly[x.id] ?: x.polygon,
                        sfx = sfx[x.id] ?: x.sfx,
                    )
                }
            }
            combine(previewed, cleanTiles, tiles, target) { b, c, t, tgt -> Render(b, c ?: t, tgt) }
                .debounce(RENDER_DEBOUNCE_MS)
                .collect { r -> renderFinal(r) }
        }
    }

    private class Render(val bubbles: List<Bubble>, val source: PageTiles?, val tgt: Lang)

    private suspend fun renderFinal(r: Render) {
        val src = r.source ?: return
        val shown = r.bubbles
        val out = withContext(Dispatchers.Default) {
            if (src.base.isRecycled) return@withContext null
            val scale = src.base.width.toFloat() / src.pageW
            runCatching { renderer.render(src.base, shown, r.tgt, scale) }.getOrNull()
        } ?: return
        val old = finalTiles.value
        finalTiles.value = PageTiles.ofBitmap(out, src.pageW, src.pageH)
        if (old != null) recycleLater(listOf(old))
    }

    /** A frame may still be drawing these: their bitmaps are recycled a little later. */
    private fun recycleLater(old: List<PageTiles>) {
        if (old.isNotEmpty()) flushScope.launch { delay(RECYCLE_DELAY_MS); old.forEach { it.close() } }
    }

    /** The pixels the canvas draws for [layer]: a missing clean layer shows the original until it is built. */
    fun tilesFor(layer: Layer, original: PageTiles?, clean: PageTiles?, final: PageTiles?): PageTiles? = when (layer) {
        Layer.ORIGINAL -> original
        Layer.CLEAN -> clean ?: original
        Layer.FINAL -> final ?: clean ?: original
    }

    fun setLayer(l: Layer) { layer.value = l }

    private fun scheduleClean(pageId: Long) {
        cleanJob?.cancel()
        cleanJob = viewModelScope.launch {
            delay(CLEAN_DEBOUNCE_MS)
            cleaning.value = true
            try {
                translator.cleanPage(pageId)
            } finally {
                cleaning.value = false
            }
        }
    }

    // ---- selection and pages -------------------------------------------------------------------

    fun select(id: Long?) {
        selected.value = id
        remembered.value = null
    }

    /** The next (or previous) bubble in reading order, dismissed ones skipped; wraps nowhere. */
    fun step(delta: Int) {
        val list = bubbles.value.filter { !it.ignored }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == selected.value }
        val next = if (i < 0) 0 else (i + delta).coerceIn(0, list.size - 1)
        selected.value = list[next].id
    }

    /** A page's original file, for the page strip's thumbnails. */
    fun pageFile(p: StudioPage): java.io.File = repo.files.file(p.file)

    fun goToPage(i: Int) {
        val n = pages.value.size
        if (n == 0) return
        flushAll()
        pageIndex.value = i.coerceIn(0, n - 1)
    }

    // ---- text ----------------------------------------------------------------------------------

    fun editSource(id: Long, text: String) {
        draftSource.update { it + (id to text) }
        scheduleSave(id)
    }

    fun editTranslation(id: Long, text: String) {
        draftTranslation.update { it + (id to text) }
        scheduleSave(id)
    }

    private fun scheduleSave(id: Long) {
        saves[id]?.cancel()
        saves[id] = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save(id)
        }
    }

    /** Writes a bubble's typed text; each pause in typing is one undo step. */
    private suspend fun save(id: Long, record: Boolean = true) {
        val b = bubbles.value.firstOrNull { it.id == id } ?: repo.bubbleList(page.value?.id ?: return).firstOrNull { it.id == id } ?: return
        val src = draftSource.value[id] ?: b.sourceText
        val tr = draftTranslation.value[id] ?: b.translatedText
        if (src == b.sourceText && tr == b.translatedText) return
        val after = b.copy(sourceText = src, translatedText = tr)
        repo.updateBubble(after)
        if (record) record(Edit(undo = { putTexts(b) }, redo = { putTexts(after) }))
    }

    /** Puts [b]'s texts back (undo / redo of a text edit), dropping any draft that would hide them. */
    private suspend fun putTexts(b: Bubble) {
        draftSource.update { it - b.id }
        draftTranslation.update { it - b.id }
        current(b.id)?.let { repo.updateBubble(it.copy(sourceText = b.sourceText, translatedText = b.translatedText)) }
    }

    /**
     * Saves every pending edit now (a page turn, leaving the screen). Runs on its own scope: when the screen is left,
     * [viewModelScope] is already cancelled.
     */
    private fun flushAll() {
        val ids = saves.keys.toList()
        saves.values.forEach { it.cancel() }
        saves.clear()
        // Styles are taken now, with this page's id: by the time the flush runs, the page may have changed. They are
        // written after the text, onto the rows as saved, so neither overwrites the other.
        styleSaves.values.forEach { it.cancel() }
        styleSaves.clear()
        sfxSaves.values.forEach { it.cancel() }
        sfxSaves.clear()
        val styles = draftStyle.value
        val effects = draftSfx.value
        val pageId = page.value?.id
        draftStyle.value = emptyMap()
        draftSfx.value = emptyMap()
        draftPolygon.value = emptyMap()
        if (ids.isEmpty() && ((styles.isEmpty() && effects.isEmpty()) || pageId == null)) return
        flushScope.launch {
            ids.forEach { save(it) }
            if (pageId != null && (styles.isNotEmpty() || effects.isNotEmpty())) {
                var replaceChanged = false
                repo.bubbleList(pageId).forEach { b ->
                    val style = styles[b.id] ?: b.style
                    val sfx = effects[b.id] ?: b.sfx
                    if (style != b.style || sfx != b.sfx) {
                        repo.updateBubble(b.copy(style = style, sfx = sfx))
                        if (replaces(b.sfx) != replaces(sfx)) replaceChanged = true
                    }
                }
                // The page left before its clean layer caught up with a Replace: it is cleaned the next time it opens.
                if (replaceChanged) repo.getPage(pageId)?.let { p -> repo.updatePage(p.copy(stages = StageRules.afterCleanInputChange(p.stages))) }
            }
        }
    }

    private suspend fun current(id: Long): Bubble? =
        bubbles.value.firstOrNull { it.id == id } ?: page.value?.id?.let { pid -> repo.bubbleList(pid).firstOrNull { it.id == id } }

    // ---- bubble edits (each one undoable) ------------------------------------------------------

    /**
     * Writes [after] over the bubble, recording [before] for undo. [cleanInput]: the change affects the clean layer (a
     * shape, a dismissal), so the page loses Cleaned and is cleaned again.
     */
    private suspend fun change(before: Bubble, after: Bubble, cleanInput: Boolean) {
        repo.updateBubble(after)
        if (cleanInput) invalidateClean()
        record(Edit(
            undo = { current(before.id)?.let { repo.updateBubble(before) }; if (cleanInput) invalidateClean() },
            redo = { current(after.id)?.let { repo.updateBubble(after) }; if (cleanInput) invalidateClean() },
        ))
    }

    private suspend fun invalidateClean() {
        val p = page.value?.id?.let { repo.getPage(it) } ?: return
        val next = StageRules.afterCleanInputChange(p.stages)
        if (next != p.stages) repo.updatePage(p.copy(stages = next))
        scheduleClean(p.id)
    }

    /** Dismisses or restores a bubble; it stays selected, so the same button brings it straight back. */
    fun setIgnored(id: Long, ignored: Boolean) = viewModelScope.launch {
        val b = current(id) ?: return@launch
        if (b.ignored != ignored) change(b, b.copy(ignored = ignored), cleanInput = true)
    }

    /**
     * Removes a bubble from the page: it is no longer drawn, cleaned or counted, and the next bubble is selected. Its
     * place is cleaned again from the original. Undo puts it back as it was (typing waiting to be saved included).
     */
    fun deleteBubble(id: Long) = viewModelScope.launch {
        saves.remove(id)?.cancel()
        save(id)
        val b = current(id) ?: return@launch
        val list = bubbles.value
        val at = list.indexOfFirst { it.id == id }
        val next = list.getOrNull(at + 1) ?: list.getOrNull(at - 1)
        repo.deleteBubble(b)
        draftTranslation.update { it - id }
        if (selected.value == id) selected.value = next?.id
        invalidateClean()
        var restored = b
        record(Edit(
            undo = { restored = restored.copy(id = repo.addBubble(restored)); selected.value = restored.id; invalidateClean() },
            redo = { current(restored.id)?.let { repo.deleteBubble(it) }; if (selected.value == restored.id) selected.value = null; invalidateClean() },
        ))
    }

    /** The style the editor shows for [b]: the one being changed, else the saved one. */
    fun styleOf(b: Bubble): BubbleStyle = draftStyle.value[b.id] ?: b.style

    /** Previews [style] on the Final layer at once; saved (one undo step) when the changes pause. */
    fun setStyle(id: Long, style: BubbleStyle) {
        draftStyle.update { it + (id to style) }
        styleSaves[id]?.cancel()
        styleSaves[id] = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            styleSaves.remove(id)
            saveStyle(id)
        }
    }

    private suspend fun saveStyle(id: Long) {
        val style = draftStyle.value[id] ?: return
        val b = current(id)
        if (b != null && b.style != style) change(b, b.copy(style = style), cleanInput = false)
        // Dropped only if no newer change came in meanwhile.
        draftStyle.update { m -> if (m[id] == style) m - id else m }
    }

    /** Saves every style still waiting (before undo, a page turn, a whole-page style). */
    private suspend fun saveStyles() {
        val ids = styleSaves.keys.toList()
        ids.forEach { styleSaves.remove(it)?.cancel() }
        draftStyle.value.keys.forEach { saveStyle(it) }
    }

    /** The edit the editor shows for sound effect [b]: the one being changed, else the saved one (or its default). */
    fun sfxOf(b: Bubble): SfxEdit = draftSfx.value[b.id] ?: SfxRenderer.editOf(b)

    /**
     * Previews [edit] at once and saves it (one undo step) when the changes pause. A change into or out of Replace
     * affects the clean layer (the original effect is erased or comes back), so the page is cleaned again.
     */
    fun setSfx(id: Long, edit: SfxEdit) {
        draftSfx.update { it + (id to edit) }
        sfxSaves[id]?.cancel()
        sfxSaves[id] = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            sfxSaves.remove(id)
            saveSfx(id)
        }
    }

    private suspend fun saveSfx(id: Long) {
        val edit = draftSfx.value[id] ?: return
        val b = current(id)
        if (b != null && b.sfx != edit) change(b, b.copy(sfx = edit), cleanInput = replaces(b.sfx) != replaces(edit))
        draftSfx.update { m -> if (m[id] == edit) m - id else m }
    }

    private suspend fun saveEffects() {
        sfxSaves.keys.toList().forEach { sfxSaves.remove(it)?.cancel() }
        draftSfx.value.keys.forEach { saveSfx(it) }
    }

    private fun replaces(e: SfxEdit?): Boolean = e?.mode == SfxEditMode.REPLACE

    /**
     * Remembers [b]'s effect for the whole manga: its source and replacement go into the manga's glossary, so the same
     * effect is translated the same way on every later page and chapter.
     */
    fun rememberEffect(b: Bubble) = viewModelScope.launch {
        val m = manga.value ?: return@launch
        val text = draftTranslation.value[b.id] ?: b.translatedText
        if (b.sourceText.isBlank() || text.isBlank()) return@launch
        glossary.upsert(GlossaryEntry(seriesKey = StudioTranslator.seriesKey(m.id), source = b.sourceText.trim(), target = text.trim()))
        remembered.value = b.id
    }

    /** The effect just remembered (the panel says so until another bubble is picked). */
    val remembered = MutableStateFlow<Long?>(null)

    /** A shape being dragged (null: the drag ended or was abandoned), shown on the Final layer before it is saved. */
    fun previewPolygon(id: Long, polygon: List<Pt>?) {
        draftPolygon.update { if (polygon == null) it - id else it + (id to polygon) }
    }

    /** [update] applied to every live bubble of the page, as one undo step. */
    fun setStyleForAll(update: (BubbleStyle) -> BubbleStyle) = viewModelScope.launch {
        saveStyles()
        val before = bubbles.value.filter { !it.ignored }
        val after = before.map { it.copy(style = update(it.style)) }.filterIndexed { i, b -> b.style != before[i].style }
        if (after.isEmpty()) return@launch
        val ids = after.map { it.id }.toSet()
        val was = before.filter { it.id in ids }
        after.forEach { repo.updateBubble(it) }
        record(Edit(
            undo = { was.forEach { b -> current(b.id)?.let { repo.updateBubble(it.copy(style = b.style)) } } },
            redo = { after.forEach { b -> current(b.id)?.let { repo.updateBubble(it.copy(style = b.style)) } } },
        ))
    }

    fun setPolygon(id: Long, polygon: List<Pt>) = viewModelScope.launch {
        val b = current(id)
        if (b != null && polygon.size >= 3 && b.polygon != polygon) change(b, b.copy(polygon = polygon), cleanInput = true)
        draftPolygon.update { it - id }
    }

    fun addStroke(stroke: BrushStroke) = viewModelScope.launch {
        val before = strokes.value
        val after = before + stroke
        putStrokes(after)
        record(Edit(undo = { putStrokes(before) }, redo = { putStrokes(after) }))
    }

    private suspend fun putStrokes(list: List<BrushStroke>) {
        val file = page.value?.file ?: return
        strokes.value = list
        withContext(Dispatchers.IO) { StrokeStore.write(repo.files.file(StudioFiles.layer(file, StudioFiles.STROKES)), list) }
        invalidateClean()
    }

    // ---- undo / redo ---------------------------------------------------------------------------

    private fun record(e: Edit) {
        undoStack.addLast(e)
        while (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        flags()
    }

    private fun flags() {
        canUndo.value = undoStack.isNotEmpty()
        canRedo.value = redoStack.isNotEmpty()
    }

    fun undo() = viewModelScope.launch {
        // Typing waiting to be saved is a step of its own: save it first, so undo takes it back.
        saves.keys.toList().forEach { saves.remove(it)?.cancel(); save(it) }
        saveStyles()
        saveEffects()
        val e = undoStack.removeLastOrNull() ?: return@launch
        e.undo()
        redoStack.addLast(e)
        flags()
    }

    fun redo() = viewModelScope.launch {
        val e = redoStack.removeLastOrNull() ?: return@launch
        e.redo()
        undoStack.addLast(e)
        flags()
    }

    // ---- engine actions ------------------------------------------------------------------------

    /** Translates the bubble again from its source text as it is now (an edit waiting to be saved is saved first). */
    fun retranslate(id: Long) = viewModelScope.launch {
        val pageId = page.value?.id ?: return@launch
        saves.remove(id)?.cancel()
        save(id)
        val before = current(id)
        busy.value = ReviewBusy.Retranslating(id)
        try {
            failure.value = translator.retranslateBubble(id, pageId)
            draftTranslation.update { it - id }
            val after = repo.bubbleList(pageId).firstOrNull { it.id == id }
            if (before != null && after != null && after.translatedText != before.translatedText) {
                record(Edit(undo = { putTexts(before) }, redo = { putTexts(after) }))
            }
        } finally {
            busy.value = null
        }
    }

    /**
     * A bubble (or, with [sfx], a sound effect) for the text inside [box] (page pixels), read and translated, then
     * selected. Undo deletes it.
     */
    fun addBubble(box: Box, sfx: Boolean = false) = viewModelScope.launch {
        val p = page.value ?: return@launch
        val rect = IntRect(box.left.roundToInt(), box.top.roundToInt(), box.right.roundToInt(), box.bottom.roundToInt()).clamp(p.width, p.height)
        if (rect.width < MIN_BOX || rect.height < MIN_BOX) return@launch
        busy.value = ReviewBusy.Adding
        try {
            val (id, f) = translator.addBubble(p.id, rect, if (sfx) RegionKind.SFX else RegionKind.ENCLOSED)
            failure.value = f
            if (id != null) {
                selected.value = id
                invalidateClean()
                var added = repo.bubbleList(p.id).firstOrNull { it.id == id } ?: return@launch
                record(Edit(
                    undo = { current(added.id)?.let { repo.deleteBubble(it) }; if (selected.value == added.id) selected.value = null; invalidateClean() },
                    redo = { added = added.copy(id = repo.addBubble(added)); selected.value = added.id; invalidateClean() },
                ))
            }
        } finally {
            busy.value = null
        }
    }

    /** Marks the page reviewed and moves to the next one (the last page stays). */
    fun markReviewedAndNext() = viewModelScope.launch {
        flushAll()
        val p = page.value?.id?.let { repo.getPage(it) } ?: return@launch
        if (!p.reviewed) repo.updatePage(p.copy(stages = p.stages + StudioStage.REVIEWED))
        if (pageIndex.value < pages.value.size - 1) goToPage(pageIndex.value + 1)
    }

    fun clearFailure() { failure.value = null }

    override fun onCleared() {
        flushAll()
        val all = listOfNotNull(tiles.value, cleanTiles.value, finalTiles.value)
        flushScope.launch { delay(RECYCLE_DELAY_MS); all.forEach { it.close() } }
    }

    companion object {
        const val SAVE_DELAY_MS = 400L
        const val RECYCLE_DELAY_MS = 300L
        const val RENDER_DEBOUNCE_MS = 150L
        /** A pause after a reshape or a stroke before the clean layer is rebuilt (more edits may follow). */
        const val CLEAN_DEBOUNCE_MS = 800L
        const val UNDO_LIMIT = 100
        /** A box smaller than this (page pixels) is a slip of the finger, not a bubble. */
        const val MIN_BOX = 12
    }
}
