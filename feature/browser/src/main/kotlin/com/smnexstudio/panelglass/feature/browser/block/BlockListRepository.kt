package com.smnexstudio.panelglass.feature.browser.block

import com.smnexstudio.panelglass.core.data.block.BlockListUpdater
import android.content.Context
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches the block lists at runtime and merges them: an ad/tracker list (AdGuard DNS filter, StevenBlack's
 * unified hosts when AdGuard cannot be reached) and HaGeZi's pop-up ads list (the popunder and redirect networks
 * reader sites run, most of which the ad list does not name). Each source is cached on disk on its own
 * (`filesDir/blocklist/<name>.txt`), so a source that fails to refresh keeps its last good copy instead of dropping
 * out of the merge. Refreshed once a day; nothing is bundled in assets, so the lists never ship stale.
 */
@Singleton
class BlockListRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttp: OkHttpClient,
    private val settings: SettingsRepository,
) : BlockListUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val dir = File(context.filesDir, "blocklist")
    /** The single cache file of earlier versions: it held the ad list, so it seeds that source once. */
    private val legacyFile = File(context.filesDir, "blocklist.txt")

    private val _list = MutableStateFlow(BlockList.EMPTY)
    val list: StateFlow<BlockList> = _list

    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt

    private val fetchOkHttp by lazy {
        okHttp.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun cacheOf(source: Source) = File(dir, source.name + ".txt")

    /** The clock a cached list's age is measured with; tests set it. */
    internal var now: () -> Long = System::currentTimeMillis

    /** Loads the cached lists immediately and refreshes in the background when one is stale or missing. */
    fun ensureLoaded() {
        scope.launch {
            mutex.withLock {
                if (_list.value.size == 0) {
                    migrateLegacy()
                    try { publish() } catch (_: Exception) {}
                }
            }
            val at = now()
            if (SOURCES.any { at - cacheOf(it).lastModified() > REFRESH_MS }) refresh()
        }
    }

    private fun migrateLegacy() {
        val ads = cacheOf(SOURCES.first())
        if (legacyFile.isFile && !ads.isFile) {
            dir.mkdirs()
            if (!legacyFile.renameTo(ads)) legacyFile.delete()
        } else legacyFile.delete()
    }

    /** Parses and merges every cached source into [list]. Call with [mutex] held. */
    private fun publish() {
        var merged = BlockList.EMPTY
        var newest = 0L
        for (source in SOURCES) {
            val f = cacheOf(source)
            if (!f.isFile) continue
            merged += f.bufferedReader().use { BlockList.parse(it) }
            newest = maxOf(newest, f.lastModified())
        }
        if (merged.size > 0) _list.value = merged
        _updatedAt.value = newest
    }

    /** The fetch under way, shared by everyone who asks while it runs. */
    private var inFlight: Deferred<Boolean>? = null
    private val inFlightLock = Any()

    /** Settings › Sites › Block lists › Update: the same fetch as the daily one, now. */
    override suspend fun updateNow(): Boolean = refresh()

    /**
     * Fetches every source; true when at least one fresh list replaced its cache. ensureLoaded is called from the
     * warm-up and from every reader, so one fetch runs at a time: a caller arriving while one runs (the Update button
     * during the daily refresh) waits for it and gets its answer, rather than a "failed" for a fetch that is working.
     */
    suspend fun refresh(): Boolean = synchronized(inFlightLock) {
        inFlight?.takeIf { it.isActive } ?: scope.async { refreshSources() }.also { inFlight = it }
    }.await()

    private suspend fun refreshSources(): Boolean {
        var changed = false
        for (source in SOURCES) {
            for (url in source.urls) {
                val text = fetch(url) ?: continue
                if (BlockList.parse(text).size < source.minHosts) continue
                mutex.withLock {
                    dir.mkdirs()
                    val tmp = File(dir, source.name + ".tmp")
                    tmp.writeText(text)
                    // A rename onto an existing file fails on some file systems (as PatchCache handles): replace it.
                    val target = cacheOf(source)
                    if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) changed = true else tmp.delete()
                }
                break
            }
        }
        if (!changed) return false
        mutex.withLock {
            publish()
            settings.setBlockListUpdatedAt(_updatedAt.value)
        }
        return true
    }

    /** The list's text, or null. A body over [MAX_BYTES] is refused rather than read whole into memory. */
    private fun fetch(url: String): String? = try {
        fetchOkHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.takeIf { r.isSuccessful && it.contentLength() <= MAX_BYTES } ?: return null
            val source = body.source()
            // request() buffers until it has that many bytes or the body ends: true means the body is too large.
            if (source.request(MAX_BYTES + 1)) null else source.buffer.readUtf8()
        }
    } catch (_: Exception) { null }

    /** One merged list: [urls] are tried in order and the first that parses to at least [minHosts] hosts wins. */
    class Source(val name: String, val urls: List<String>, val minHosts: Int)

    companion object {
        const val REFRESH_MS = 24L * 60 * 60 * 1000
        /** Far above any real list (AdGuard's DNS filter is a few MB). */
        const val MAX_BYTES = 32L * 1024 * 1024
        val SOURCES = listOf(
            Source(
                "ads",
                listOf(
                    "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                ),
                minHosts = 1000,
            ),
            Source("popups", listOf("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/popupads.txt"), minHosts = 1000),
        )
    }
}
