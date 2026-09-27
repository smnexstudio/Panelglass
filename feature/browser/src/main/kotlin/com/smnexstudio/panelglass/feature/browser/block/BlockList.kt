package com.smnexstudio.panelglass.feature.browser.block

import android.net.Uri

/**
 * Host-suffix matcher for ad/tracker domains. `blocks` runs on the WebView's interceptor thread for
 * every subresource, so it is allocation-free apart from the substring walk, and never touches
 * Room, DataStore or the network.
 *
 * [allowed] holds the lists' own exceptions (`@@||host^`): a host, or any parent of it, listed there is never
 * blocked, however many other lists name it.
 */
class BlockList(hosts: Set<String>, private val allowed: Set<String> = emptySet()) {
    private val hosts: Set<String> = hosts

    val size: Int get() = hosts.size

    fun blocks(url: Uri): Boolean { return blocksHost(url.host ?: return false) }

    fun blocksHost(host: String): Boolean {
        val h = host.lowercase()
        return matches(h, hosts) && (allowed.isEmpty() || !matches(h, allowed))
    }

    private fun matches(host: String, set: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.'); if (dot < 0) return false
            h = h.substring(dot + 1)          // a.b.c → b.c → c
        }
    }

    /** One list made of this and [other]: hosts and exceptions are both unions. */
    operator fun plus(other: BlockList): BlockList = BlockList(hosts + other.hosts, allowed + other.allowed)

    /** Back to AdGuard syntax (`||host^`, `@@||host^`), which [parse] reads: how the merged list is cached. */
    fun write(out: Appendable) {
        for (h in hosts) out.append("||").append(h).append("^\n")
        for (h in allowed) out.append("@@||").append(h).append("^\n")
    }

    companion object {
        val EMPTY = BlockList(emptySet())

        /** Rule options that do not narrow where a host rule applies (for this reader's purposes). */
        private val PLAIN_OPTIONS = setOf("important", "third-party", "3p", "popup", "all", "document", "doc")

        /**
         * Parses a hosts-file (`0.0.0.0 host`) or AdGuard/ABP-style (`||host^`, exceptions `@@||host^`) list.
         * Anything else is ignored.
         */
        fun parse(text: CharSequence): BlockList = parseLines(text.lineSequence())

        fun parse(reader: java.io.Reader): BlockList = parseLines(reader.buffered().lineSequence())

        private fun parseLines(lines: Sequence<String>): BlockList {
            val out = HashSet<String>(65_536)
            val allow = HashSet<String>()
            for (rawLine in lines) {
                if (rawLine.isEmpty()) continue
                val first = rawLine.firstOrNull { !it.isWhitespace() } ?: continue
                if (first == '#' || first == '!') continue

                val line = rawLine.trim()
                val exception = line.startsWith("@@||")
                if (exception || line.startsWith("||")) {
                    val start = if (exception) 4 else 2
                    val end = line.indexOf('^', start).let { if (it < 0) line.length else it }
                    val host = line.substring(start, end)
                    // Rules scoped by options (`$domain=`, `$badfilter`…) only apply in some contexts: skip them
                    // rather than block or allow a host everywhere.
                    val options = line.substring(end).substringAfter('$', "")
                    if (options.isNotEmpty() && options.split(',').any { it !in PLAIN_OPTIONS }) continue
                    if (isHost(host)) (if (exception) allow else out) += host.lowercase()
                    continue
                }
                if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    val rest = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').trim()
                    if (isHost(rest) && rest != "0.0.0.0" && rest != "localhost" && !rest.endsWith(".localdomain")) out += rest.lowercase()
                }
            }
            return BlockList(out, allow)
        }

        private fun isHost(s: String): Boolean =
            s.isNotEmpty() && s.length < 254 && s.indexOf('.') > 0 && s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }
}
