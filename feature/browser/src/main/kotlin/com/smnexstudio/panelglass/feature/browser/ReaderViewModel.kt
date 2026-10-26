package com.smnexstudio.panelglass.feature.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.cache.RegionStore
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.SiteRepository
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.ui.languageName
import com.smnexstudio.panelglass.core.engine.mt.PageTranslator
import com.smnexstudio.panelglass.core.model.DialogueContext
import com.smnexstudio.panelglass.core.model.RetentionLimits
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ImageSource
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.pipeline.RegionOfInterest
import com.smnexstudio.panelglass.core.pipeline.SeriesResolver
import com.smnexstudio.panelglass.core.model.SavedTranslations
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.model.WebUrl
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.feature.browser.block.BlockListRepository
import com.smnexstudio.panelglass.feature.browser.web.MangaWebViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import kotlinx.coroutines.joinAll
import org.json.JSONObject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import org.json.JSONTokener
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * One viewport snapshot with the scroll state it was taken at, so its patches can be pinned to the page, plus the
 * page's own account of where its images are and which controls float over them (view pixels).
 */
class ViewportCapture(
    val bitmap: Bitmap, val scrollX: Int, val scrollY: Int, val scale: Float,
    val images: List<IntRect> = emptyList(), val overlays: List<IntRect> = emptyList(),
    /** What each of [images] is (its URL), in the same order; empty when the page could not say. */
    val imageKeys: List<String> = emptyList(),
    /** Where the page's images are now, and the scroll offset now; null when the page cannot say. */
    val layoutNow: (suspend () -> ImageLayout?)? = null,
    /** On-screen images that were still downloading when this was taken (painted partly or not at all). */
    val pending: Int = 0,
    /** How many on-screen images are still downloading now. */
    val pendingNow: (suspend () -> Int)? = null,
    /** A small picture of the viewport now (see [Thumb]). */
    val thumbNow: (suspend () -> Thumb?)? = null,
)

/**
 * The on-screen page images' rects in the viewport (view pixels at [scale]) with their keys, at a given scroll
 * offset (page pixels at [scale]).
 */
class ImageLayout(val images: List<IntRect>, val keys: List<String>, val scrollX: Int, val scrollY: Int, val scale: Float = 1f) {
    /**
     * Which of the page's images is the [occurrence]-th one with this key.
     *
     * The same picture twice on one page is two images, not one: a record saved from the second must not be drawn
     * on the first. The old code always took the first match, which put a translation on the wrong picture.
     */
    fun occurrenceIndex(key: String, occurrence: Int): Int {
        if (occurrence < 0) return -1
        var seen = 0
        for (i in keys.indices) {
            if (keys[i] != key) continue
            if (seen == occurrence) return i
            seen++
        }
        return -1
    }
}

/**
 * Where a patch stands on the page image it was made from: that image's key and the patch's offset from the
 * image's top-left corner, in CSS pixels (zoom-independent).
 */
class Anchor(
    val key: String, val dx: Float, val dy: Float,
    /** The image's CSS size when the anchor was made; 0 when it is not known. */
    val imgW: Float = 0f, val imgH: Float = 0f,
    /**
     * Which occurrence of [key] this anchor belongs to: 0 is the first picture with that key, 1 the second.
     *
     * The same picture twice on one page is two images, and a patch belongs to the one it was found on. The old
     * code always took the **first** match, which moved a patch on the second picture to the first one — a
     * leftward shift of the whole bubble.
     */
    val occurrence: Int = 0,
    /**
     * The patch's size as a fraction of the image's own size.
     *
     * The reanchor needs it: when the image is laid out at another width, the bubble's size has to follow the
     * image, and a fixed CSS size does not.
     */
    val nw: Float = 0f, val nh: Float = 0f,
    /** The patch's place as a fraction of the image's own size: left/width, top/height. */
    val nx: Float = 0f, val ny: Float = 0f,
)

/**
 * The anchor for a patch at [left],[top] (view pixels at [scale]) whose centre lies on one of [images]; null on
 * none.
 */
internal fun anchorFor(
    left: Int, top: Int, width: Int, height: Int, images: List<IntRect>, keys: List<String>, scale: Float = 1f,
): Anchor? {
    val cx = left + width / 2; val cy = top + height / 2
    val i = images.indices.firstOrNull { images[it].contains(cx, cy) && keys.getOrNull(it).orEmpty().isNotEmpty() } ?: return null
    val img = images[i]
    // The image's CSS size: a saved translation is a fraction of it, so it survives a change of zoom or width.
    val imgW = img.width / scale
    val imgH = img.height / scale
    // The fraction of the image the patch takes, so a later reanchor can resize it with the image.
    val nx = (left - img.left) / scale / imgW
    val ny = (top - img.top) / scale / imgH
    val nw = width / scale / imgW
    val nh = height / scale / imgH
    return Anchor(
        keys[i], (left - img.left) / scale, (top - img.top) / scale, imgW, imgH,
        occurrenceOf(i, keys), nw, nh, nx, ny,
    )
}

/**
 * Which of the page's images a patch at [view] (view pixels) is on; -1 when it is on none of them.
 *
 * The patch's **centre** decides, the same way the anchor has always decided: a bubble at the edge of an image
 * belongs to the image it mostly lies on.
 */
internal fun imageIndexFor(view: Geometry.ViewRect, images: List<IntRect>, keys: List<String>): Int {
    val cx = view.left + view.width / 2
    val cy = view.top + view.height / 2
    return images.indices.firstOrNull { images[it].contains(cx, cy) && keys.getOrNull(it).orEmpty().isNotEmpty() } ?: -1
}

/**
 * Which occurrence of its key the image at [index] is: the first image with that key is 0, the second 1, and so on.
 *
 * The same picture twice on one page is two images, not one.
 */
internal fun occurrenceOf(index: Int, keys: List<String>): Int {
    if (index < 0 || index >= keys.size) return -1
    val key = keys[index]
    var seen = 0
    for (i in 0 until index) {
        if (keys[i] == key) seen++
    }
    return seen
}

/**
 * Position (CSS pixels) of a patch anchored to an image, from where that image is [now]; null when it is not on
 * screen. The patch stays on its art whatever moved it: a reader re-centring its page in its own layer (bilibili,
 * 11 px), scroll anchoring when a lazy image above the viewport finished loading (rawkuma: the art stayed put on
 * screen while the scroll offset jumped 94 px), or a zoom.
 */
internal fun anchoredPosition(anchor: Anchor, now: ImageLayout): Pair<Float, Float>? {
    val i = now.occurrenceIndex(anchor.key, anchor.occurrence).takeIf { it >= 0 } ?: return null
    val img = now.images[i]
    return Pair((now.scrollX + img.left) / now.scale + anchor.dx, (now.scrollY + img.top) / now.scale + anchor.dy)
}

/**
 * The box of a patch anchored to an image, from where that image is [now]: where it is and how big it is.
 *
 * Both come from the same normalized geometry, so they cannot disagree: a patch that is a fraction of its image
 * stays that fraction of it, whatever width the page is laid out at. The old code moved the patch by its old CSS
 * offset but never resized it, so a page that was laid out again left the bubble at the old place and the old size.
 */
internal fun anchoredRect(anchor: Anchor, now: ImageLayout): Geometry.PageRect? {
    val i = now.occurrenceIndex(anchor.key, anchor.occurrence).takeIf { it >= 0 } ?: return null
    val img = now.images[i]
    if (!now.scale.isFinite() || now.scale <= 0f) return null
    val imgWCss = img.width / now.scale
    val imgHCss = img.height / now.scale
    if (imgWCss <= 0f || imgHCss <= 0f) return null
    // The fraction, when it is known: it survives a change of width. The CSS offset, when it is not.
    val nx = if (anchor.nw > 0f) anchor.nx else anchor.dx / imgWCss
    val ny = if (anchor.nh > 0f) anchor.ny else anchor.dy / imgHCss
    val nw = if (anchor.nw > 0f) anchor.nw else 0f
    val nh = if (anchor.nh > 0f) anchor.nh else 0f
    val leftCss = (now.scrollX + img.left) / now.scale + nx * imgWCss
    val topCss = (now.scrollY + img.top) / now.scale + ny * imgHCss
    return Geometry.PageRect(
        leftCss = leftCss, topCss = topCss,
        widthCss = if (nw > 0f) nw * imgWCss else 0f,
        heightCss = if (nh > 0f) nh * imgHCss else 0f,
    )
}

/**
 * A rendered, decoded patch placed in CSS pixels (page pixels / zoom), so it stays glued to the art while the page
 * scrolls and while it is zoomed: [rectAt] is where it is drawn at a given zoom. A bitmap made at one zoom is only
 * scaled at another.
 */
