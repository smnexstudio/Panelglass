package com.smnexstudio.panelglass.feature.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.smnexstudio.panelglass.core.model.WebUrl

/**
 * Answers `WebChromeClient.onCreateWindow`. The reader has one window, so every `window.open` and `target=_blank`
 * link comes here instead of silently replacing the page (which is what a single-window WebView does with them,
 * and how popunders take over the reader on the first tap).
 *
 * The new window's URL is only known once it starts loading, so a throwaway WebView is handed out to catch it;
 * [MangaWebViewClient.allowsNewWindow] then decides: a tapped same-site link opens in the reader, anything else is
 * dropped. The throwaway never shows and is destroyed after its first navigation, or after [TIMEOUT_MS] when the
 * opener never navigates it.
 */
object NewWindows {
    private const val TIMEOUT_MS = 10_000L

    fun onCreateWindow(parent: WebView, isUserGesture: Boolean, resultMsg: Message?, client: MangaWebViewClient): Boolean {
        val msg = resultMsg ?: return false
        if (!isUserGesture) { client.allowsNewWindow(Uri.EMPTY, false); return false }
        val transport = msg.obj as? WebView.WebViewTransport ?: return false
        val probe = WebView(parent.context).apply {
            // It only has to see the first URL: no scripts and no local files.
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        var done = false
        fun finish() { if (!done) { done = true; probe.post { probe.stopLoading(); probe.destroy() } } }
        fun decide(url: String?) {
            if (done || url.isNullOrBlank() || url == "about:blank") return
            // https only: an http target is judged, and opened, as its https upgrade.
            val target = WebUrl.https(url) ?: url
            if (client.allowsNewWindow(Uri.parse(target), userGesture = true)) parent.loadUrl(target)
            finish()
        }
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                decide(request.url.toString()); return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = decide(url)
        }
        parent.postDelayed({ finish() }, TIMEOUT_MS)
        transport.webView = probe
        msg.sendToTarget()
        return true
    }
}
