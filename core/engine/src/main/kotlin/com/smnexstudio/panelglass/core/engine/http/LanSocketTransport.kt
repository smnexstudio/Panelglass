package com.smnexstudio.panelglass.core.engine.http

import okhttp3.HttpUrl
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Narrow HTTP/1.1 JSON POST transport; never a browser proxy or a general URL loader. */
internal class LanSocketTransport(
    private val maxReplyBytes: Long,
    private val dns: PrivateLanDns = PrivateLanDns(),
) {
    data class Reply(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    /** SocketChannel-backed blocking operations close when runInterruptible cancels the IO thread. */
    suspend fun postCancellable(url: HttpUrl, authorization: String?, body: ByteArray): Reply = try {
        withTimeout(120_000) { runInterruptible(Dispatchers.IO) { post(url, authorization, body) } }
    } catch (e: IOException) {
        // An interrupted channel throws IOException; cancelled requests must stay cancellation.
        currentCoroutineContext().ensureActive()
        throw e
    }

    /** Blocking entry point for protocol tests; production integration must use postCancellable. */
    fun post(url: HttpUrl, authorization: String?, body: ByteArray): Reply {
        if (url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null)
            throw IOException("LAN HTTP URL required")
        if (authorization?.any { it.code !in 32..126 } == true) throw IOException("Invalid authorization")
        if (body.size > MAX_REQUEST_BYTES) throw IOException("Request too large")
        val addresses = dns.lookup(url.host)
        // Connect to the validated address directly: never resolve the hostname a second time.
        return SocketChannel.open().socket().use { socket ->
            socket.soTimeout = 60_000
            socket.connect(InetSocketAddress(addresses.first(), url.port), 15_000)
            if (!PrivateLanDns.allowed(socket.inetAddress)) throw IOException("Private LAN peer required")
            val host = if (':' in url.host) "[${url.host}]" else url.host
            val target = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            val head = buildString {
                append("POST $target HTTP/1.1\r\nHost: $host:${url.port}\r\n")
                append("Content-Type: application/json; charset=utf-8\r\nAccept: application/json\r\n")
                append("Accept-Encoding: identity\r\nConnection: close\r\nContent-Length: ${body.size}\r\n")
                if (authorization != null) append("Authorization: $authorization\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
            readReply(BufferedInputStream(socket.getInputStream()))
        }
    }

    private fun readReply(input: BufferedInputStream): Reply {
        var budget = MAX_HEADER_BYTES
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                if (--budget < 0) throw IOException("HTTP metadata too large")
                val b = input.read()
                if (b < 0) throw EOFException("Truncated HTTP metadata")
                if (b == 13) {
                    if (--budget < 0 || input.read() != 10) throw IOException("Invalid HTTP line")
                    return out.toString("US-ASCII")
                }
                if (b == 10 || b !in 32..126) throw IOException("Invalid HTTP metadata")
                out.write(b)
            }
        }
        val status = line().split(' ', limit = 3)
        if (status.size < 2 || status[0] !in listOf("HTTP/1.0", "HTTP/1.1")) throw IOException("Invalid HTTP status")
        val code = status[1].toIntOrNull()?.takeIf { it in 200..599 } ?: throw IOException("Unsupported HTTP status")
        val headers = linkedMapOf<String, String>()
        while (true) {
            val text = line()
            if (text.isEmpty()) break
            val colon = text.indexOf(':')
            if (colon <= 0) throw IOException("Invalid HTTP header")
            val name = text.substring(0, colon).lowercase(java.util.Locale.ROOT)
            if (!name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) throw IOException("Invalid HTTP header")
            if (headers.put(name, text.substring(colon + 1).trim()) != null) throw IOException("Duplicate HTTP header")
        }
        // Never follow or consume a redirect body; no credentials can reach Location.
        if (code in 300..399) return Reply(code, headers, byteArrayOf())
        if (headers["content-encoding"]?.let { !it.equals("identity", true) } == true) throw IOException("Unsupported content encoding")
        val length = headers["content-length"]?.let { value ->
            if (value.isEmpty() || value.any { it !in '0'..'9' }) throw IOException("Invalid content length")
            value.toLongOrNull() ?: throw IOException("Invalid content length")
        }
        val transfer = headers["transfer-encoding"]
        if (transfer != null && (length != null || !transfer.equals("chunked", true))) throw IOException("Ambiguous HTTP framing")
        val out = ByteArrayOutputStream()
        fun copy(count: Long) {
            if (count > maxReplyBytes - out.size()) throw ReplyTooLarge()
            var remaining = count
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw EOFException("Truncated HTTP body")
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
        when {
            code == 204 || code == 304 -> Unit
            transfer != null -> {
                while (true) {
                    val sizeText = line().substringBefore(';')
                    if (sizeText.isEmpty() || sizeText.any { it !in "0123456789abcdefABCDEF" }) throw IOException("Invalid chunk size")
                    val size = sizeText.toLongOrNull(16) ?: throw IOException("Invalid chunk size")
                    if (size == 0L) {
                        while (line().isNotEmpty()) { /* Discard bounded trailers; never use as headers. */ }
                        break
                    }
                    copy(size)
                    if (line().isNotEmpty()) throw IOException("Invalid chunk terminator")
                }
            }
            length != null -> copy(length)
            else -> {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > maxReplyBytes - out.size()) throw ReplyTooLarge()
                    out.write(buffer, 0, n)
                }
            }
        }
        return Reply(code, headers, out.toByteArray())
    }

    companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    }
}
