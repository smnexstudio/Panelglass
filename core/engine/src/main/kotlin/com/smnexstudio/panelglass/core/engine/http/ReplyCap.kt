package com.smnexstudio.panelglass.core.engine.http

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** A provider's reply, or its error page, was larger than [ReplyCap] allows. */
class ReplyTooLarge : IOException("reply too large")

/**
 * Stops reading a reply past [maxBytes].
 *
 * Retrofit reads a whole reply into memory, and the user's own server (Custom AI) is not one we vouch for: a broken or
 * hostile one could stream gigabytes and take the app down with it. A declared length over the cap is refused before
 * any of it is read; a reply without one is cut as soon as it passes the cap.
 */
class ReplyCap(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val length = body.contentLength()
        if (length > maxBytes) {
            response.close()
            throw ReplyTooLarge()
        }
        val capped = object : ForwardingSource(body.source()) {
            private var read = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) {
                    read += n
                    if (read > maxBytes) throw ReplyTooLarge()
                }
                return n
            }
        }
        return response.newBuilder().body(capped.buffer().asResponseBody(body.contentType(), length)).build()
    }
}
