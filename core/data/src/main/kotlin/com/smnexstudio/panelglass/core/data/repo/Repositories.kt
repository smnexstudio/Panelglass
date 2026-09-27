package com.smnexstudio.panelglass.core.data.repo

import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.model.GlossaryEntry
import com.smnexstudio.panelglass.core.model.HistoryEntry
import com.smnexstudio.panelglass.core.model.SfxCacheEntry
import com.smnexstudio.panelglass.core.model.Site
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SiteRepository @Inject constructor(private val db: PanelglassDb) {
    val sites: Flow<List<Site>> = db.sites().observeAll()

    suspend fun byId(id: Long): Site? = db.sites().byId(id)

    /** The site that owns [url] ([Site.bestMatch]: host, then the longest saved path), so per-site settings apply while browsing. */
    suspend fun forUrl(url: String): Site? = Site.bestMatch(db.sites().all(), url)

    suspend fun add(site: Site): Long {
        val order = db.sites().nextSortOrder()
        return db.sites().upsert(site.copy(sortOrder = order))
    }

    suspend fun update(site: Site) = db.sites().update(site)
    suspend fun delete(site: Site) = db.sites().delete(site)
    suspend fun setPinned(site: Site, pinned: Boolean) = db.sites().update(site.copy(pinned = pinned))
}

@Singleton
class SfxCacheRepository @Inject constructor(private val db: PanelglassDb) {
    suspend fun lookup(keys: Collection<String>): Map<String, String> =
        if (keys.isEmpty()) emptyMap() else db.sfxCache().getAll(keys.toList()).associate { it.key to it.translated }

    suspend fun store(entries: Map<String, String>) {
        if (entries.isNotEmpty()) db.sfxCache().putAll(entries.map { SfxCacheEntry(it.key, it.value) })
    }

    suspend fun clear() = db.sfxCache().clear()
}

@Singleton
class GlossaryRepository @Inject constructor(private val db: PanelglassDb) {
    fun observe(seriesKey: String): Flow<List<GlossaryEntry>> = db.glossary().observe(seriesKey)
    suspend fun forSeries(seriesKey: String): Map<String, String> =
        db.glossary().forSeries(seriesKey).associate { it.source to it.target }
    suspend fun upsert(entry: GlossaryEntry) = db.glossary().upsert(entry)
    suspend fun delete(entry: GlossaryEntry) = db.glossary().delete(entry)
}

@Singleton
class HistoryRepository @Inject constructor(private val db: PanelglassDb) {
    val recent: Flow<List<HistoryEntry>> = db.history().observeRecent()
    suspend fun record(url: String, title: String) {
        if (!url.startsWith("http")) return
        db.history().put(HistoryEntry(url, title.ifBlank { Site.hostOf(url) }, Site.hostOf(url), System.currentTimeMillis()))
    }
    suspend fun delete(entry: HistoryEntry) = db.history().delete(entry)
    suspend fun clear() = db.history().clear()
}