class ScreenPatch(val image: ImageBitmap, val left: Float, val top: Float, val width: Float, val height: Float, val anchor: Anchor? = null) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    fun at(left: Float, top: Float) = ScreenPatch(image, left, top, width, height, anchor)

    /** Page (content) pixels at [scale], the WebView's zoom. */
    fun rectAt(scale: Float) = IntRect(
        (left * scale).roundToInt(), (top * scale).roundToInt(), (right * scale).roundToInt(), (bottom * scale).roundToInt(),
    )

    fun overlapFraction(o: ScreenPatch): Float {
        val w = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0f)
        val h = (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0f)
        val area = width * height
        return if (area <= 0f) 0f else w * h / area
    }

    /** The same bubble seen from two viewports: mostly overlapping *and* about the same size. A big crop (a sound effect's) that merely covers a small neighbour is not its duplicate. */
    fun duplicates(o: ScreenPatch, minOverlap: Float): Boolean {
        if (overlapFraction(o) < minOverlap) return false
        val a = width * height; val b = o.width * o.height
        return maxOf(a, b) <= minOf(a, b) * DUPLICATE_SIZE_RATIO
    }

    private companion object { const val DUPLICATE_SIZE_RATIO = 2.5f }
}

/**
 * Continuous screen translation (Start in the reader): the viewport is captured whenever the page settles after a
 * scroll, run through the pipeline, and the patches are kept in page coordinates so they scroll with the content.
 * Nothing is fetched: this works on any reader, whatever it does with its images.
 */
data class ScreenSession(
    val active: Boolean = false,
    val working: Boolean = false,
    val patches: List<ScreenPatch> = emptyList(),
    val regions: Int = 0,
    val error: String? = null,
    /** The typed failure behind [error], when an engine produced it: the bottom bar offers its fix (add key, switch). */
    val failure: EngineFailure? = null,
    /** The progress of a whole-page scan; null when no scan is running. */
    val scan: ScanProgress? = null,
)

