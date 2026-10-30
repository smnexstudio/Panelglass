package com.smnexstudio.panelglass.core.engine.local

import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Hugging Face catalogue: which LiteRT-LM models exist and where to fetch them.
 *
 * The API is used anonymously: a gated repository answers 401/403 and the caller says so. Only the `litert-community`
 * author is searched, but any repository can be listed by name, so a model published elsewhere is reachable too.
 *
 * Downloads are pinned to the commit the listing was taken from, so the file's SHA-256 stays valid.
 */
@Singleton
class HfCatalog @Inject constructor(
    private val support: HttpEngineSupport,
) {
    /** The API host; a `var` so tests can point it at a MockWebServer. */
    var apiBase = "https://huggingface.co"

    /** A repository in the catalogue. */
    data class HfRepo(val id: String, val downloads: Long, val gated: Boolean)

    /** One file in a repository. */
    data class HfFile(val path: String, val bytes: Long, val sha256: String)

    /** A repository's files, with the commit they were listed at. */
    data class HfListing(val repoId: String, val commit: String, val files: List<HfFile>)

    /** The repositories the author search returns. */
    suspend fun search(query: String): List<HfRepo> {
        val q = query.trim()
        val url = buildString {
            append(apiBase).append("/api/models?author=").append(AUTHOR)
            if (q.isNotEmpty()) append("&search=").append(q.urlEncode())
            append("&sort=downloads&direction=-1&limit=40")
        }
        val arr = getArray(url)
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            HfRepo(id, o["downloads"]?.jsonPrimitive?.longOrNull ?: 0L, isGated(o["gated"]))
        }
    }

    /** The `.litertlm` files of one repository, at the commit the listing was taken from. */
    suspend fun listing(repoId: String): HfListing {
        if (!looksLikeRepoId(repoId)) throw IllegalArgumentException("Not a repository id")
        val commit = getObject("$apiBase/api/models/$repoId")["sha"]?.jsonPrimitive?.content.orEmpty()
        val arr = getArray("$apiBase/api/models/$repoId/tree/main")
        val files = arr.mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.content != "file") return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!path.endsWith(EXT)) return@mapNotNull null
            // The LFS object id is the file's SHA-256; without it the file cannot be verified after a download.
            val oid = o["lfs"]?.jsonObject?.get("oid")?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!SHA256.matches(oid)) return@mapNotNull null
            HfFile(path, o["size"]?.jsonPrimitive?.longOrNull ?: 0L, oid.lowercase())
        }
        return HfListing(repoId, commit, files)
    }

    /** The download address of [f], pinned to the listing's commit. */
    fun downloadUrl(l: HfListing, f: HfFile): String =
        "$apiBase/${l.repoId}/resolve/${l.commit}/${f.path.split('/').joinToString("/") { it.urlEncode() }}"

    /** Whether [s] looks like `owner/name`: the shape the browse box accepts to list a repository directly. */
    fun looksLikeRepoId(s: String): Boolean = REPO_ID.matches(s.trim())

    /** Whether the address is one we are willing to download from. */
    fun isHubUrl(url: String): Boolean = url.startsWith(HUB_PREFIX)

    /** A gated repository is one the API marks with a non-empty [gated] value. */
    private fun isGated(el: JsonElement?): Boolean = when (el) {
        null -> false
        is kotlinx.serialization.json.JsonPrimitive -> el.content.isNotEmpty() && el.content != "false"
        else -> false
    }

    private suspend fun getArray(url: String): List<JsonElement> =
        support.get(EngineId.LOCAL_USER, url, emptyMap()).jsonArray

    private suspend fun getObject(url: String) = support.get(EngineId.LOCAL_USER, url, emptyMap()).jsonObject

    private companion object {
        const val AUTHOR = "litert-community"
        const val EXT = ".litertlm"
        const val HUB_PREFIX = "https://huggingface.co/"
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val REPO_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

/** A path segment for a URL: everything outside the unreserved set is escaped. */
internal fun String.urlEncode(): String = buildString {
    for (b in this@urlEncode.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt().toChar()
        if (c.isLetterOrDigit() || c in "-_.~") append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
    }
}
