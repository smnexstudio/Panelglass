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
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    /**
     * The canonical base address of [raw], or null when it is not a usable server address.
     *
     * HTTPS by default; [allowHttp] permits HTTP only for local/LAN host names and addresses. Credentials in the
     * address are refused: the key belongs in the key field, not in the URL.
     */
    fun normalize(raw: String, allowHttp: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // A space anywhere in an address is a typo or a paste gone wrong, never a valid host.
        if (trimmed.any { it.isWhitespace() }) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != SCHEME_HTTPS && !(scheme == SCHEME_HTTP && allowHttp)) return null
        // A query string or a fragment is not part of a base address.
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        // Credentials in the URL: the key has its own field.
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        // This is an early input check, not a substitute for transport DNS and peer validation.
        if (scheme == SCHEME_HTTP && !isLocalHost(host)) return null

        var path = uri.rawPath.orEmpty()
        // Trailing slashes first, so a pasted `…/chat/completions/` still matches the suffix below.
        path = path.trimEnd('/')
        // The completions address the user pasted is the base plus the path the engine adds itself.
        if (path.endsWith(COMPLETIONS_PATH, ignoreCase = true)) {
            path = path.dropLast(COMPLETIONS_PATH.length).trimEnd('/')
        }
        return scheme + "://" + host + authoritySuffix(uri) + path
    }

    /**
     * The server [raw] points at: its host in lower case, plus the port when the address names one. Null when [raw] is
     * not a usable address. Two addresses with the same host are the same server whatever their paths, so a saved key
     * goes on to the new one without a question; a different host is asked about first (the key sheet).
     */
    fun host(raw: String, allowHttp: Boolean = false): String? {
        val base = normalize(raw, allowHttp) ?: return null
        val uri = URI(base)
        return uri.host.lowercase(Locale.ROOT) + authoritySuffix(uri)
    }

    /**
     * Input precheck for localhost, RFC1918 or loopback IPv4, unique-local or loopback IPv6,
     * excluding link-local, CGNAT, unspecified and multicast addresses. Also accepts
     * a `.local`/`.lan`/`.home.arpa`/`.internal` name, or a single-label name such as `mypc`.
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT).trimEnd('.')
        if (h.isEmpty()) return false
        if (':' in h) {
            if ('%' in h) return false
            // A colon identifies an IP literal: parsing here never resolves a hostname.
            val bytes = runCatching { java.net.InetAddress.getByName(h).address }.getOrNull() ?: return false
            if (bytes.size != 16) return false
            return (bytes[0].toInt() and 254) == 252 ||
                (bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1)
        }
        val parts = h.split('.')
        if (parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            if (parts.size != 4 || parts.any { it.length > 3 || it.toInt() > 255 }) return false
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        return '.' !in h
    }

    /** The port, when the address names one. */
    private fun authoritySuffix(uri: URI): String = if (uri.port != -1) ":${uri.port}" else ""

    /** The request URL for a normalized base: the base plus the completions path. */
    fun completionsUrl(base: String): String = base.trimEnd('/') + COMPLETIONS_PATH
}
