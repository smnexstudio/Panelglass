package com.smnexstudio.panelglass.feature.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.smnexstudio.panelglass.core.ui.R as UiR
import com.smnexstudio.panelglass.core.ui.uiName
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.ui.PrimaryPill
import com.smnexstudio.panelglass.core.ui.TextAction
import com.smnexstudio.panelglass.core.ui.Tokens
import com.smnexstudio.panelglass.feature.browser.web.NewWindows
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(
    initialUrl: String,
    initialEngine: String? = null,
    /** Launch-intent languages (`Lang` names) and auto-Start, for scripted runs; this session only. */
    initialSrc: String? = null,
    initialTgt: String? = null,
    startNow: Boolean = false,
    /** The library site that was tapped, when the reader was opened from one. */
    initialSiteId: Long? = null,
    /** [initialUrl] came from another app's intent: never translated on open. */
    initialExternal: Boolean = false,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenKeySheet: (engineName: String) -> Unit = {},
    vm: ReaderViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val scroll by vm.scroll.collectAsStateWithLifecycle()
    val zoom by vm.zoom.collectAsStateWithLifecycle()
    val autoStart by vm.autoStart.collectAsStateWithLifecycle()
    val pageText by vm.pageText.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(initialUrl) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(initialEngine) {
        if (!initialEngine.isNullOrBlank()) {
            try {
                val eng = EngineId.valueOf(initialEngine)
                vm.selectEngine(eng)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(initialSrc, initialTgt, startNow) {
        val src = initialSrc?.let { n -> Lang.entries.firstOrNull { it.name == n } }
        val tgt = initialTgt?.let { n -> Lang.entries.firstOrNull { it.name == n } }
        if (src != null || tgt != null || startNow) vm.overrideLanguages(src, tgt, startNow)
    }

    LaunchedEffect(ui.url) { if (!editing) urlText = ui.url }
    BackHandler { if (!vm.goBack()) onBack() }

    // The spare made on the idle frame after launch when there is one: AwContents' construction is the largest
    // single cost of this composition.
    // Back from Settings the view model still has the page (hostedWebView): no reload, same scroll, same patches.
    val webView = remember {
        vm.hostedWebView(context) ?: vm.warmup.obtain(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Pages never need local files or content providers; with JavaScript on they would be readable by any site.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = true
            // https only: an https page's http subresources are never loaded (the app sends no cleartext at all).
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Multiple windows on, so window.open / target=_blank reach onCreateWindow (NewWindows) and get a
            // decision; with them off a single-window WebView loads them over the reader itself.
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) =
                    view != null && NewWindows.onCreateWindow(view, isUserGesture, resultMsg, vm.client)
                override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean { result?.confirm(); return true }
                override fun onProgressChanged(view: WebView?, newProgress: Int) { vm.onProgress(newProgress) }
            }
            // Screen patches live in page space: every scroll step moves them, a settled viewport gets translated.
            @Suppress("DEPRECATION")
            setOnScrollChangeListener { v, x, y, _, _ -> vm.onScroll(x, y, (v as WebView).scale) }
            // Rotation (or a split-screen resize) lays the page out anew: patches made for the old width are void.
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol && or - ol > 0) vm.onViewportResized() }
            // A paged reader turns its page without scrolling: every finished gesture lets the view model look.
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_UP) vm.onGestureEnd()
                false
            }
            vm.attach(this, initialUrl, initialSiteId, initialExternal)
        }
    }
    // Off screen (Settings on top) the page keeps loading but its timers and media stop; the view model destroys it
    // when the reader is closed for good.
    DisposableEffect(Unit) { onDispose { webView.onPause() } }

    // Translate-on-open (settings or the site): start once; Stop is then the user's to keep.
    LaunchedEffect(autoStart, ui.config != null) {
        if (autoStart && ui.config != null) {
            vm.consumeAutoStart()
            if (!screen.active) vm.startScreen { captureViewport(webView) }
        }
    }

    Column(Modifier.fillMaxSize().background(Tokens.Ink)) {
        // ---- compact top bar ---------------------------------------------------------------
        // Drawn after the page (zIndex): until the WebView has its first frame its hardware draw clears whatever
        // was painted before it, which left this bar black while the bottom bar, painted later, showed.
        Column(Modifier.fillMaxWidth().zIndex(1f).background(Tokens.Ink).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back), tint = Tokens.Bg) }
            Row(
                Modifier.weight(1f).height(36.dp)
                    .background(Tokens.InkRaised, RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (ui.adBlockEnabled) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = stringResource(if (ui.adBlockEnabled) UiR.string.reader_adblock_on else UiR.string.reader_adblock_off),
                    tint = if (ui.adBlockEnabled) Tokens.Yellow else Tokens.InkFaint,
                    modifier = Modifier.size(16.dp).combinedClickable(onClick = {}, onLongClick = { vm.toggleAdBlock() }),
                )
                Spacer(Modifier.size(8.dp))
                BasicTextField(
                    value = urlText,
                    onValueChange = { urlText = it; editing = true },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.Bg, fontSize = 13.sp),
                    cursorBrush = SolidColor(Tokens.Yellow),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { editing = false; focus.clearFocus(); vm.load(urlText) }),
                    modifier = Modifier.weight(1f),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(UiR.string.cd_more), tint = Tokens.Bg) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(UiR.string.reader_reload)) }, onClick = { menu = false; vm.reload() })
                    DropdownMenuItem(text = { Text(stringResource(if (ui.adBlockEnabled) UiR.string.reader_adblock_turn_off else UiR.string.reader_adblock_turn_on)) }, onClick = { menu = false; vm.toggleAdBlock() })
                    // Fresh OCR + engine call for what is on screen: for a poor translation, or after switching the engine.
                    DropdownMenuItem(text = { Text(stringResource(UiR.string.reader_retranslate)) }, onClick = { menu = false; vm.retranslateScreen { captureViewport(webView) } })
                    // The page's own text (titles, chapter lists), not the art: Chrome's translate bar, on ML Kit.
                    DropdownMenuItem(text = { Text(stringResource(UiR.string.reader_translate_page)) }, onClick = { menu = false; vm.pageText.open() })
                    if (ui.site == null) DropdownMenuItem(text = { Text(stringResource(UiR.string.reader_add_to_sites)) }, onClick = { menu = false; scope.launch { vm.addCurrentSite() } })
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Tokens.InkRaised)

        // ---- translate bar ("Translate page") -----------------------------------------------
        if (pageText.open) {
            PageTranslateBar(
                pageText,
                onSource = { vm.pageText.pickSource(it) }, onTarget = { vm.pageText.pickTarget(it) },
                onTranslate = { vm.pageText.translate() }, onShowOriginal = { vm.pageText.showOriginal() },
                onClose = { vm.pageText.close() },
            )
            HorizontalDivider(thickness = 1.dp, color = Tokens.InkRaised)
        }

        // ---- blocked-requests strip --------------------------------------------------------
        if (ui.blockedCount > 0 && !ui.stripDismissed) {
            Row(
                Modifier.fillMaxWidth().height(32.dp)
                    .combinedClickable(onClick = {}, onLongClick = { vm.toggleAdBlock() })
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(UiR.plurals.reader_blocked, ui.blockedCount, ui.blockedCount),
                    style = MaterialTheme.typography.labelSmall, color = Tokens.InkFaint,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { vm.dismissStrip() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Close, contentDescription = stringResource(UiR.string.cd_dismiss), tint = Tokens.InkFaint, modifier = Modifier.size(14.dp)) }
            }
            HorizontalDivider(thickness = 1.dp, color = Tokens.InkRaised)
        }
        }

        // ---- page ---------------------------------------------------------------------------
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { (webView.parent as? ViewGroup)?.removeView(webView); webView }, modifier = Modifier.fillMaxSize())
            ScreenOverlayLayer(screen, scroll, zoom)
            // Browser-style load bar, laid over the page so it never resizes the WebView.
            LoadBar(ui.progress, Modifier.align(Alignment.TopCenter))
        }

        // ---- persistent bottom bar ----------------------------------------------------------
        HorizontalDivider(thickness = 1.dp, color = Tokens.InkRaised)
        Column(Modifier.fillMaxWidth().background(Tokens.Ink).navigationBarsPadding()) {
            val failure = screen.failure
            when (failure) {
                is EngineFailure.MissingKey -> ErrorRow(stringResource(UiR.string.error_needs_key, failure.engine.uiName()), stringResource(UiR.string.action_add_key)) { onOpenKeySheet(failure.engine.name) }
                // The escape hatch is Google Translate (ML Kit, on-device, no key) — named as such, since the failing
                // engine may itself be the on-device model — and only offered while it is not the one that failed.
                is EngineFailure.QuotaExceeded -> ErrorRow(stringResource(UiR.string.error_quota, failure.engine.uiName()), stringResource(UiR.string.action_use_google_translate)) { vm.switchToOnDevice { captureViewport(webView) } }
                is EngineFailure.Unavailable, is EngineFailure.Network, is EngineFailure.Overloaded ->
                    if (failure.engine == EngineId.GOOGLE) ErrorRow(stringResource(UiR.string.error_unavailable_now, failure.engine.uiName()), null, null)
                    else ErrorRow(stringResource(UiR.string.error_unavailable_now, failure.engine.uiName()), stringResource(UiR.string.action_use_google_translate)) { vm.switchToOnDevice { captureViewport(webView) } }
                else -> Unit
            }
            // Both states keep one height: a bar that changes height resizes the WebView, and a reader that centres
            // its page (bilibili) then moves the art under patches already made (11 px on a phone: the first
            // capture ran at the idle bar's height, the patches showed at the active one's).
            if (screen.active) {
                // screen session: (status) Translating screen… / N regions on screen   [ Stop ]
                Row(
                    Modifier.fillMaxWidth().heightIn(min = BAR_ROW_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (screen.working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Tokens.Yellow)
                    else Icon(
                        if (screen.error != null) Icons.Filled.Warning else Icons.Filled.Check,
                        contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = if (screen.error != null) Tokens.Error else Tokens.Yellow,
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                screen.working -> stringResource(UiR.string.reader_translating)
                                screen.error != null -> screen.error!!
                                else -> pluralStringResource(UiR.plurals.reader_patches, screen.patches.size, screen.patches.size)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (screen.error != null && !screen.working) Tokens.Error else Tokens.Bg,
                        )
                        Text( vm.engineLabel(), style = MaterialTheme.typography.labelSmall, color = Tokens.InkFaint)
                    }
                    TextAction(stringResource(UiR.string.reader_stop), Tokens.Yellow) { vm.stopScreen() }
                }
            } else {
                // idle: [ Start ] Gemini 2.5 Flash · JA → EN — the engine is chosen in Settings, the label only reports it.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = BAR_ROW_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryPill(stringResource(UiR.string.reader_start), onClick = { vm.startScreen { captureViewport(webView) } })
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = vm.engineLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.InkFaint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Height of the bottom bar's row in every state (the Start pill's row is the tallest, at about 62 dp). */
private val BAR_ROW_HEIGHT = 64.dp

/** A thin bar that fills with the page's load progress and fades once the page is in, as in Chrome or Firefox. */
@Composable
private fun LoadBar(progress: Int, modifier: Modifier = Modifier) {
    val loading = progress < 100
    // Hold the last real value while fading out so the bar finishes full rather than snapping back.
    val fraction by animateFloatAsState(if (loading) progress / 100f else 1f, animationSpec = tween(200), label = "load")
    AnimatedVisibility(visible = loading, enter = fadeIn(tween(120)), exit = fadeOut(tween(300)), modifier = modifier) {
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Tokens.Yellow))
        }
    }
}

@Composable
private fun ErrorRow(message: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = Tokens.Error, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextAction(action, Tokens.Sky, onAction)
    }
}
