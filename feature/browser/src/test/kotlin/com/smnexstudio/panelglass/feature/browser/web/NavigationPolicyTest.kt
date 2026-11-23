package com.smnexstudio.panelglass.feature.browser.web

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.feature.browser.block.BlockList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The reader's navigation policy (CLAUDE.md › Rules: https only, pop-ups, blocked hosts): which page navigations,
 * new windows and sub-requests the WebView may make. Robolectric (JUnit 4 via vintage) for WebView and Uri.
 */
@RunWith(RobolectricTestRunner::class)
class NavigationPolicyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val counts = mutableListOf<Int>()
    private val listener = object : MangaWebViewClient.Listener {
        override fun onPageStarted(url: String) = Unit
        override fun onPageFinished(url: String, title: String?) = Unit
        override fun onBlockedCountChanged(count: Int) { counts += count }
        override fun onProgressUrl(url: String) = Unit
    }
    private lateinit var client: MangaWebViewClient
    private lateinit var view: WebView

    @Before fun setUp() {
        client = MangaWebViewClient(app, "", listener)
        client.blockList = BlockList.parse("||ads.example.net^\n||popunder.example.org^\n||banner.comics.example.com^")
        view = WebView(app)
        client.onPageStarted(view, "https://read.comics.example.com/series/1", null)
        counts.clear()
    }

    private class Req(
        url: String, private val main: Boolean = true, private val gesture: Boolean = true,
        private val redirect: Boolean = false, private val headers: Map<String, String> = emptyMap(),
    ) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl(): Uri = uri
        override fun isForMainFrame() = main
        override fun isRedirect() = redirect
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun navigate(r: Req) = client.shouldOverrideUrlLoading(view, r)
    private fun loaded(): String? = shadowOf(view).lastLoadedUrl

    // ---- schemes -------------------------------------------------------------------------------------------

    /** How ads jump out of the page: never followed, tap or not, and counted as blocked. */
    @Test fun nonWebSchemesNeverLoadOrLeaveTheApp() {
        for (url in listOf("javascript:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "market://details?id=x",
            "file:///sdcard/a.html", "data:text/html,hi", "content://x/y", "customapp://open")) {
            assertTrue(url, navigate(Req(url)))
        }
        assertNull("no other app was opened", shadowOf(app).nextStartedActivity)
        assertEquals(7, client.blockedCount)
    }

    @Test fun onlyATappedMailPhoneOrSmsLinkOpensAnotherApp() {
        assertTrue(navigate(Req("mailto:someone@example.com", gesture = true)))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("mailto", started.data!!.scheme)
        assertTrue(navigate(Req("tel:123", gesture = false)))
        assertNull("a scripted tel: link opens nothing", shadowOf(app).nextStartedActivity)
        assertEquals(1, client.blockedCount)
    }

    // ---- https only ----------------------------------------------------------------------------------------

    @Test fun anHttpLinkIsFollowedAsItsHttpsUpgrade() {
        assertTrue(navigate(Req("http://read.comics.example.com/series/2")))
        assertEquals("https://read.comics.example.com/series/2", loaded())
    }

    /** A downgrade redirect is explicitly cancelled, never upgraded into a redirect loop. */
    @Test fun aRedirectDownToHttpIsNotUpgraded() {
        assertTrue(navigate(Req("http://read.comics.example.com/series/2", gesture = false, redirect = true)))
        assertNull(loaded())
    }

    @Test fun httpsSameSiteNavigationLoadsNormally() {
        assertFalse(navigate(Req("https://read.comics.example.com/series/1/ch2")))
        assertFalse("another subdomain of the same site", navigate(Req("https://img.comics.example.com/p", gesture = false)))
        assertEquals(0, client.blockedCount)
    }

    // ---- cross-site and blocked hosts ----------------------------------------------------------------------

    @Test fun aScriptDrivenCrossSiteNavigationIsAPopupAndIsBlocked() {
        assertTrue(navigate(Req("https://elsewhere.example.org/landing", gesture = false)))
        assertFalse("a tapped link to another site is the reader's choice", navigate(Req("https://elsewhere.example.org/landing", gesture = true)))
        assertFalse("a server redirect is not a pop-up", navigate(Req("https://elsewhere.example.org/landing", gesture = false, redirect = true)))
    }

    /** "Click anywhere" handlers send the first tap to an ad network: a blocked host never gets the reader. */
    @Test fun aBlockedHostNeverGetsTheMainFrameEvenOnATap() {
        assertTrue(navigate(Req("https://ads.example.net/click", gesture = true)))
        assertEquals(1, client.blockedCount)
        client.adBlockEnabled = false
        assertFalse(navigate(Req("https://ads.example.net/click", gesture = true)))
    }

    @Test fun subFrameNavigationsAreLeftToTheWebView() {
        assertFalse(navigate(Req("https://elsewhere.example.org/frame", main = false, gesture = false)))
    }

    // ---- new windows ---------------------------------------------------------------------------------------

    @Test fun onlyATappedSameSiteHttpsWindowOpens() {
        assertTrue(client.allowsNewWindow(Uri.parse("https://read.comics.example.com/ch3"), userGesture = true))
        assertFalse("no tap", client.allowsNewWindow(Uri.parse("https://read.comics.example.com/ch3"), userGesture = false))
        assertFalse("cleartext", client.allowsNewWindow(Uri.parse("http://read.comics.example.com/ch3"), userGesture = true))
        assertFalse("another site", client.allowsNewWindow(Uri.parse("https://elsewhere.example.org/"), userGesture = true))
        assertFalse("a blocked host on the same site", client.allowsNewWindow(Uri.parse("https://banner.comics.example.com/x"), userGesture = true))
        assertFalse("a public suffix is no site", client.allowsNewWindow(Uri.parse("https://other.example.co.jp/"), userGesture = true))
        assertEquals(5, client.blockedCount)
    }

    // ---- sub-requests --------------------------------------------------------------------------------------

    @Test fun blockedSubRequestsGetAnEmptyBodyOfTheExpectedType() {
        val img = client.shouldInterceptRequest(view, Req("https://ads.example.net/b.gif", main = false, headers = mapOf("Accept" to "image/webp")))!!
        assertEquals("image/png", img.mimeType)
        assertEquals(0, img.data.readBytes().size)
        val js = client.shouldInterceptRequest(view, Req("https://popunder.example.org/p.js", main = false))!!
        assertEquals("application/javascript", js.mimeType)
        assertNull(client.shouldInterceptRequest(view, Req("https://img.comics.example.com/page1.jpg", main = false)))
        client.adBlockEnabled = false
        assertNull(client.shouldInterceptRequest(view, Req("https://ads.example.net/b.gif", main = false)))
        assertEquals(listOf(1, 2), counts)
    }

    @Test fun httpFrameNavigationsAreBlockedEvenWithoutAdBlocking() {
        client.adBlockEnabled = false
        assertTrue(navigate(Req("http://read.comics.example.com/frame", main = false)))
        assertEquals(1, client.blockedCount)
    }

    @Test fun httpResourcesAreBlockedEvenWithoutAdBlocking() {
        client.adBlockEnabled = false
        for (main in listOf(true, false)) {
            val response = client.shouldInterceptRequest(view, Req("http://read.comics.example.com/image.jpg", main = main))
            assertTrue(response != null)
            assertEquals(0, response!!.data.readBytes().size)
        }
        assertEquals(2, client.blockedCount)
    }

    @Test fun aNewPageResetsTheBlockedCount() {
        navigate(Req("javascript:void(0)"))
        assertEquals(1, client.blockedCount)
        client.onPageStarted(view, "https://read.comics.example.com/series/2", null)
        assertEquals(0, client.blockedCount)
        assertEquals(0, counts.last())
    }
}
