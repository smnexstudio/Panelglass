package com.smnexstudio.panelglass.feature.browser.web

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.smnexstudio.panelglass.feature.browser.block.BlockList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns request interception for ad/popup blocking and the navigation policy. Everything in
 * [shouldInterceptRequest] runs on a background thread for every subresource, so it reads only
 * the `@Volatile` fields set at page load and never touches Room, DataStore or the block-list repo.
 */
class MangaWebViewClient(
    private val context: Context,
    private val bridgeScript: String,
    private val listener: Listener,
) : WebViewClient() {

    interface Listener {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String, title: String?)
        fun onBlockedCountChanged(count: Int)
        fun onProgressUrl(url: String)
    }

    @Volatile var adBlockEnabled: Boolean = true
    @Volatile var blockList: BlockList = BlockList.EMPTY
    @Volatile private var pageHost: String = ""

    private val blocked = AtomicInteger(0)
    val blockedCount: Int get() = blocked.get()

    // ---- navigation policy ---------------------------------------------------------------

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            // Other apps only for a tap on a mail/phone/SMS link. Everything else (market://, custom app schemes,
            // intent://) is how ads jump out of the page into the Play Store or an installed app.
            if (request.hasGesture() && scheme in EXTERNAL_SCHEMES) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else countBlocked()
            return true
        }
        if (!request.isForMainFrame) return false
        val crossSite = !sameSite(url.host, pageHost)
        // A known ad host never gets the whole reader, tap or not: pages hang "click anywhere" handlers on the art
        // that send the first tap to an ad network.
        if (crossSite && adBlockEnabled && blockList.blocks(url)) { countBlocked(); return true }
        // Script-driven cross-site navigation without a tap is a popup or redirect ad.
        if (crossSite && !request.hasGesture() && !request.isRedirect) { countBlocked(); return true }
        return false
    }

    /**
     * What to do with a page's request for a new window (`window.open`, `target=_blank`), once its URL is known:
     * true to open it in the reader. Only a tapped link to the same site is kept; everything else is a pop-up or a
     * popunder, and is dropped.
     */
    fun allowsNewWindow(url: Uri, userGesture: Boolean): Boolean {
        val scheme = url.scheme?.lowercase()
        val ok = userGesture && (scheme == "http" || scheme == "https") && sameSite(url.host, pageHost) &&
            !(adBlockEnabled && blockList.blocks(url))
        if (!ok) countBlocked()
        return ok
    }

    private fun countBlocked() = listener.onBlockedCountChanged(blocked.incrementAndGet())

    /** Same registrable domain (`a.example.co.jp` and `b.example.co.jp`, not `a.co.jp` and `b.co.jp`). */
    private fun sameSite(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return siteOf(a) == siteOf(b)
    }

    // ---- interception -------------------------------------------------------------------

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (adBlockEnabled && blockList.blocks(url)) {
            listener.onBlockedCountChanged(blocked.incrementAndGet())
            return empty(request)
        }
        return null
    }

    /** An empty body whose MIME type matches what the caller expected, so nothing errors loudly. */
    private fun empty(request: WebResourceRequest): WebResourceResponse {
        val accept = request.requestHeaders["Accept"] ?: ""
        val mime = when {
            accept.startsWith("image/") -> "image/png"
            accept.contains("javascript") || request.url.path?.endsWith(".js") == true -> "application/javascript"
            accept.contains("text/css") -> "text/css"
            accept.contains("text/html") -> "text/html"
            else -> "text/plain"
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    // ---- page lifecycle -----------------------------------------------------------------

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        pageHost = Uri.parse(url).host ?: ""
        blocked.set(0)
        listener.onBlockedCountChanged(0)
        listener.onPageStarted(url)
        view.evaluateJavascript(COSMETIC_CSS, null)
        view.evaluateJavascript(bridgeScript, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(bridgeScript, null)
        view.evaluateJavascript("window.__mt && __mt.start();", null)
        listener.onPageFinished(url, view.title)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        listener.onProgressUrl(url)
    }

    companion object {
        /** The only non-web links a tap may hand to another app. */
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms")

        /**
         * The registrable domain (eTLD+1) from the public-suffix list OkHttp ships, so `co.jp`, `com.br` and the like
         * are not mistaken for one site. The list loads on first use; [warmUp] does that off the main thread.
         */
        fun siteOf(host: String): String {
            val h = host.lowercase().trimEnd('.')
            return ("https://$h/").toHttpUrlOrNull()?.topPrivateDomain() ?: h
        }

        fun warmUp() { siteOf("www.example.co.jp") }

        /** Ad iframes and the usual overlay containers. Fixed-position covers are handled in mt.js by size. */
        val COSMETIC_CSS = """
            (function(){var s=document.createElement('style');s.id='__mt_css';s.textContent=
            'iframe[src*="ads"],iframe[src*="doubleclick"],iframe[id^="google_ads"],ins.adsbygoogle,[id^="google_ads"],[class*="adsbygoogle"],' +
            'iframe[src*="googlesyndication"],iframe[src*="adserver"],[id^="div-gpt-ad"],amp-ad,amp-embed,[data-ad-slot],[data-adunit],' +
            '[id*="popup-overlay"],[class*="popup-overlay"],[class*="modal-backdrop"],[id*="interstitial"],[class*="interstitial"]' +
            '{display:none!important;visibility:hidden!important}';
            var root=document.head||document.documentElement;if(root){root.appendChild(s);}else{document.addEventListener('DOMContentLoaded',function(){(document.head||document.documentElement).appendChild(s);});}})();
        """.trimIndent()
    }
}
