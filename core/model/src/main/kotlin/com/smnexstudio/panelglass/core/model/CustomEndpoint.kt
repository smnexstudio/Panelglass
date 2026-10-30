package com.smnexstudio.panelglass.core.model

import java.net.URI
import java.util.Locale

/**
 * The base address of the user's own OpenAI-compatible server (the "Custom AI" engine).
 *
 * The user types what their provider's documentation shows: either the base (`https://api.example.com/v1`) or the
 * complete completions address (`https://api.example.com/v1/chat/completions`). Both mean the same server, so the
 * stored form is the base: the engine appends `/chat/completions` itself. No version segment is ever added — a server
 * that serves `/api/v1` or nothing but `/` is called exactly as the user wrote it.
 */
object CustomEndpoint {
    /** The path the OpenAI-compatible request goes to, appended to the normalized base. */
    const val COMPLETIONS_PATH = "/chat/completions"

    private const val SCHEME_HTTPS = "https"
    private const val SCHEME_HTTP = "http"

    /**
     * The canonical base address of [raw], or null when it is not a usable server address.
     *
     * HTTPS only unless [allowHttp] is set (tests point the engine at a local MockWebServer). Credentials in the
     * address are refused: the key belongs in the key field, not in the URL.
     */
    fun normalize(raw: String, allowHttp: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // A space anywhere in an address is a typo or a paste gone wrong, never a valid host.
        if (trimmed.any { it.isWhitespace() }) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != SCHEME_HTTPS && !(allowHttp && scheme == SCHEME_HTTP)) return null
        // A query string or a fragment is not part of a base address.
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        // Credentials in the URL: the key has its own field.
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null

        var path = uri.rawPath.orEmpty()
        // Trailing slashes first, so a pasted `…/chat/completions/` still matches the suffix below.
        path = path.trimEnd('/')
        // The completions address the user pasted is the base plus the path the engine adds itself.
        if (path.endsWith(COMPLETIONS_PATH, ignoreCase = true)) {
            path = path.dropLast(COMPLETIONS_PATH.length).trimEnd('/')
        }
        return scheme + "://" + host + authoritySuffix(uri) + path
    }

    /** The port, when the address names one. */
    private fun authoritySuffix(uri: URI): String = if (uri.port != -1) ":${uri.port}" else ""

    /** The request URL for a normalized base: the base plus the completions path. */
    fun completionsUrl(base: String): String = base.trimEnd('/') + COMPLETIONS_PATH
}
