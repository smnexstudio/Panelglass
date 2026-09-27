package com.smnexstudio.panelglass.feature.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.SiteRepository
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.mt.PageTranslator
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ImageSource
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import androidx.compose.ui.graphics.ImageBitmap
import com.smnexstudio.panelglass.core.pipeline.RegionOfInterest
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.Site
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import com.smnexstudio.panelglass.feature.browser.block.BlockListRepository
import com.smnexstudio.panelglass.feature.browser.web.MangaWebViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
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

/** The on-screen page images' rects in the viewport (view pixels) with their keys, at a given scroll offset. */
class ImageLayout(val images: List<IntRect>, val keys: List<String>, val scrollX: Int, val scrollY: Int)

/**
 * Where a patch stands on the page image it was made from: that image's key and the patch's offset from the
 * image's top-left corner (view pixels at the capture's zoom).
 */
class Anchor(val key: String, val dx: Int, val dy: Int)

/** The anchor for a patch at [left],[top] (view pixels) whose centre lies on one of [images]; null on none. */
internal fun anchorFor(left: Int, top: Int, width: Int, height: Int, images: List<IntRect>, keys: List<String>): Anchor? {
    val cx = left + width / 2; val cy = top + height / 2
    val i = images.indices.firstOrNull { images[it].contains(cx, cy) && keys.getOrNull(it).orEmpty().isNotEmpty() } ?: return null
    return Anchor(keys[i], left - images[i].left, top - images[i].top)
}

/**
 * Page position of a patch anchored to an image, from where that image is [now]; null when it is not on screen.
 * Page coordinates are what the overlay draws in (scroll + view), so a patch stays on its art whatever moved it:
 * a reader re-centring its page in its own layer (bilibili, 11 px), or scroll anchoring when a lazy image above
 * the viewport finished loading (rawkuma: the art stayed put on screen while the scroll offset jumped 94 px).
 */
internal fun anchoredPosition(anchor: Anchor, now: ImageLayout): Pair<Int, Int>? {
    val i = now.keys.indexOf(anchor.key).takeIf { it >= 0 } ?: return null
    val img = now.images[i]
    return Pair(now.scrollX + img.left + anchor.dx, now.scrollY + img.top + anchor.dy)
}