data class ReaderUiState(
    val url: String = "",
    val title: String = "",
    val blockedCount: Int = 0,
    val adBlockEnabled: Boolean = true,
    val stripDismissed: Boolean = false,
    val site: Site? = null,
    val config: TranslateConfig? = null,
    val canGoBack: Boolean = false,
    /** Page-load progress, 0..100, from the WebView; 100 when nothing is loading. */
    val progress: Int = 100,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pipeline: TranslationPipeline,
    private val sites: SiteRepository,
    private val settingsRepo: SettingsRepository,
    private val blockLists: BlockListRepository,
    private val history: HistoryRepository,
    private val registry: EngineRegistry,
    private val pageTranslator: PageTranslator,
    private val packs: LanguagePackStore,
    val warmup: ReaderWarmup,
    private val glossary: GlossaryRepository,
    private val regions: RegionStore,
) : ViewModel(), MangaWebViewClient.Listener {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui

    // ---- the chapter's memory: the lines the reader has already translated -------------------------------
    // In RAM only, and cleared when the page changes: a new chapter is a new conversation.
    private val memory = ChapterMemory()
    /** The user's character sheet for the page being read, in the page's own host. */
    @Volatile private var castText = ""

    // ---- the saved translations -------------------------------------------------------------------
    /** Bumped at every change of page: a save or a restore that started before it must not touch the new page. */
    @Volatile private var pageGen = 0
    /** The last capture's viewport height, in pixels; the memory trim needs it. */
    @Volatile private var viewportHeight = 0
    /** The scope the saved translations belong to: the chapter the reader is in. */
    @Volatile private var scope: String = ""
    /** The fingerprint of the settings the current translations were made with. */
    @Volatile private var fingerprint: String = ""
    /** The work of saving and restoring, off the main thread. */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The saves that have been started and not yet finished.
     *
     * A whole-page scan is not done when the last translation is: the patches it produced still have to be
     * encoded and written. The reader scrolls back to the top as soon as the scan ends, and the restore there
     * finds nothing if the writes have not landed yet. The counter is what lets the scan wait for them.
     */
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Waits until the saves that were started have finished.
     *
     * It is bounded: a save that hangs must not hold the reader at the end of a scan.
     */
    private suspend fun awaitPendingSaves(timeoutMs: Long = SAVE_BARRIER_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (pendingSaves.get() > 0 && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        if (pendingSaves.get() > 0) {
            android.util.Log.d("ScreenSession", "save barrier timed out: pending=${pendingSaves.get()}")
        }
    }

    // ---- the series' names, pronouns and notes (⋮ › Glossary & pronouns) ------------------------------------

    private val series = SeriesMemory(
        glossary, viewModelScope,
        seriesKey = _ui.map { it.config?.seriesKey.orEmpty() },
        keyNow = { _ui.value.config?.seriesKey.orEmpty() },
    )
    val seriesTerms: StateFlow<List<GlossaryEntry>> get() = series.terms
    val seriesNotes: StateFlow<String> get() = series.notes

    fun saveTerm(entry: GlossaryEntry) = series.saveTerm(entry)
    fun deleteTerm(entry: GlossaryEntry) = series.deleteTerm(entry)
    fun saveSeriesNotes(text: String) = series.saveNotes(text)

    /** The sheet closed: a running screen session translates the viewport again with what was edited. */
    fun seriesSheetClosed() {
        val writes = series.takeWrites()
        if (writes.isEmpty()) return
        if (_screen.value.active) viewModelScope.launch {
            writes.joinAll()
            clearScreenPatches("glossary"); translateViewport()
        }
    }

    private var web: WebView? = null

    /** "Translate page" in the menu: the page's own text, in place (ML Kit). */
    val pageText = PageTextTranslation(
        viewModelScope, pageTranslator,
        eval = { js -> evalInPage(js) },
        onTextChanged = { if (_screen.value.active) { clearScreenPatches("pageText"); translateViewport() } },
        saveTarget = { settingsRepo.setPageTranslateTarget(it) },
        fetchPacks = { packs.downloadNow(it) },
    )

    /** Runs [js] in the page; its JSON result, or null without a page or when the reply is over [MAX_BRIDGE_REPLY]. */
    private suspend fun evalInPage(js: String): String? = withContext(Dispatchers.Main) {
        val wv = web ?: return@withContext null
        suspendCancellableCoroutine<String?> { cont ->
            try { wv.evaluateJavascript(js) { v -> if (cont.isActive) cont.resume(v) } }
            catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }?.takeIf { it.length <= MAX_BRIDGE_REPLY }
    }

    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val userModelList = settingsRepo.userModels.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- whole-page scan: measuring and moving the page ---------------------------------------

    /** How tall the page is now, in view pixels at the current zoom; null when the page cannot say. */
    @Suppress("DEPRECATION")
    suspend fun measurePage(): PageMetrics? {
        val wv = web ?: return null
        val raw = evalInPage("(function(){var d=document,b=d.body;return JSON.stringify({h:Math.max(d.documentElement.scrollHeight,b?b.scrollHeight:0)})})()") ?: return null
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() ?: return null
        val h = runCatching { JSONObject(text).getDouble("h") }.getOrNull() ?: return null
        return withContext(Dispatchers.Main) { PageMetrics((h * wv.scale).toInt(), wv.height, wv.scrollY) }
    }

    /** Scrolls the page to [y] (page pixels at the current zoom). */
    private suspend fun scrollPageTo(y: Int) = withContext(Dispatchers.Main) { web?.let { it.scrollTo(it.scrollX, y) } }

    /** Where the page is scrolled to now. */
    private suspend fun currentScrollY(): Int = withContext(Dispatchers.Main) { web?.scrollY ?: 0 }

    private val _screen = MutableStateFlow(ScreenSession())
    val screen: StateFlow<ScreenSession> = _screen
    /** Current WebView scroll offset, fed from the view; the overlay subtracts it to place page-space patches. */
    private val _scroll = MutableStateFlow(Pair(0, 0))
    val scroll: StateFlow<Pair<Int, Int>> = _scroll
    /** The WebView's current zoom (`WebView.scale`); the overlay multiplies the patches' CSS pixels by it. */
    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom

    private var capture: (suspend () -> ViewportCapture?)? = null
    private var screenJob: Job? = null
    private var settleJob: Job? = null
    private var lastCaptureScroll: Pair<Int, Int>? = null
    private var lastViewport = Pair(0, 0)

    // ---- whole-page scan ---------------------------------------------------------------------
    /** The scan's own coroutine: one capture at a time, from the top of the page to the bottom. */
    private var scanJob: Job? = null
    /** True while the scan is moving the page: the scroll it makes must not start captures of its own. */
    @Volatile private var scanning = false
    /** True while the page is held (its patches are kept whole, under a memory budget instead of a screen count). */
    private var holdPage = false
    /** The page the scan is on; a page change drops the scan and its patches. */
    private var scanPageKey: String? = null
    /** Where the scan started, to scroll back to when it ends. */
    private var scanStart: Pair<Int, Int> = Pair(0, 0)
    /** Whether the scan should scroll back to where it started when it ends. */
    private var scanRestore = false
    /**
     * Blocks the last capture could not read because its edge cut them, in page pixels at [deferredScale]: they are
     * what a small scroll reveals.
     */
    private var deferred: List<IntRect> = emptyList()
    private var deferredScale = 1f

    /** Start: translate what is on screen now, then keep doing so as the reader scrolls. */
    fun startScreen(capture: suspend () -> ViewportCapture?) {
        this.capture = capture
        _screen.value = ScreenSession(active = true)
        lastCaptureScroll = null
        translateViewport()
    }

    fun stopScreen() {
        cancelScan(restore = false)
        holdPage = false
        loadedJob?.cancel(); loadedJob = null
        gestureJob?.cancel(); gestureJob = null
        settleJob?.cancel(); settleJob = null
        screenJob?.cancel(); screenJob = null
        capture = null
        android.util.Log.d("ScreenSession", "clear reason=stop")
        pageGen++
        _screen.value = ScreenSession()
    }

    /**
     * From the WebView: every scroll step, with its zoom. Patches move with the page and scale with a zoom (they are
     * kept in CSS pixels); a settled new viewport is translated.
     */
    fun onScroll(x: Int, y: Int, scale: Float) {
        _scroll.value = Pair(x, y)
        val zoomed = abs(scale - _zoom.value) > ZOOM_EPSILON
        _zoom.value = scale
        if (!_screen.value.active) return
        // A scan scrolls the page itself: its captures would collide with the ones the scroll would start.
        if (scanning) return
        // A zoom keeps every patch on its art. Once it settles the viewport is captured again: the bubbles already
        // patched are skipped, so only what zooming out brought into view (or made readable) is read.
        if (zoomed) lastCaptureScroll = null
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(SETTLE_MS)
            // A scroll the user did not make (scroll anchoring as images above load) leaves the art where it was on
            // screen: the patches follow their images, not the offset.
            reanchor()
            val last = lastCaptureScroll
            val (vw, vh) = lastViewport
            val moved = last == null || abs(y - last.second) >= vh * MIN_MOVE || abs(x - last.first) >= vw * MIN_MOVE
            // A block the last capture had to skip (cut by the edge) is worth a capture on its own once it is whole on screen.
            val k = scale / deferredScale
            val revealed = deferred.any {
                it.top * k >= y + EDGE_MARGIN && it.bottom * k <= y + vh - EDGE_MARGIN && it.left * k >= x && it.right * k <= x + vw
            }
            // The saved translations come back **first**, whether the page moved or not.
            //
            // They are the cheapest thing the reader can do: no capture, no detector, no OCR, no engine. A
            // reader who scrolls back to a page they have already paid for should see it again at once, and the
            // capture is only needed for what is still missing. This is why the movement threshold no longer
            // decides whether the saved translations come back.
            val restored = restoreVisible()
            // A capture is still needed for the bubbles that were never translated.
            if (moved || revealed || restored == 0) translateViewport()
        }
    }

    /** From the WebViewClient: the zoom changed (a pinch need not scroll, and scroll steps can lag the scale). */
    override fun onScaleChanged(scale: Float) = onScroll(_scroll.value.first, _scroll.value.second, scale)

    private fun translateViewport() {
        val cfg = _ui.value.config ?: return
        val cap = capture ?: return
        screenJob?.cancel()
        screenJob = viewModelScope.launch { translateViewportOnce(followUps = true) }
    }

    /**
     * What one viewport translation did: whether it captured at all, the failure it hit, and the follow-up the
     * caller may want (images that were still downloading).
     */
    private class StepOutcome(
        val captured: Boolean,
        val failure: EngineFailure? = null,
        val pending: Int = 0,
        val pendingNow: (suspend () -> Int)? = null,
    )

    /**
     * One viewport: capture it, run the pipeline, add the patches. [followUps] starts the two follow-up captures
     * (a page that was still loading, a canvas page with no text); a whole-page scan owns its own capturing and
     * does not want them.
     */
    private suspend fun translateViewportOnce(followUps: Boolean): StepOutcome {
        val cfg = _ui.value.config ?: return StepOutcome(false)
        val cap = capture ?: return StepOutcome(false)
        val c = cap()
        if (c == null) {
            _screen.update { it.copy(working = false, error = context.getString(UiR.string.reader_capture_failed)) }
            return StepOutcome(false)
        }
        lastCaptureScroll = Pair(c.scrollX, c.scrollY)
        lastViewport = Pair(c.bitmap.width, c.bitmap.height)
        lastThumb = Thumb.of(c.bitmap); thumbNow = c.thumbNow
        // The zoom the pixels were taken at is the live one (a page that never scrolled has sent no onScroll yet).
        _zoom.value = c.scale
        _screen.update { it.copy(working = true, error = null, failure = null) }
        val w = c.bitmap.width; val h = c.bitmap.height
        // The viewport's height, for the memory trim: what is on screen is what the user is looking at.
        viewportHeight = h
        // The settings this capture is translated under, resolved now: a change made after the page opened must
        // not be filed under the settings of that moment. It is resolved **before** the restore, so the restore
        // matches the policy that is in force and not the one of the moment the page opened.
        refreshFingerprint(cfg)
        val fp = fingerprint
        // The saved translations come back first: they are in the "already done" list below, so the pipeline
        // never detects, reads or translates them again.
        promoteSaved(c)
        // Bubbles an earlier capture already patched (at any zoom) are not read or translated again (the slow
        // part of a scroll).
        val done = _screen.value.patches.map { it.rectAt(c.scale).offset(-c.scrollX, -c.scrollY) }
        val disc = StatusDiscSpec.rectIn(w, context.resources.displayMetrics.density)
        val roi = RegionOfInterest(include = c.images, exclude = c.overlays + disc, done = done)
        val bypass = bypassCacheOnce; bypassCacheOnce = false
        // The page generation at the start of this capture: a save that finishes after the page changed is dropped.
        val gen = pageGen
        // How many patches were on the screen BEFORE this capture: the number is about the capture, and reading
        // it afterwards counted the patches the capture itself had just added.
        val patchesBefore = _screen.value.patches.size
        var failure: EngineFailure? = null
        // The dialogue-context level is read NOW, not taken from the config that was captured when the page opened:
        // a change in Settings must take effect without reopening the page.
        val level = settingsRepo.current().dialogueContext
        val cfgNow = if (level == DialogueContext.OFF) cfg else cfg.copy(
            dialogueContext = level,
            castSheet = castText,
            speakerTags = level != DialogueContext.OFF && !cfg.engineId.isLocalLlm,
        )
        try {
            val result = pipeline.translate(
                ImageSource.Native(c.bitmap), cfgNow, imageId = "screen", roi = roi, bypassCache = bypass,
                contextFor = if (level == DialogueContext.OFF) null else { topPx ->
                    // The snapshot's pixels to the page's: the same factor the patch code uses.
                    memory.linesAbove((c.scrollY + topPx) / c.scale, cfgNow.contextLimit())
                },
            ) { patch ->
                // onPatch runs on the pipeline's worker: decode here, never in composition.
                val img = decodePatch(patch) ?: return@translate
                // The capture's numbers, taken once: the viewport's size, the scroll offset and the zoom all
                // belong to this snapshot. The percentages become view pixels here, and nowhere else.
                val geo = Geometry.CaptureGeometry(w, h, c.scrollX, c.scrollY, c.scale)
                val view = Geometry.viewRect(patch.xPct, patch.yPct, patch.wPct, patch.hPct, geo) ?: return@translate
                val page = Geometry.pageRect(view, geo) ?: return@translate
                // The image this bubble is really on, and which occurrence of it: the same picture twice on one
                // page is two images, and a record belongs to the one it was made from.
                val imageIndex = imageIndexFor(view, c.images, c.imageKeys)
                val occurrence = if (imageIndex >= 0) occurrenceOf(imageIndex, c.imageKeys) else -1
                val imageBox = if (imageIndex >= 0) c.images[imageIndex] else null
                val imageKey = if (imageIndex >= 0) c.imageKeys.getOrNull(imageIndex).orEmpty() else ""
                val anchor = if (imageIndex >= 0) {
                    // The anchor carries the CSS offset (for the ordinary scroll) **and** the fraction of the
                    // image (for a page that is laid out again at another width). Both are the same geometry,
                    // measured once.
                    val norm = Geometry.normalizedRect(view, imageBox!!, geo)
                    Anchor(
                        imageKey,
                        (view.left - imageBox.left) / c.scale, (view.top - imageBox.top) / c.scale,
                        imageBox.width / c.scale, imageBox.height / c.scale,
                        occurrence, norm?.nw ?: 0f, norm?.nh ?: 0f, norm?.nx ?: 0f, norm?.ny ?: 0f,
                    )
                } else null
                val sp = ScreenPatch(
                    img, left = page.leftCss, top = page.topCss,
                    width = page.widthCss, height = page.heightCss,
                    anchor = anchor,
                )
                // The record to save, built from the live geometry. It is made here, outside the state update:
                // a state update may be evaluated more than once, and a save must happen exactly once.
                val accepted = if (anchor != null && imageBox != null) {
                    RetentionCoordinator.accept(
                        imageKey = imageKey, occurrence = occurrence, view = view, image = imageBox,
                        geo = geo, fingerprint = fp, createdAtMs = System.currentTimeMillis(),
                    )
                } else null
                _screen.update { s ->
                    // Overlapping viewports see the same bubble twice: a patch mostly covered by an existing one
                    // is a duplicate; conversely a fragment (a bubble cut by the last viewport edge) mostly
                    // covered by the new, fuller patch gives way to it.
                    val dup = s.patches.any { sp.duplicates(it, DUPLICATE_OVERLAP) }
                    android.util.Log.d("ScreenSession", "patch ${sp.left},${sp.top} ${sp.width}x${sp.height} scroll=${c.scrollX},${c.scrollY} dup=$dup")
                    if (dup) s
                    else {
                        val kept = keepPatches(s.patches.filter { !it.duplicates(sp, DUPLICATE_OVERLAP) } + sp, c.scrollY, h, c.scale)
                        android.util.Log.d("ScreenSession", "retain patches=${kept.size} bytes=${kept.sumOf { it.bytes }}")
                        s.copy(patches = kept)
                    }
                }
                // The translation is saved, so that coming back to it costs no engine call. The save happens
                // outside the state update, so it happens exactly once per accepted patch.
                if (accepted != null) savePatch(patch, accepted)
            }
            layoutNow = c.layoutNow ?: layoutNow
            reanchor()
            result.fold(
                onSuccess = { page ->
                    deferred = page.deferred.map { IntRect(c.scrollX + it.left, c.scrollY + it.top, c.scrollX + it.right, c.scrollY + it.bottom) }
                    deferredScale = c.scale
                    if (level != DialogueContext.OFF && page.lines.isNotEmpty()) {
                        // The lines of this screen join the memory, with their place on the page: the region's box
                        // is in the snapshot's pixels, and the page's position is the same conversion the patch uses.
                        memory.add(page.lines.map { line ->
                            val left = (c.scrollX + line.bbox.left) / c.scale
                            val top = (c.scrollY + line.bbox.top) / c.scale
                            ChapterMemory.Entry(
                                top = top, left = left,
                                source = line.source, translation = line.translation, speaker = line.speaker,
                            )
                        })
                    }
                    android.util.Log.d(
                        "ScreenSession",
                        "capture end regions=${page.regionCount} fromCache=${page.fromCache} " +
                            "patchesBefore=$patchesBefore patchesAfter=${_screen.value.patches.size}",
                    )
                    _screen.update { it.copy(working = false, regions = it.regions + page.regionCount) }
                    if (followUps) {
                        if (c.pending > 0) recaptureWhenLoaded(c)
                        else if (page.regionCount == 0 && c.imageKeys.any { it.startsWith("canvas#") }) retryEmptyCanvas(c)
                    }
                },
                onFailure = { e ->
                    val f = (e as? EngineException)?.failure
                    failure = f
                    _screen.update { it.copy(working = false, error = screenError(f), failure = f) }
                },
            )
        } finally { c.bitmap.recycle() }
        return StepOutcome(captured = true, failure = failure, pending = c.pending, pendingNow = c.pendingNow)
    }

    // ---- whole-page scan ------------------------------------------------------------------------

    /**
     * Translates the whole page: the page is scrolled from the top to the bottom and every stop is translated
     * with the same machinery as the continuous mode. The patches stay while the user stays on the page.
     */
    fun startFullPage(capture: suspend () -> ViewportCapture?) {
        if (_ui.value.config == null) return
        if (scanning) return
        if (!_screen.value.active) {
            this.capture = capture
            _screen.value = ScreenSession(active = true)
            lastCaptureScroll = null
        }
        holdPage = true
        scanPageKey = PageScanPlan.pageKey(_ui.value.url)
        scanRestore = true
        // The scan does its own capturing: the ones the screen would start on its own are dropped.
        settleJob?.cancel(); settleJob = null
        screenJob?.cancel(); screenJob = null
        loadedJob?.cancel(); loadedJob = null
        gestureJob?.cancel(); gestureJob = null
        scanJob?.cancel()
        scanJob = viewModelScope.launch { runScan() }
    }

    /**
     * Stops a running scan. [restore] scrolls the page back to where the scan started (the Cancel button does;
     * a touch on the page does not, because the user took over).
     */
    fun cancelScan(restore: Boolean = true) {
        if (!scanning && scanJob == null) { scanRestore = false; return }
        scanRestore = restore
        scanJob?.cancel()
        scanJob = null
    }

    /** A touch on the page during a scan: the user took over, so the page is not scrolled back. */
    fun onUserTouch() = cancelScan(restore = false)

    /**
     * The scan itself: scroll, settle, translate, next. One capture at a time, from the top of the page to the
     * bottom, and back to where it started.
     */
    private suspend fun runScan() {
        scanning = true
        withContext(Dispatchers.Main) { web?.keepScreenOn = true }
        scanStart = withContext(Dispatchers.Main) { web?.let { Pair(it.scrollX, it.scrollY) } ?: Pair(0, 0) }
        var gaps = 0
        try {
            val first = measurePage()
            if (first == null) {
                _screen.update { it.copy(error = context.getString(UiR.string.reader_capture_failed)) }
                return
            }
            // A page that does not scroll as a window: only what is on screen can be translated.
            if (PageScanPlan.maxScrollY(first.pageHeight, first.viewportHeight) == 0) {
                scanStep()
                _screen.update { it.copy(error = context.getString(UiR.string.reader_scan_no_scroll)) }
                return
            }
            var y = 0
            var step = 0
            var metrics: PageMetrics = first
            while (step < PageScanPlan.MAX_STEPS) {
                scrollPageTo(y)
                delay(SCAN_SETTLE_MS)
                val at = currentScrollY()
                // The page did not move although it should have: it does not scroll as a window.
                if (step == 0 && y > 0 && abs(at - y) > SCAN_SCROLL_EPSILON) {
                    scanStep()
                    _screen.update { it.copy(error = context.getString(UiR.string.reader_scan_no_scroll)) }
                    return
                }
                metrics = measurePage() ?: metrics
                _screen.update { it.copy(scan = ScanProgress(step + 1, PageScanPlan.steps(metrics.pageHeight, metrics.viewportHeight), gaps)) }
                val outcome = scanStep()
                val f = outcome.failure
                // These end the scan at once: the bottom bar already offers their fix.
                if (f is EngineFailure.MissingKey || f is EngineFailure.QuotaExceeded || f is EngineFailure.PackMissing) return
                // No capture at all: the app is not on screen (PixelCopy needs it).
                if (!outcome.captured) {
                    _screen.update { it.copy(error = context.getString(UiR.string.reader_scan_paused)) }
                    return
                }
                if (f != null) gaps++
                val max = PageScanPlan.maxScrollY(metrics.pageHeight, metrics.viewportHeight)
                if (y >= max) {
                    // The bottom of the page: a lazy page may still grow, so it is measured once more.
                    delay(SCAN_BOTTOM_WAIT_MS)
                    val again = measurePage()
                    val newMax = if (again == null) max else PageScanPlan.maxScrollY(again.pageHeight, again.viewportHeight)
                    if (newMax <= y + 1) break
                    metrics = again ?: metrics
                    y = newMax
                } else {
                    y = minOf(y + PageScanPlan.stepPx(metrics.viewportHeight), max)
                }
                step++
            }
            if (gaps > 0) {
                _screen.update { it.copy(error = context.getString(UiR.string.reader_scan_gaps, gaps)) }
            }
        } finally {
            scanning = false
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { web?.keepScreenOn = false }
                // The scan is not over when the last translation is: the patches it produced still have to be
                // written. The reader scrolls back to the top a moment later, and the restore there finds
                // nothing if the writes have not landed. The wait is bounded: a save that hangs must not hold
                // the reader.
                awaitPendingSaves()
                _screen.update { it.copy(scan = null, working = false) }
                // The page goes back to where the scan started: on a normal end, and on a cancel by the button.
                // A cancel by a touch keeps the place the user took over at (scanRestore is false then).
                if (scanRestore) scrollPageTo(scanStart.second)
            }
        }
    }

    /**
     * One stop of the scan: capture and translate what is on screen. A capture that did not come back (the app is
     * not on screen) and a transient failure are both tried again; the rest is left to the caller.
     */
    private suspend fun scanStep(): StepOutcome {
        var o = translateViewportOnce(followUps = false)
        var tries = 0
        while (!o.captured && tries < 2) { delay(1_000L); o = translateViewportOnce(false); tries++ }
        tries = 0
        while (o.failure.isTransient() && tries < 2) { delay(2_000L * (tries + 1)); o = translateViewportOnce(false); tries++ }
        if (o.captured && o.failure == null && o.pending > 0) {
            // Images were still downloading: wait for them (bounded), then capture the same place again (the
            // bubbles already patched are skipped).
            val until = System.currentTimeMillis() + SCAN_LOAD_WAIT_MS
            while (System.currentTimeMillis() < until && (o.pendingNow?.invoke() ?: 0) > 0) delay(500L)
            o = translateViewportOnce(false)
        }
        return o
    }

    /** Whether this failure is worth another try. */
    private fun EngineFailure?.isTransient() = this is EngineFailure.Overloaded || this is EngineFailure.Network || this is EngineFailure.Unavailable

    private var loadedJob: Job? = null

    /**
     * A capture that went ahead while pages were still downloading (a slow image host) read only what was painted.
     * Once those pages finish, the same viewport is captured again; the bubbles already patched are skipped, so
     * only what appeared since is read. A scroll in the meantime starts its own capture and ends this wait.
     */
    private fun recaptureWhenLoaded(c: ViewportCapture) {
        val pendingNow = c.pendingNow ?: return
        loadedJob?.cancel()
        loadedJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + LOAD_FOLLOW_UP_MS
            while (System.currentTimeMillis() < deadline) {
                delay(LOAD_POLL_MS)
                if (lastCaptureScroll != Pair(c.scrollX, c.scrollY) || !_screen.value.active) return@launch
                if (pendingNow() == 0) {
                    android.util.Log.d("ScreenSession", "pages finished loading: capturing again")
                    translateViewport(); return@launch
                }
            }
        }
    }

    private var lastThumb: Thumb? = null
    private var thumbNow: (suspend () -> Thumb?)? = null
    private var gestureJob: Job? = null

    /**
     * From the WebView: a touch gesture ended. A scroll is handled by [onScroll]; this catches the gesture that
     * changed the page without scrolling it — a paged reader turning its page (bilibili), whose old patches would
     * otherwise stay over the new page. The viewport is compared with the last capture outside the patches; if it
     * shows something else, the patches on screen are dropped and it is translated again.
     */
    fun onGestureEnd() {
        // A scan moves the page itself: a gesture of its own would be compared against the wrong viewport.
        if (scanning) return
        if (!_screen.value.active) return
        gestureJob?.cancel()
        gestureJob = viewModelScope.launch {
            delay(GESTURE_SETTLE_MS)
            val at = lastCaptureScroll ?: return@launch
            if (_scroll.value != at) return@launch
            val before = lastThumb ?: return@launch
            val now = thumbNow?.invoke() ?: return@launch
            val (vw, vh) = lastViewport
            val view = IntRect(at.first, at.second, at.first + vw, at.second + vh)
            val zoom = _zoom.value
            val patched = _screen.value.patches.map { it.rectAt(zoom).offset(-at.first, -at.second) }
            if (contentChanged(before, now, patched) != true) return@launch
            android.util.Log.d("ScreenSession", "page changed without a scroll: translating it again")
            _screen.update { s -> s.copy(patches = s.patches.filterNot { it.rectAt(zoom).intersects(view) }) }
            emptyRetriedAt = null
            translateViewport()
        }
    }

    /** From the WebView: its width changed (rotation). The page is laid out anew, so the patches are dropped and the new viewport translated once it settles. */
    fun onViewportResized() {
        if (!_screen.value.active) return
        cancelScan(restore = false)
        holdPage = false
        screenJob?.cancel(); loadedJob?.cancel(); gestureJob?.cancel()
        android.util.Log.d("ScreenSession", "clear reason=rotation")
        _screen.update { it.copy(patches = emptyList(), working = false) }
        lastCaptureScroll = null; lastThumb = null; emptyRetriedAt = null
        settleJob?.cancel()
        settleJob = viewModelScope.launch { delay(RESIZE_SETTLE_MS); translateViewport() }
    }

    /** The scroll offset an empty canvas capture was already retried at: one retry per place. */
    private var emptyRetriedAt: Pair<Int, Int>? = null

    /**
     * A reader that draws its pages on a canvas (bilibili) shows a loading picture there until the page arrives,
     * and nothing in the DOM says when it has: a capture that found no text there is tried once more, shortly after.
     */
    private fun retryEmptyCanvas(c: ViewportCapture) {
        val at = Pair(c.scrollX, c.scrollY)
        if (emptyRetriedAt == at) return
        emptyRetriedAt = at
        loadedJob?.cancel()
        loadedJob = viewModelScope.launch {
            delay(EMPTY_CANVAS_RETRY_MS)
            if (lastCaptureScroll != at || !_screen.value.active) return@launch
            android.util.Log.d("ScreenSession", "canvas page had no text: capturing again")
            translateViewport()
        }
    }

    /** Asks the page where its images are now (from the latest capture's WebView). */
    private var layoutNow: (suspend () -> ImageLayout?)? = null

    /**
     * Puts the saved translations of what is on screen back onto the screen, without capturing anything.
     *
     * This is the cheap path: the page is asked where its images are (the same little map the capture uses), the
     * records of those images are read, and the ones that match are decoded and published. No screenshot, no
     * detector, no OCR, no engine call.
     *
     * It returns how many bubbles were actually put on the screen.
     */
    private suspend fun restoreVisible(): Int {
        if (settings.value.savedTranslations == SavedTranslations.OFF) return 0
        if (bypassCacheOnce) return 0
        val targetScope = scope
        if (targetScope.isEmpty()) return 0
        val layout = layoutNow?.invoke() ?: return 0
        if (layout.images.isEmpty() || layout.keys.isEmpty()) return 0
        val viewportH = lastViewport.second
        if (viewportH <= 0) return 0
        return restoreInto(layout, viewportH, layout.scale)
    }

    /** Puts every patch whose page image is on screen back onto that image, wherever the layout has moved it. */
    private suspend fun reanchor() {
        if (_screen.value.patches.none { it.anchor != null }) return
        val now = layoutNow?.invoke() ?: return
        var moved = 0
        _screen.update { s ->
            s.copy(patches = s.patches.map { p ->
                val rect = p.anchor?.let { anchoredRect(it, now) } ?: return@map p
                // Under a pixel at the current zoom is rounding in the image rects, not a move.
                val samePlace = abs(rect.leftCss - p.left) * now.scale < 1f && abs(rect.topCss - p.top) * now.scale < 1f
                val sameSize = rect.widthCss <= 0f || abs(rect.widthCss - p.width) * now.scale < 1f
                if (samePlace && sameSize) p
                else {
                    moved++
                    // The size follows the image too: a page that was laid out at another width gets its bubbles
                    // resized with it, not only moved.
                    ScreenPatch(
                        p.image, rect.leftCss, rect.topCss,
                        if (rect.widthCss > 0f) rect.widthCss else p.width,
                        if (rect.heightCss > 0f) rect.heightCss else p.height,
                        p.anchor,
                    )
                }
            })
        }
        if (moved > 0) android.util.Log.d("ScreenSession", "layout moved: $moved patches back on their images")
    }

    /** The status line for a failed viewport: say what went wrong, not just which engine. */
    private fun screenError(f: EngineFailure?): String {
        val name = f?.engine?.uiName(context) ?: return context.getString(UiR.string.error_translation_failed)
        if (f is EngineFailure.PackMissing) return context.getString(UiR.string.error_pack_missing, packNames(f.tags))
        val res = when (f) {
            is EngineFailure.Overloaded -> UiR.string.error_overloaded_scroll
            is EngineFailure.MissingKey -> UiR.string.error_needs_key
            is EngineFailure.QuotaExceeded -> UiR.string.error_quota
            is EngineFailure.Network -> UiR.string.error_network
            is EngineFailure.Unavailable -> if (f.reason.startsWith("Watchdog")) UiR.string.error_too_long else UiR.string.error_failed
            else -> UiR.string.error_failed
        }
        return context.getString(res, name)
    }

    /** ML Kit tags named in the UI's language ("Hindi, Arabic"). */
    fun packNames(tags: List<String>): String = tags.joinToString(", ") { languageName(it) }


    /**
     * Which patches to keep. While a page is held (a whole-page scan), they are kept whole under a memory budget:
     * the ones farthest from the viewport go first. Otherwise the usual few screens are kept and the rest are
     * re-made on return.
     */
    /** How much of the overlay is kept: the screens around the viewport, and the byte budget for them. */
    private data class PatchLimits(val screens: Int, val maxBytes: Long)

    /**
     * The limits the user chose, read from the settings snapshot the view model already holds.
     *
     * `0` means Auto: the number of screens and the memory cap then follow the phone's own memory. The settings
     * snapshot is read without blocking: it is already in memory.
     */
    private fun retentionLimits(): PatchLimits {
        val s = settings.value
        val ramMb = DeviceMemory.totalBytes(context) / (1024L * 1024L)
        val screens = if (s.overlayScreens > 0) s.overlayScreens else RetentionLimits.autoScreens(ramMb)
        val mb = if (s.overlayMemoryMb > 0) s.overlayMemoryMb else RetentionLimits.autoOverlayMb(ramMb)
        return PatchLimits(screens, mb * 1024L * 1024L)
    }

    private fun keepPatches(patches: List<ScreenPatch>, scrollY: Int, viewportH: Int, scale: Float): List<ScreenPatch> {
        if (!holdPage) {
            val limits = retentionLimits()
            val pruned = PatchWindow.prune(patches, scrollY, viewportH, scale, limits.screens, limits.maxBytes)
            if (pruned.droppedByDistance > 0) {
                RetentionDiagnosticsHub.record(
                    RetentionEvent(RetentionReason.DECODED_EVICTED_DISTANCE, pruned.droppedByDistance.toLong()),
                )
            }
            if (pruned.droppedByBytes > 0) {
                RetentionDiagnosticsHub.record(
                    RetentionEvent(RetentionReason.DECODED_EVICTED_BYTES, pruned.droppedByBytes.toLong()),
                )
            }
            return pruned.kept
        }
        val viewportCentre = scrollY + viewportH / 2
        val before = patches.size
        val kept = PageScanPlan.evictFarthest(
            patches,
            PAGE_PATCH_BUDGET_BYTES,
            bytes = { it.image.width.toLong() * it.image.height * 4L },
            distance = { p ->
                val r = p.rectAt(scale)
                abs((r.top + r.height / 2) - viewportCentre)
            },
        )
        // The whole-page scan evicts by a rule of its own, so its evictions are said separately.
        if (kept.size < before) {
            RetentionDiagnosticsHub.record(
                RetentionEvent(RetentionReason.DECODED_EVICTED_WHOLE_PAGE, (before - kept.size).toLong()),
            )
        }
        return kept
    }

    private var bypassCacheOnce = false

    /** Set once at attach when the site or the settings ask for translation on open; consumed by the screen. */
    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart
    fun consumeAutoStart() { _autoStart.value = false }

    private val languages = SessionLanguages()

    /** Languages from the launch intent (`src`/`tgt` extras): this reader session only, never saved to the site or Settings. */
    fun overrideLanguages(src: Lang?, tgt: Lang?, start: Boolean) {
        languages.override(src, tgt)
        _ui.update { s -> s.copy(config = s.config?.let { withOverride(it, s.site) }) }
        if (start) _autoStart.value = true
    }

    private fun withOverride(cfg: TranslateConfig, site: Site?): TranslateConfig = languages.apply(cfg, site, _ui.value.url)

    /**
     * After a page loads: ML Kit's language identification on its visible text (and `<html lang>`). A page in another
     * of the comic source languages than the one configured switches this session to it, unless the site or the
     * launch intent set the source; the bar's "KO → EN" shows it. The screen is translated again if it was on.
     */
    private fun detectPageLanguage(url: String) {
        if (languages.sourceOverridden) return
        viewModelScope.launch {
            val sample = evalInPage("window.__pt ? __pt.sample() : null")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@launch
            val tag = pageTranslator.detect(sample.optString("text"), sample.optString("lang").ifEmpty { null }) ?: return@launch
            val cfg = _ui.value.config ?: return@launch
            val lang = SessionLanguages.sourceFor(tag, cfg.src) ?: return@launch
            if (_ui.value.url != url || _ui.value.site?.sourceLang != null) return@launch
            languages.pageIs(url, lang)
            if (lang == cfg.src) return@launch
            android.util.Log.i("ReaderViewModel", "page language ${lang.name}: source ${cfg.src.name} -> ${lang.name} for this session")
            val next = cfg.copy(src = lang)
            _ui.update { it.copy(config = next) }
            prewarm(next)
            if (_screen.value.active) { clearScreenPatches(); translateViewport() }
        }
    }

    /** Re-translate what is on screen: fresh OCR and a fresh engine call (the patch cache is skipped), same engine or a new one. */
    fun retranslateScreen(capture: suspend () -> ViewportCapture?) {
        cancelScan(restore = false)
        if (!_screen.value.active) { startScreen(capture); bypassCacheOnce = true; translateViewport(); return }
        screenJob?.cancel()
        android.util.Log.d("ScreenSession", "clear reason=retranslate")
        _screen.update { it.copy(patches = emptyList(), regions = 0, error = null, failure = null) }
        bypassCacheOnce = true
        // A fresh translation must replace the saved one, not be replaced by it. The clear is awaited: the
        // replacement work starts after the old records are gone, so the clear cannot delete what came after it.
        val gen = pageGen
        val sc = scope
        storeScope.launch {
            if (gen == pageGen && sc.isNotEmpty()) regions.clearScope(sc)
        }
        translateViewport()
    }

    // ---- the saved translations: the scope, the fingerprint, saving and restoring -----------------------

    /**
     * The scope the saved translations belong to, and the fingerprint of the settings they were made with.
     *
     * The scope is the chapter: the page's URL without its fragment. The fingerprint says what the translation was
     * made with, so a translation made under other settings is never shown again.
     */
    private suspend fun prepareStore(url: String, cfg: TranslateConfig) {
        val s = settings.value
        val newScope = PageScanPlan.pageKey(url)
        if (newScope != scope) {
            scope = newScope
            when (s.savedTranslations) {
                SavedTranslations.OFF -> regions.clear()
                SavedTranslations.CHAPTER -> regions.clearScopesExcept(newScope)
                SavedTranslations.WEEK -> Unit
            }
        }
        refreshFingerprint(cfg)
    }

    /**
     * Brings the fingerprint up to date with the settings as they are now.
     *
     * The fingerprint was once computed when the page opened. A change made afterwards — a different model, a
     * different dialogue level, a character sheet that has been filled in — would then be filed under the old
     * settings, and a translation made under one policy would be shown under another. It is rebuilt at the start
     * of every capture, so it always describes the work that is about to happen.
     */
    private suspend fun refreshFingerprint(cfg: TranslateConfig) {
        val level = settings.value.dialogueContext
        val contextTag = if (level == DialogueContext.OFF) "" else "ctx${cfg.contextLimit()}"
        val castTag = if (castText.isBlank()) "" else "cast" + RegionStore.sha256Hex(castText.toByteArray()).take(8)
        fingerprint = PatchRetention.fingerprint(
            cfg, modelTag(cfg), SeriesResolver.digest(cfg), TranslationPipeline.PIPELINE_VERSION,
            contextTag, castTag,
        )
    }

    /**
     * What the engine's model is: the model the user chose, and for the user's own server the address too.
     *
     * Two servers on one host are two servers, so the whole address is in the tag, not just the host.
     */
    private suspend fun modelTag(cfg: TranslateConfig): String {
        val s = settings.value
        val parts = ArrayList<String>(2)
        if (cfg.engineId.hasModel) {
            val model = s.modelFor(cfg.engineId).trim()
            if (model.isNotEmpty()) parts += model
        }
        when (cfg.engineId) {
            EngineId.CUSTOM -> {
                val address = s.customBaseUrl.trim()
                if (address.isNotEmpty()) {
                    parts += RegionStore.sha256Hex(address.toByteArray()).take(12)
                }
            }
            EngineId.LOCAL_USER -> {
                val model = s.activeUserModelId.trim()
                if (model.isNotEmpty()) parts += model
            }
            else -> Unit
        }
        return parts.joinToString("|")
    }

    /** The saved translations' budget, in bytes. */
    private fun storeBudget(): Long = settings.value.savedTranslationsMb * 1024L * 1024L

    /** How long a saved translation lives, in milliseconds; null when it lives until the chapter ends. */
    private fun storeMaxAge(): Long? = when (settings.value.savedTranslations) {
        SavedTranslations.OFF -> null
        SavedTranslations.CHAPTER -> null
        SavedTranslations.WEEK -> 7L * 24 * 60 * 60 * 1000
    }

    /**
     * Saves one accepted translation, off the main thread.
     *
     * The patch's bytes are encoded here and never on the main thread: encoding a lossless WEBP costs every patch
     * more than drawing it does.
     *
     * [region] carries the geometry, the fingerprint and the scope of the capture it belongs to. Nothing is read
     * from the view model's current state inside the background work: a save that started under one chapter must
     * not land in another, and a save that started under one set of settings must not be filed under another.
     */
    private fun savePatch(patch: Patch, region: AcceptedRegion) {
        if (settings.value.savedTranslations == SavedTranslations.OFF) return
        if (!PatchRetention.retainable(region.imageKey)) return
        // The scope and the budget of the capture this belongs to: captured now, used later, never re-read.
        val targetScope = scope
        if (targetScope.isEmpty()) return
        val budget = storeBudget()
        pendingSaves.incrementAndGet()
        RetentionDiagnosticsHub.record(RetentionEvent(RetentionReason.SAVE_QUEUED))
        storeScope.launch {
            try {
                val webp = patch.webp
                val saved = RetentionCoordinator.toSaved(region, webp) ?: return@launch
                // The epoch is taken here, and the store checks it **inside** its own lock: a clear that happened
                // while the bytes were being encoded must not be undone by this write.
                val epoch = regions.epochOf(targetScope)
                regions.upsert(targetScope, saved.first, saved.second, budget, epoch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: never the text, never the key.
                RetentionDiagnosticsHub.record(RetentionEvent(RetentionReason.SAVE_FAILED))
                android.util.Log.w("ScreenSession", "save failed: ${e::class.simpleName}")
            } finally {
                pendingSaves.decrementAndGet()
            }
        }
    }

    /**
     * Puts the saved translations of the images now on screen back onto the screen.
     *
     * It runs before the region of interest is built, so the restored bubbles are in the "already done" list and
     * the pipeline never detects, reads or translates them again.
     */
    private suspend fun promoteSaved(c: ViewportCapture): Int {
        if (settings.value.savedTranslations == SavedTranslations.OFF) return 0
        if (bypassCacheOnce) return 0
        val layout = ImageLayout(c.images, c.imageKeys, c.scrollX, c.scrollY, c.scale)
        return restoreInto(layout, c.bitmap.height, c.scale)
    }

    /**
     * Puts the saved translations of [layout] back onto the screen.
     *
     * One implementation for both callers: the capture's own layout, and the layout the page reports when the
     * reader scrolls somewhere. The two must not drift apart.
     */
    private suspend fun restoreInto(layout: ImageLayout, viewportH: Int, scale: Float): Int {
        val gen = pageGen
        val fp = fingerprint
        val targetScope = scope
        if (targetScope.isEmpty()) return 0
        if (viewportH <= 0) return 0
        val limit = storeMaxAge()
        val margin = PatchRetention.restoreMargin(retentionLimits().screens, viewportH)
        val existing = _screen.value.patches
        val restored = ArrayList<ScreenPatch>()
        var quarantined = 0
        withContext(Dispatchers.IO) {
            // Every image of the page, with the occurrence it is: the same picture twice is two images.
            for (index in layout.keys.indices) {
                val key = layout.keys[index]
                if (!PatchRetention.retainable(key)) continue
                // The records of this key: they may belong to several images with the same key.
                val saved = regions.load(targetScope, key, limit)
                if (saved.isEmpty()) continue
                // The records the broken version left behind are counted, not drawn.
                quarantined += saved.count { PatchRetention.legacyReason(it.schema) != null }
                restored += RetentionCoordinator.restore(
                    saved, key, occurrenceOf(index, layout.keys), layout, viewportH, margin, fp,
                    existing + restored,
                    decode = { bytes -> decodePatch(Patch(0f, 0f, 0f, 0f, bytes)) },
                )
            }
        }
        if (restored.isEmpty()) {
            RetentionDiagnosticsHub.record(
                RetentionEvent(RetentionReason.RESTORED_CANDIDATE, 0, quarantined.toLong()),
            )
            return 0
        }
        // The page may have changed while the files were read.
        if (gen != pageGen) return 0
        // The patches that are actually put on the screen: the ones that were not a duplicate of what is there.
        val published = ArrayList<ScreenPatch>()
        _screen.update { s ->
            for (p in restored) {
                if (s.patches.any { p.duplicates(it, DUPLICATE_OVERLAP) }) continue
                if (published.any { p.duplicates(it, DUPLICATE_OVERLAP) }) continue
                published += p
            }
            val merged = s.patches + published
            s.copy(patches = keepPatches(merged, layout.scrollY, viewportH, scale))
        }
        RetentionDiagnosticsHub.record(
            RetentionEvent(RetentionReason.RESTORED_CANDIDATE, restored.size.toLong(), quarantined.toLong()),
        )
        RetentionDiagnosticsHub.record(
            RetentionEvent(RetentionReason.PUBLISHED_RESTORE, published.size.toLong()),
        )
        return published.size
    }

    /**
     * The user's character sheet for the website being read.
     *
     * It is saved for the page's host and used from the next translation on; the text is never logged.
     */
    fun saveCastSheet(text: String) {
        val host = Site.hostOf(_ui.value.url)
        if (host.isEmpty()) return
        viewModelScope.launch {
            settingsRepo.setCastSheet(host, text)
            castText = settingsRepo.castSheetFor(host)
        }
    }

    /** The character sheet of the website being read, for the editor. */
    fun castSheet(): String = castText

    /**
     * Drops the overlays that are not on screen, keeping the ones the user is looking at.
     *
     * This is what the app does when the memory is tight: the decoded bitmaps go first, the saved translations on
     * disk stay.
     */
    fun trimOverlays() {
        val s = _screen.value
        if (!s.active) return
        RetentionDiagnosticsHub.record(RetentionEvent(RetentionReason.DECODED_EVICTED_MEMORY_TRIM))
        val scroll = lastCaptureScroll?.second ?: return
        val scale = _zoom.value
        val height = viewportHeight.takeIf { it > 0 } ?: return
        _screen.update { cur ->
            val visible = cur.patches.filter { p ->
                val r = p.rectAt(scale)
                r.bottom >= scroll && r.top <= scroll + height
            }
            cur.copy(patches = visible)
        }
    }

    /**
     * Layout changed underneath (new page): forget page-space patches; the next settle re-translates.
     *
     * [reason] says why, for the log: which of the things that legitimately clear the patches happened.
     */
    fun clearScreenPatches(reason: String = "page") {
        android.util.Log.d("ScreenSession", "clear reason=$reason")
        // A new page: a save or a restore that started before this must not touch the new page.
        if (reason != "glossary" && reason != "retranslate") pageGen++
        cancelScan(restore = false)
        holdPage = false
        screenJob?.cancel()
        lastCaptureScroll = null
        // The memory belongs to the page: with the patches gone, the lines they came from are gone too.
        memory.clear()
        _screen.update { if (it.active) it.copy(patches = emptyList(), working = false, regions = 0, error = null, failure = null) else it }
    }

    lateinit var client: MangaWebViewClient
        private set

    private var siteAdBlockOverride: Boolean? = null

    init {
        viewModelScope.launch { pageText.setTarget(settingsRepo.pageTranslateTarget()) }
        blockLists.ensureLoaded()
        viewModelScope.launch { blockLists.list.collect { list -> if (::client.isInitialized) client.blockList = list } }
    }

    /**
     * The library site that opened this reader. It owns the session while the page stays on its host, so chapter
     * links keep its languages even when another saved site shares the host; off the host, [SiteRepository.forUrl] decides.
     */
    private var pinnedSiteId: Long? = null

    private suspend fun siteFor(url: String): Site? {
        val pinned = pinnedSiteId?.let { sites.byId(it) }
        if (pinned != null) {
            val host = Site.hostOf(url)
            if (host == pinned.host || host.endsWith("." + pinned.host)) return pinned
        }
        return sites.forUrl(url)
    }

    /**
     * The page this reader already shows, for a composition that comes back (from Settings): the WebView lives here,
     * not in the composition, so the page, its scroll position and the patches over it survive the trip instead of
     * reloading. It sits on a [MutableContextWrapper] that is pointed at the activity hosting it now, so it never
     * keeps a destroyed activity alive. Null before [attach].
     */
    fun hostedWebView(host: Context): WebView? {
        val wv = web ?: return null
        (wv.context as? MutableContextWrapper)?.let { if (it.baseContext !== host) it.baseContext = host }
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.onResume()
        viewModelScope.launch { reloadConfig() }
        return wv
    }

    /**
     * Settings may have changed the engine or the languages while the reader was off screen, and there was no page
     * load to pick them up: re-resolve, and re-translate what is on screen when the result would differ.
     */
    private suspend fun reloadConfig() {
        val url = _ui.value.url
        val (s, site) = withContext(Dispatchers.IO) { settingsRepo.current() to siteFor(url) }
        val cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyFor(site, url)), site)
        val old = _ui.value.config
        _ui.update { it.copy(site = site, config = cfg) }
        if (old == null || old == cfg) return
        if (old.engineId != cfg.engineId) prewarm(cfg)
        if (_screen.value.active) { clearScreenPatches("engineChange"); translateViewport() }
    }

    /**
     * [external]: [requestedUrl] came from another app's intent. Such a page is never translated on open, whatever
     * translate-on-open says, or any installed app could spend the user's provider quota on a page of its choosing.
     */
    fun attach(webView: WebView, requestedUrl: String, siteId: Long? = null, external: Boolean = false) {
        pinnedSiteId = siteId
        web?.takeIf { it !== webView }?.destroy()
        web = webView
        client = MangaWebViewClient(context, warmup.bridgeScript, this).also { it.blockList = blockLists.list.value }
        webView.webViewClient = client
        // The bridge goes in before any page script runs, so a page cannot define `__mt`/`__pt` first and have the
        // app talk to its fake. The onPageStarted/onPageFinished injections stay as the fallback (a no-op once there).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(webView, warmup.bridgeScript, setOf("*")) }
        }

        // Kick off web navigation immediately — never block behind DataStore/Room reads
        // The URL can come from another app's intent: https pages only (http upgraded), never javascript:, file: or content:.
        val initialUrl = WebUrl.https(requestedUrl).orEmpty()
        if (initialUrl.isNotEmpty()) {
            _ui.update { it.copy(url = initialUrl) }
            webView.loadUrl(initialUrl)
        }

        viewModelScope.launch {
            // Main.immediate runs this inline until the first suspension: keep the DataStore/Room reads off the
            // composition that is building the reader.
            val (s, site) = withContext(Dispatchers.IO) { settingsRepo.current() to siteFor(initialUrl) }
            val adBlock = site?.adBlockEnabled ?: s.adBlockDefault
            client.adBlockEnabled = adBlock
            var cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyFor(site, initialUrl)), site)
            pendingEngine?.let { cfg = cfg.copy(engineId = it); pendingEngine = null }
            _ui.update { it.copy(site = site, adBlockEnabled = adBlock, config = cfg) }
            prewarm(cfg)
            // Translate-on-open: the screen starts it (it owns the capture), once, when it sees this flag.
            if (!external && (s.translateOnOpen || site?.autoTranslate == true)) _autoStart.value = true
        }
    }

    fun load(url: String) {
        val target = normalise(url)
        _ui.update { it.copy(url = target) }
        web?.loadUrl(target)
    }

    /** A typed address as an https URL (http upgraded); anything that is not one becomes a search. */
    private fun normalise(input: String): String =
        WebUrl.https(input) ?: ("https://duckduckgo.com/?q=" + android.net.Uri.encode(input.trim()))

    fun goBack(): Boolean {
        val wv = web ?: return false
        if (wv.canGoBack()) { wv.goBack(); return true }
        return false
    }

    fun reload() { web?.reload() }

    fun toggleAdBlock() {
        val next = !_ui.value.adBlockEnabled
        siteAdBlockOverride = next
        client.adBlockEnabled = next
        _ui.update { it.copy(adBlockEnabled = next) }
        viewModelScope.launch { _ui.value.site?.let { sites.update(it.copy(adBlockEnabled = next)) } }
        reload()
    }

    fun dismissStrip() = _ui.update { it.copy(stripDismissed = true) }


    /** Error-row action: keep reading with Google Translate (ML Kit, on-device, no key). Explicit user choice, never automatic. */
    /**
     * The bar's "Download" on [EngineFailure.PackMissing]: fetch the language packs the user just agreed to, then
     * translate the screen again. A failed download says so in the bar; nothing switches engines.
     */
    fun downloadPacksAndRetry(tags: List<String>, capture: suspend () -> ViewportCapture?) {
        viewModelScope.launch {
            _screen.update { it.copy(working = true, failure = null, error = context.getString(UiR.string.pack_downloading)) }
            if (packs.downloadNow(tags)) startScreen(capture)
            else _screen.update {
                it.copy(working = false, error = context.getString(UiR.string.pack_failed),
                    failure = EngineFailure.Unavailable(EngineId.GOOGLE, context.getString(UiR.string.pack_failed)))
            }
        }
    }

    fun switchToOnDevice(capture: suspend () -> ViewportCapture?) {
        val cfg = _ui.value.config?.copy(engineId = EngineId.GOOGLE) ?: return
        _ui.update { it.copy(config = cfg) }
        startScreen(capture)
    }

    /** An engine chosen before [attach] built the config (the launch intent's `engine` extra) waits here. */
    private var pendingEngine: EngineId? = null

    fun selectEngine(id: EngineId) {
        viewModelScope.launch { settingsRepo.setEngine(id) }
        val cfg = _ui.value.config?.copy(engineId = id)
        if (cfg == null) { pendingEngine = id; return }
        _ui.update { it.copy(config = cfg) }
        prewarm(cfg)
    }

    /**
     * Load the detector, the recognizers and the engine (TLS session, or the on-device model's load and first
     * generation) while the page is still loading, so the first Start is not paying for them on its own clock.
     */
    private fun prewarm(cfg: TranslateConfig) {
        viewModelScope.launch(Dispatchers.Default) { runCatching { pipeline.warmUp(cfg) } }
    }

    fun engineLabel(userModelLabel: String? = null): String {
        val cfg = _ui.value.config ?: return ""
        val name = if (cfg.engineId == EngineId.LOCAL_USER) userModelLabel?.takeIf { it.isNotBlank() } ?: cfg.engineId.uiName(context)
            else cfg.engineId.uiName(context)
        return name + " · " + cfg.src.code.uppercase() + " → " + cfg.tgt.code.uppercase()
    }


    suspend fun addCurrentSite(): Site? {
        val url = _ui.value.url
        if (url.isBlank()) return null
        val host = Site.hostOf(url)
        val existing = sites.forUrl(url)
        if (existing != null) return existing
        val site = Site(name = host.substringBefore('.').replaceFirstChar { it.uppercase() }, url = "https://$host", adBlockEnabled = settings.value.adBlockDefault)
        val id = sites.add(site)
        return site.copy(id = id).also { s -> _ui.update { it.copy(site = s) } }
    }

    // ---- MangaWebViewClient.Listener -------------------------------------------------------

    override fun onPageStarted(url: String) {
        clearScreenPatches("page")
        // The saved translations belong to this chapter, and were made with these settings.
        storeScope.launch {
            val cfg = _ui.value.config
            if (cfg != null) prepareStore(url, cfg)
        }
        pageText.onPageStarted()
        _ui.update { it.copy(url = url, stripDismissed = false, canGoBack = web?.canGoBack() == true, progress = minOf(it.progress, 10)) }
        viewModelScope.launch {
            val site = siteFor(url)
            val s = settingsRepo.current()
            // The character sheet belongs to the website being read.
            castText = settingsRepo.castSheetFor(Site.hostOf(url))
            val adBlock = siteAdBlockOverride ?: site?.adBlockEnabled ?: s.adBlockDefault
            client.adBlockEnabled = adBlock
            // Keep the session's engine: a page load must not undo a pick made in the reader.
            val engine = _ui.value.config?.engineId
            var cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyFor(site, url)), site)
            if (engine != null) cfg = cfg.copy(engineId = engine)
            _ui.update { it.copy(site = site, adBlockEnabled = adBlock, config = cfg) }
        }
    }

    override fun onPageFinished(url: String, title: String?) {
        _ui.update { it.copy(title = title.orEmpty(), canGoBack = web?.canGoBack() == true, progress = 100) }
        viewModelScope.launch { history.record(url, title.orEmpty()) }
        pageText.onPageFinished()
        detectPageLanguage(url)
        if (_screen.value.active) onScroll(_scroll.value.first, _scroll.value.second, _zoom.value)
    }

    override fun onBlockedCountChanged(count: Int) = _ui.update { it.copy(blockedCount = count) }

    /** From the WebChromeClient: drives the load bar under the URL field. */
    fun onProgress(percent: Int) = _ui.update { it.copy(progress = percent.coerceIn(0, 100)) }

    override fun onProgressUrl(url: String) {
        // A single-page-app chapter change with no page load: the patches of the old chapter are void.
        if (holdPage && PageScanPlan.pageKey(url) != scanPageKey) clearScreenPatches("pageTurn")
        _ui.update { it.copy(url = url, canGoBack = web?.canGoBack() == true) }
    }

    override fun onCleared() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        // The saved translations' work must not outlive the reader.
        storeScope.cancel()
    }

    suspend fun blockListAge(): Long = settingsRepo.settings.first().blockListUpdatedAt

    private companion object {
        /** Quiet time after the last scroll event before the viewport counts as settled. */
        const val SETTLE_MS = 450L

        /** How long the end of a scan waits for the saves it started, in milliseconds. */
        const val SAVE_BARRIER_TIMEOUT_MS = 5_000L
        /** Zoom steps smaller than this are the same zoom (WebView reports the scale as a float). */
        const val ZOOM_EPSILON = 0.001f
        /** Fraction of the viewport the page must have moved before a settled viewport is captured again. */
        const val MIN_MOVE = 0.2f
        const val KEEP_SCREENS = 3
        /** Pixels a deferred block must clear the viewport edge by before it counts as whole (the pipeline's own cut margin plus slack). */
        const val EDGE_MARGIN = 8
        /**
         * How long a capture taken over still-downloading pages waits for them to finish, and how often it asks.
         * A chapter that requests all its pages at once can take over a minute per page on mobile data (rawkuma:
         * 70-78 s); the wait ends as soon as the reader scrolls or stops.
         */
        const val LOAD_FOLLOW_UP_MS = 180_000L
        const val LOAD_POLL_MS = 1_000L
        const val EMPTY_CANVAS_RETRY_MS = 2_000L
        /** After a gesture, time for a page-turn animation to finish before the viewport is compared. */
        const val GESTURE_SETTLE_MS = 800L
        /** After a rotation, time for the page to lay itself out again before it is captured. */
        const val RESIZE_SETTLE_MS = 1_200L
        // ---- whole-page scan ---------------------------------------------------------------------
        /** Time the page is given to settle after a scroll before it is captured. */
        const val SCAN_SETTLE_MS = 700L
        /** Time at the bottom of the page, to see whether a lazy page grew. */
        const val SCAN_BOTTOM_WAIT_MS = 1_500L
        /** How long a scan waits for images that were still downloading. */
        const val SCAN_LOAD_WAIT_MS = 15_000L
        /** How far the page must fail to move before it is taken for a page that does not scroll as a window. */
        const val SCAN_SCROLL_EPSILON = 2
        /** Memory the held page's patches may take; the farthest from the viewport go first. */
        const val PAGE_PATCH_BUDGET_BYTES = 96L * 1024 * 1024
    }
}
