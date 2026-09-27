package com.smnexstudio.panelglass.feature.browser.block

import android.content.Context
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
) {
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

    /** Loads the cached lists immediately and refreshes in the background when one is stale or missing. */
    fun ensureLoaded() {
        scope.launch {
            mutex.withLock {
                if (_list.value.size == 0) {
                    migrateLegacy()
                    try { publish() } catch (_: Exception) {}
                }
            }
            val now = System.currentTimeMillis()
            if (SOURCES.any { now - cacheOf(it).lastModified() > REFRESH_MS }) refresh()
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

    private val refreshing = AtomicBoolean(false)

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        // ensureLoaded is called from the warm-up and from every reader; one fetch at a time is enough.
        if (!refreshing.compareAndSet(false, true)) return@withContext false
        try { refreshSources() } finally { refreshing.set(false) }
    }

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
                    if (!tmp.renameTo(cacheOf(source))) tmp.delete() else changed = true
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

    private fun fetch(url: String): String? = try {
        fetchOkHttp.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    } catch (_: Exception) { null }

    /** One merged list: [urls] are tried in order and the first that parses to at least [minHosts] hosts wins. */
    class Source(val name: String, val urls: List<String>, val minHosts: Int)

    companion object {
        const val REFRESH_MS = 24L * 60 * 60 * 1000
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