/** A rendered, decoded patch in page (content) pixels at the capture's zoom, so it stays glued to the art while scrolling. */
class ScreenPatch(val image: ImageBitmap, val left: Int, val top: Int, val width: Int, val height: Int, val anchor: Anchor? = null) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    fun at(left: Int, top: Int) = ScreenPatch(image, left, top, width, height, anchor)
    fun overlapFraction(o: ScreenPatch): Float {
        val w = (minOf(right, o.right) - maxOf(left, o.left)).coerceAtLeast(0)
        val h = (minOf(bottom, o.bottom) - maxOf(top, o.top)).coerceAtLeast(0)
        val area = width * height
        return if (area <= 0) 0f else (w * h).toFloat() / area
    }

    /** The same bubble seen from two viewports: mostly overlapping *and* about the same size. A big crop (a sound effect's) that merely covers a small neighbour is not its duplicate. */
    fun duplicates(o: ScreenPatch, minOverlap: Float): Boolean {
        if (overlapFraction(o) < minOverlap) return false
        val a = (width * height).toFloat(); val b = (o.width * o.height).toFloat()
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
    val scale: Float = 1f,
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
    pageTranslator: PageTranslator,
    val warmup: ReaderWarmup,
) : ViewModel(), MangaWebViewClient.Listener {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui

    private var web: WebView? = null

    /** "Translate page" in the menu: the page's own text, in place (ML Kit). */
    val pageText = PageTextTranslation(
        viewModelScope, pageTranslator,
        eval = { js -> evalInPage(js) },
        onTextChanged = { if (_screen.value.active) { clearScreenPatches(); translateViewport() } },
        saveTarget = { settingsRepo.setPageTranslateTarget(it) },
    )

    /** Runs [js] in the page; its JSON result, or null without a page. */
    private suspend fun evalInPage(js: String): String? = withContext(Dispatchers.Main) {
        val wv = web ?: return@withContext null
        suspendCancellableCoroutine { cont ->
            try { wv.evaluateJavascript(js) { v -> if (cont.isActive) cont.resume(v) } }
            catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }

    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _screen = MutableStateFlow(ScreenSession())
    val screen: StateFlow<ScreenSession> = _screen
    /** Current WebView scroll offset, fed from the view; the overlay subtracts it to place page-space patches. */
    private val _scroll = MutableStateFlow(Pair(0, 0))
    val scroll: StateFlow<Pair<Int, Int>> = _scroll

    private var capture: (suspend () -> ViewportCapture?)? = null
    private var screenJob: Job? = null
    private var settleJob: Job? = null
    private var lastCaptureScroll: Pair<Int, Int>? = null
    private var lastViewport = Pair(0, 0)
    /** Blocks the last capture could not read because its edge cut them, in page pixels: they are what a small scroll reveals. */
    private var deferred: List<IntRect> = emptyList()

    /** Start: translate what is on screen now, then keep doing so as the reader scrolls. */
    fun startScreen(capture: suspend () -> ViewportCapture?) {
        this.capture = capture
        _screen.value = ScreenSession(active = true)
        lastCaptureScroll = null
        translateViewport()
    }

    fun stopScreen() {
        loadedJob?.cancel(); loadedJob = null
        gestureJob?.cancel(); gestureJob = null
        settleJob?.cancel(); settleJob = null
        screenJob?.cancel(); screenJob = null
        capture = null
        _screen.value = ScreenSession()
    }

    /** From the WebView: every scroll step. Patches move with the page; a settled new viewport is translated. */
    fun onScroll(x: Int, y: Int, scale: Float) {
        _scroll.value = Pair(x, y)
        val s = _screen.value
        if (!s.active) return
        if (s.patches.isNotEmpty() && abs(scale - s.scale) > 0.01f) {
            // Zoom changed: page-space coordinates are no longer valid.
            screenJob?.cancel()
            _screen.update { it.copy(patches = emptyList(), working = false) }
            lastCaptureScroll = null
        }
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
            val revealed = deferred.any { it.top >= y + EDGE_MARGIN && it.bottom <= y + vh - EDGE_MARGIN && it.left >= x && it.right <= x + vw }
            if (moved || revealed) translateViewport()
        }
    }

    private fun translateViewport() {
        val cfg = _ui.value.config ?: return
        val cap = capture ?: return
        screenJob?.cancel()
        screenJob = viewModelScope.launch {
            val c = cap()
            if (c == null) { _screen.update { it.copy(working = false, error = context.getString(UiR.string.reader_capture_failed)) }; return@launch }
            lastCaptureScroll = Pair(c.scrollX, c.scrollY)
            lastViewport = Pair(c.bitmap.width, c.bitmap.height)
            lastThumb = Thumb.of(c.bitmap); thumbNow = c.thumbNow
            _screen.update { it.copy(working = true, error = null, failure = null, scale = c.scale) }
            val w = c.bitmap.width; val h = c.bitmap.height
            // Bubbles an earlier capture already patched are not read or translated again (the slow part of a scroll).
            val done = _screen.value.patches.map { IntRect(it.left - c.scrollX, it.top - c.scrollY, it.right - c.scrollX, it.bottom - c.scrollY) }
            val roi = RegionOfInterest(include = c.images, exclude = c.overlays, done = done)
            val bypass = bypassCacheOnce; bypassCacheOnce = false
            try {
                val result = pipeline.translate(ImageSource.Native(c.bitmap), cfg, imageId = "screen", roi = roi, bypassCache = bypass) { patch ->
                    // onPatch runs on the pipeline's worker: decode here, never in composition.
                    val img = decodePatch(patch) ?: return@translate
                    val vx = (patch.xPct / 100f * w).toInt(); val vy = (patch.yPct / 100f * h).toInt()
                    val pw = (patch.wPct / 100f * w).toInt().coerceAtLeast(1); val ph = (patch.hPct / 100f * h).toInt().coerceAtLeast(1)
                    val sp = ScreenPatch(
                        img, left = c.scrollX + vx, top = c.scrollY + vy, width = pw, height = ph,
                        anchor = anchorFor(vx, vy, pw, ph, c.images, c.imageKeys),
                    )
                    _screen.update { s ->
                        // Overlapping viewports see the same bubble twice: a patch mostly covered by an existing one
                        // is a duplicate; conversely a fragment (a bubble cut by the last viewport edge) mostly
                        // covered by the new, fuller patch gives way to it.
                        val dup = s.patches.any { sp.duplicates(it, DUPLICATE_OVERLAP) }
                        android.util.Log.d("ScreenSession", "patch ${sp.left},${sp.top} ${sp.width}x${sp.height} scroll=${c.scrollX},${c.scrollY} dup=$dup")
                        if (dup) s
                        else s.copy(patches = prune(s.patches.filter { !it.duplicates(sp, DUPLICATE_OVERLAP) } + sp, c.scrollY, h))
                    }
                }
                layoutNow = c.layoutNow ?: layoutNow
                reanchor()
                result.fold(
                    onSuccess = { page ->
                        deferred = page.deferred.map { IntRect(c.scrollX + it.left, c.scrollY + it.top, c.scrollX + it.right, c.scrollY + it.bottom) }
                        _screen.update { it.copy(working = false, regions = it.regions + page.regionCount) }
                        if (c.pending > 0) recaptureWhenLoaded(c)
                        else if (page.regionCount == 0 && c.imageKeys.any { it.startsWith("canvas#") }) retryEmptyCanvas(c)
                    },
                    onFailure = { e ->
                        val f = (e as? EngineException)?.failure
                        _screen.update { it.copy(working = false, error = screenError(f), failure = f) }
                    },
                )
            } finally { c.bitmap.recycle() }
        }
    }

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
            val patched = _screen.value.patches.map { IntRect(it.left - at.first, it.top - at.second, it.right - at.first, it.bottom - at.second) }
            if (contentChanged(before, now, patched) != true) return@launch
            android.util.Log.d("ScreenSession", "page changed without a scroll: translating it again")
            _screen.update { s -> s.copy(patches = s.patches.filterNot { IntRect(it.left, it.top, it.right, it.bottom).intersects(view) }) }
            emptyRetriedAt = null
            translateViewport()
        }
    }

    /** From the WebView: its width changed (rotation). The page is laid out anew, so the patches are dropped and the new viewport translated once it settles. */
    fun onViewportResized() {
        if (!_screen.value.active) return
        screenJob?.cancel(); loadedJob?.cancel(); gestureJob?.cancel()
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

    /** Puts every patch whose page image is on screen back onto that image, wherever the layout has moved it. */
    private suspend fun reanchor() {
        if (_screen.value.patches.none { it.anchor != null }) return
        val now = layoutNow?.invoke() ?: return
        var moved = 0
        _screen.update { s ->
            s.copy(patches = s.patches.map { p ->
                val pos = p.anchor?.let { anchoredPosition(it, now) } ?: return@map p
                if (pos.first == p.left && pos.second == p.top) p else { moved++; p.at(pos.first, pos.second) }
            })
        }
        if (moved > 0) android.util.Log.d("ScreenSession", "layout moved: $moved patches back on their images")
    }

    /** The status line for a failed viewport: say what went wrong, not just which engine. */
    private fun screenError(f: EngineFailure?): String {
        val name = f?.engine?.uiName(context) ?: return context.getString(UiR.string.error_translation_failed)
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

    /** Keep memory bounded: patches further than a few screens from the viewport are dropped (they are re-made on return). */
    private fun prune(patches: List<ScreenPatch>, scrollY: Int, viewportH: Int): List<ScreenPatch> {
        val keep = viewportH * KEEP_SCREENS
        return patches.filter { it.bottom >= scrollY - keep && it.top <= scrollY + viewportH + keep }
    }

    private var bypassCacheOnce = false

    /** Set once at attach when the site or the settings ask for translation on open; consumed by the screen. */
    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart
    fun consumeAutoStart() { _autoStart.value = false }

    /** Languages from the launch intent (`src`/`tgt` extras): this reader session only, never saved to the site or Settings. */
    private var langOverride: Pair<Lang?, Lang?> = null to null

    fun overrideLanguages(src: Lang?, tgt: Lang?, start: Boolean) {
        langOverride = src to tgt
        _ui.update { s -> s.copy(config = s.config?.let(::withOverride)) }
        if (start) _autoStart.value = true
    }

    private fun withOverride(cfg: TranslateConfig): TranslateConfig =
        cfg.copy(src = langOverride.first ?: cfg.src, tgt = langOverride.second ?: cfg.tgt)

    /** Re-translate what is on screen: fresh OCR and a fresh engine call (the patch cache is skipped), same engine or a new one. */
    fun retranslateScreen(capture: suspend () -> ViewportCapture?) {
        if (!_screen.value.active) { startScreen(capture); bypassCacheOnce = true; translateViewport(); return }
        screenJob?.cancel()
        _screen.update { it.copy(patches = emptyList(), regions = 0, error = null, failure = null) }
        bypassCacheOnce = true
        translateViewport()
    }

    /** Layout changed underneath (new page): forget page-space patches; the next settle re-translates. */
    fun clearScreenPatches() {
        screenJob?.cancel()
        lastCaptureScroll = null
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
        val cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyOf(url)))
        val old = _ui.value.config
        _ui.update { it.copy(site = site, config = cfg) }
        if (old == null || old == cfg) return
        if (old.engineId != cfg.engineId) prewarm(cfg)
        if (_screen.value.active) { clearScreenPatches(); translateViewport() }
    }

    fun attach(webView: WebView, initialUrl: String, siteId: Long? = null) {
        pinnedSiteId = siteId
        web?.takeIf { it !== webView }?.destroy()
        web = webView
        client = MangaWebViewClient(context, warmup.bridgeScript, this).also { it.blockList = blockLists.list.value }
        webView.webViewClient = client

        // Kick off web navigation immediately — never block behind DataStore/Room reads
        // The URL can come from another app's intent: only web pages, never javascript:, file: or content:.
        if (isWebUrl(initialUrl)) {
            _ui.update { it.copy(url = initialUrl) }
            webView.loadUrl(initialUrl)
        }

        viewModelScope.launch {
            // Main.immediate runs this inline until the first suspension: keep the DataStore/Room reads off the
            // composition that is building the reader.
            val (s, site) = withContext(Dispatchers.IO) { settingsRepo.current() to siteFor(initialUrl) }
            val adBlock = site?.adBlockEnabled ?: s.adBlockDefault
            client.adBlockEnabled = adBlock
            var cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyOf(initialUrl)))
            pendingEngine?.let { cfg = cfg.copy(engineId = it); pendingEngine = null }
            _ui.update { it.copy(site = site, adBlockEnabled = adBlock, config = cfg) }
            prewarm(cfg)
            // Translate-on-open: the screen starts it (it owns the capture), once, when it sees this flag.
            if (s.translateOnOpen || site?.autoTranslate == true) _autoStart.value = true
        }
    }

    fun load(url: String) {
        val target = normalise(url)
        _ui.update { it.copy(url = target) }
        web?.loadUrl(target)
    }

    private fun isWebUrl(url: String): Boolean {
        val scheme = android.net.Uri.parse(url.trim()).scheme?.lowercase()
        return scheme == "http" || scheme == "https"
    }

    private fun normalise(input: String): String {
        val t = input.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t
        else if (t.contains('.') && !t.contains(' ')) "https://$t"
        else "https://duckduckgo.com/?q=" + android.net.Uri.encode(t)
    }

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

    fun engineLabel(): String {
        val cfg = _ui.value.config ?: return ""
        return cfg.engineId.uiName(context) + " · " + cfg.src.code.uppercase() + " → " + cfg.tgt.code.uppercase()
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
        clearScreenPatches()
        pageText.onPageStarted()
        _ui.update { it.copy(url = url, stripDismissed = false, canGoBack = web?.canGoBack() == true, progress = minOf(it.progress, 10)) }
        viewModelScope.launch {
            val site = siteFor(url)
            val s = settingsRepo.current()
            val adBlock = siteAdBlockOverride ?: site?.adBlockEnabled ?: s.adBlockDefault
            client.adBlockEnabled = adBlock
            // Keep the session's engine: a page load must not undo a pick made in the reader.
            val engine = _ui.value.config?.engineId
            var cfg = withOverride(s.resolve(site).copy(seriesKey = Site.seriesKeyOf(url)))
            if (engine != null) cfg = cfg.copy(engineId = engine)
            _ui.update { it.copy(site = site, adBlockEnabled = adBlock, config = cfg) }
        }
    }

    override fun onPageFinished(url: String, title: String?) {
        _ui.update { it.copy(title = title.orEmpty(), canGoBack = web?.canGoBack() == true, progress = 100) }
        viewModelScope.launch { history.record(url, title.orEmpty()) }
        pageText.onPageFinished()
        if (_screen.value.active) onScroll(_scroll.value.first, _scroll.value.second, _screen.value.scale)
    }

    override fun onBlockedCountChanged(count: Int) = _ui.update { it.copy(blockedCount = count) }

    /** From the WebChromeClient: drives the load bar under the URL field. */
    fun onProgress(percent: Int) = _ui.update { it.copy(progress = percent.coerceIn(0, 100)) }

    override fun onProgressUrl(url: String) = _ui.update { it.copy(url = url, canGoBack = web?.canGoBack() == true) }

    override fun onCleared() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
    }

    suspend fun blockListAge(): Long = settingsRepo.settings.first().blockListUpdatedAt

    private companion object {
        /** Quiet time after the last scroll event before the viewport counts as settled. */
        const val SETTLE_MS = 450L
        /** Fraction of the viewport the page must have moved before a settled viewport is captured again. */
        const val MIN_MOVE = 0.2f
        const val DUPLICATE_OVERLAP = 0.5f
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
    }
}
