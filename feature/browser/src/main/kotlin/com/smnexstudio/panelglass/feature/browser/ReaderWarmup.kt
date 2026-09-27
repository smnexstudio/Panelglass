package com.smnexstudio.panelglass.feature.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.os.Looper
import android.webkit.WebView
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.SiteRepository
import com.smnexstudio.panelglass.core.pipeline.TranslationPipeline
import com.smnexstudio.panelglass.feature.browser.block.BlockListRepository
import com.smnexstudio.panelglass.feature.browser.web.MangaWebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * Everything the reader needs on its first composition, prepared on the idle frame after launch instead of inside
 * the tap-to-reader transition: the singleton graph behind [ReaderViewModel] (class loading plus Retrofit / Json /
 * pipeline construction), the `mt.js` bridge source, and a spare [WebView]. Chromium's own start-up rides along
 * with the spare. Measured on the site tap, these three were most of the main-thread time before the first frame.
 */
@Singleton
class ReaderWarmup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pipeline: Provider<TranslationPipeline>,
    private val sites: Provider<SiteRepository>,
    private val history: Provider<HistoryRepository>,
    private val blockLists: Provider<BlockListRepository>,
) {
    /** The page bridge (`mt.js`) and "Translate page" (`pt.js`), read once off the main thread and injected together. */
    val bridgeScript: String by lazy {
        listOf("mt.js", "pt.js").joinToString("\n") { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
    }

    private var spare: WebView? = null
    private var graphWarmed = false

    /** Call from the activity once its first frame is up; runs the graph warm-up on a worker and the WebView on the next idle. */
    fun schedule(activity: Activity) {
        if (!graphWarmed) {
            graphWarmed = true
            thread(name = "reader-warmup", priority = Thread.MIN_PRIORITY) {
                runCatching {
                    bridgeScript
                    pipeline.get(); sites.get(); history.get()
                    blockLists.get().ensureLoaded()
                    // The public-suffix list behind the reader's same-site test, off the main thread.
                    MangaWebViewClient.warmUp()
                }
            }
        }
        Looper.myQueue().addIdleHandler { prepare(activity); false }
    }

    /**
     * A WebView must be created on the main thread with the activity that will host it. It is made on a
     * [MutableContextWrapper] because the reader keeps its WebView across compositions (ReaderViewModel.hostedWebView)
     * and re-points it at whichever activity shows it.
     */
    private fun prepare(activity: Activity) {
        if (spare != null || activity.isFinishing || activity.isDestroyed) return
        spare = runCatching { WebView(MutableContextWrapper(activity)) }.getOrNull()
    }

    /** The spare if it was made for this activity, otherwise a fresh one; the spare is replaced on the next idle. */
    fun obtain(context: Context): WebView {
        val s = spare
        spare = null
        val activity = context.activity()
        if (s != null && activity != null && s.context.activity() === activity) {
            Looper.myQueue().addIdleHandler { prepare(activity); false }
            return s
        }
        s?.destroy()
        return WebView(MutableContextWrapper(context))
    }

    /** The activity is going away: a spare made for it would leak it. */
    fun release(activity: Activity) {
        val s = spare ?: return
        if (s.context.activity() === activity) { spare = null; s.destroy() }
    }

    private fun Context.activity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
