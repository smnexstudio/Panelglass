package com.smnexstudio.panelglass.core.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** A user-added comic site. Per-site overrides are nullable columns; null inherits the global default. */
@Serializable
@Entity(tableName = "sites")
data class Site(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val iconUrl: String? = null,
    val pinned: Boolean = false,
    val sortOrder: Int = 0,
    val sourceLang: Lang? = null,
    val targetLang: Lang? = null,
    val engineId: EngineId? = null,
    val adBlockEnabled: Boolean = true,
    val autoTranslate: Boolean = false,
) {
    val host: String get() = hostOf(url)

    companion object {
        fun hostOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            return noScheme.substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
                .removePrefix("www.")
        }

        /**
         * The saved site that owns [url]. Several sites can share a host (two series on one reader), so the host alone
         * does not decide: the site whose saved path is the longest prefix of the page's path wins (`/manga/a/` owns
         * `/manga/a/chapter-39/`). A page under none of them goes to the most general site on that host.
         */
        fun bestMatch(sites: List<Site>, url: String): Site? {
            val host = hostOf(url)
            if (host.isEmpty()) return null
            val onHost = sites.filter { host == it.host || host.endsWith("." + it.host) }
            if (onHost.isEmpty()) return null
            val path = dirOf(url)
            val owners = onHost.filter { path.startsWith(dirOf(it.url)) }
            return if (owners.isNotEmpty()) {
                owners.maxWith(compareBy<Site>({ dirOf(it.url).length }, { it.host.length }, { it.pinned }, { -it.id }))
            } else {
                onHost.maxWith(compareBy<Site>({ it.host.length }, { -dirOf(it.url).length }, { it.pinned }, { -it.id }))
            }
        }

        /** The URL's path without query or fragment, always ending in `/`, so prefixes match whole segments. */
        internal fun dirOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            val p = "/" + noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
            return if (p.endsWith("/")) p else "$p/"
        }

        /** host + first path segment; used to scope glossaries and reading context. */
        fun seriesKeyOf(url: String): String {
            val noScheme = url.substringAfter("://", url)
            val host = hostOf(url)
            val path = noScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
            val first = path.split('/').firstOrNull { it.isNotBlank() } ?: ""
            return if (first.isEmpty()) host else "$host/$first"
        }
    }
}

/** Unused (a viewport's detection is never reused); kept in the schema like [UrlEtagEntry], drop at the next bump. */
@Entity(tableName = "ocr_cache")
data class OcrCacheEntry(
    @PrimaryKey val imageHash: String,
    val srcLang: Lang,
    val regionsJson: String,
    val createdAt: Long,
)

@Entity(tableName = "sfx_cache")
data class SfxCacheEntry(
    @PrimaryKey val key: String,
    val translated: String,
) {
    companion object {
        fun keyOf(normalized: String, src: Lang, tgt: Lang) = "$normalized|${src.code}|${tgt.code}"
    }
}

@Entity(tableName = "glossary")
data class GlossaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val seriesKey: String,
    val source: String,
    val target: String,
)

/**
 * Unused since the per-image path was removed (it mapped image URLs to content hashes). The table stays in the
 * schema so existing installs keep their sites and history: dropping it needs a DB version bump, which is destructive
 * here. Remove it together with the next schema change.
 */
@Entity(tableName = "url_etag")
data class UrlEtagEntry(
    @PrimaryKey val url: String,
    val etag: String?,
    val imageHash: String,
    val seenAt: Long,
)

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val url: String,
    val title: String,
    val host: String,
    val visitedAt: Long,
)
