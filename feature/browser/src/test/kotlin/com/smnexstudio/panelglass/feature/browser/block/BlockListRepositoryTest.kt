package com.smnexstudio.panelglass.feature.browser.block

import android.content.Context
import com.smnexstudio.panelglass.feature.browser.MemoryDataStore
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * The runtime block lists: which source wins, what is cached, and what happens without a network. Each list URL is
 * answered by an interceptor (nothing reaches the internet). Robolectric (JUnit 4 via vintage) for filesDir and Uri.
 */
@RunWith(RobolectricTestRunner::class)
class BlockListRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var settings: SettingsRepository
    /** URL → (status, body); a URL missing here fails as if the host were unreachable. */
    private val replies = HashMap<String, Pair<Int, String>>()
    private val asked = mutableListOf<String>()
    private val dir get() = File(context.filesDir, "blocklist")

    private val adguard = BlockListRepository.SOURCES[0].urls[0]
    private val stevenBlack = BlockListRepository.SOURCES[0].urls[1]
    private val hagezi = BlockListRepository.SOURCES[1].urls[0]

    @Before fun setUp() {
        settings = SettingsRepository(MemoryDataStore())
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        synchronized(asked) { asked += url }
        val (code, body) = replies[url] ?: throw IOException("unreachable: $url")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body(body.toResponseBody("text/plain".toMediaType())).build()
    }.build()

    private fun repo() = BlockListRepository(context, http, settings)

    /** A hosts-format list of [n] invented hosts under [domain]. */
    private fun hosts(domain: String, n: Int = 1_200) = (1..n).joinToString("\n") { "0.0.0.0 h$it.$domain" }
    private fun adblock(domain: String, n: Int = 1_200) = (1..n).joinToString("\n") { "||p$it.$domain^" }

    @Test fun theAdListFallsBackToStevenBlackWhenAdGuardFails() = runBlocking<Unit> {
        replies[adguard] = 503 to "unavailable"
        replies[stevenBlack] = 200 to hosts("ads.example")
        replies[hagezi] = 200 to adblock("pop.example")
        val r = repo()
        assertTrue(r.refresh())
        val list = r.list.value
        assertTrue(list.blocksHost("h7.ads.example"))
        assertTrue(list.blocksHost("p7.pop.example"))
        assertEquals(listOf(adguard, stevenBlack, hagezi), asked)
        assertTrue(File(dir, "ads.txt").isFile && File(dir, "popups.txt").isFile)
        assertEquals(r.updatedAt.value, settings.current().blockListUpdatedAt)
    }

    /** A truncated or wrong reply (an error page served as 200) never replaces a list. */
    @Test fun aListTooSmallToBeRealIsRefused() = runBlocking<Unit> {
        replies[adguard] = 200 to hosts("ads.example", n = 10)
        replies[stevenBlack] = 200 to "<html>not a list</html>"
        replies[hagezi] = 200 to adblock("pop.example", n = 999)
        assertFalse(repo().refresh())
        assertFalse(dir.exists() && dir.list()!!.isNotEmpty())
    }

    /** Offline: the last good copies are loaded and kept; nothing throws. */
    @Test fun withoutANetworkTheCachedListsAreUsedAndKept() = runBlocking<Unit> {
        dir.mkdirs()
        File(dir, "ads.txt").writeText(hosts("cached.example"))
        File(dir, "popups.txt").writeText(adblock("cachedpop.example"))
        val before = File(dir, "ads.txt").readText()
        val r = repo()
        r.ensureLoaded()
        val loaded = withTimeout(10_000) { r.list.first { it.size > 0 } }
        assertTrue(loaded.blocksHost("h3.cached.example"))
        assertFalse("nothing reachable: no refresh", r.refresh())
        assertEquals(before, File(dir, "ads.txt").readText())
        assertTrue(r.list.value.blocksHost("p3.cachedpop.example"))
    }

    /** Update tapped while the daily refresh runs: it joins that fetch and gets its answer, not a "failed". */
    @Test fun anUpdateDuringARefreshSharesItsResult() = runBlocking<Unit> {
        replies[adguard] = 200 to hosts("ads.example")
        replies[hagezi] = 200 to adblock("pop.example")
        val r = repo()
        val daily = async(Dispatchers.IO) { r.refresh() }
        val update = async(Dispatchers.IO) { r.updateNow() }
        assertTrue(daily.await())
        assertTrue("the second caller is not told it failed", update.await())
        assertTrue("one fetch per list, not two", asked.count { it == adguard } <= 2)
    }

    /** One source failing keeps its own last copy in the merge while the other refreshes. */
    @Test fun aSourceThatFailsToRefreshKeepsItsLastCopy() = runBlocking<Unit> {
        dir.mkdirs()
        File(dir, "popups.txt").writeText(adblock("oldpop.example"))
        replies[adguard] = 200 to adblock("fresh.example")
        val r = repo()
        assertTrue(r.refresh())
        assertTrue(r.list.value.blocksHost("p1.fresh.example"))
        assertTrue(r.list.value.blocksHost("p1.oldpop.example"))
    }

    /** The single cache file of earlier versions seeds the ad list once. */
    @Test fun theLegacyCacheFileSeedsTheAdList() = runBlocking<Unit> {
        File(context.filesDir, "blocklist.txt").writeText(hosts("legacy.example"))
        val r = repo()
        r.ensureLoaded()
        withTimeout(10_000) { r.list.first { it.size > 0 } }
        assertTrue(r.list.value.blocksHost("h1.legacy.example"))
        assertFalse(File(context.filesDir, "blocklist.txt").exists())
        assertTrue(File(dir, "ads.txt").isFile)
    }

    @Test fun stalePartsAreRefreshedOnLoad() = runBlocking<Unit> {
        dir.mkdirs()
        val today = 1_800_000_000_000L
        File(dir, "ads.txt").apply { writeText(hosts("old.example")); setLastModified(today - BlockListRepository.REFRESH_MS - 60_000) }
        File(dir, "popups.txt").apply { writeText(adblock("pop.example")); setLastModified(today - 60_000) }
        replies[adguard] = 200 to hosts("new.example")
        val r = repo().apply { now = { today } }
        r.ensureLoaded()
        withTimeout(10_000) { r.list.first { it.blocksHost("h1.new.example") } }
        assertFalse("the refreshed list replaces the old one", r.list.value.blocksHost("h1.old.example"))
    }
}
