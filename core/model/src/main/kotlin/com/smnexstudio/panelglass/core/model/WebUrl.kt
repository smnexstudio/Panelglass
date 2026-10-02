package com.smnexstudio.panelglass.core.model

/** The app browses over https only: every URL entering it (typed, saved, from an intent or a link) goes through here. */
object WebUrl {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * [input] as an https URL: `https://` kept, `http://` upgraded, a bare `host.tld/…` given `https://`. Anything
     * else (`javascript:`, `file:`, `content:`, plain words) is null.
     */
    fun https(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val lower = t.lowercase()
        return when {
            lower.startsWith("https://") -> t.takeIf { it.length > 8 }
            lower.startsWith("http://") -> t.substring(7).takeIf { it.isNotEmpty() }?.let { "https://$it" }
            "://" in t -> null
            // "javascript:x.y" has a dot too; "host.tld:8080" is a host with a port, not a scheme.
            SCHEME.find(t)?.let { m -> !t.getOrElse(m.range.last + 1) { ' ' }.isDigit() } == true -> null
            '.' in t -> "https://$t"
            else -> null
        }
    }
}
