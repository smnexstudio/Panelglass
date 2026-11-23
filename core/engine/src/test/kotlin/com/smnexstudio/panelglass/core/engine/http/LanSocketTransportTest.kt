package com.smnexstudio.panelglass.core.engine.http

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.UnknownHostException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.SocketPolicy

class LanSocketTransportTest {
    @Test fun loopbackJsonPostPreservesPathAndOptionalAuthorization() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}"))
            val reply = LanSocketTransport(1024).post(server.url("/api/v1/chat/completions"), null, "{}".toByteArray())
            assertEquals(200, reply.code)
            assertEquals("{}", reply.body.toString(Charsets.UTF_8))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/chat/completions", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test fun redirectsNeverContactTheirDestination() {
        MockWebServer().use { source -> MockWebServer().use { sink ->
            source.start(); sink.start()
            for (code in listOf(301, 302, 303, 307, 308)) {
                source.enqueue(MockResponse().setResponseCode(code).setHeader("Location", sink.url("/secret")))
                assertEquals(code, LanSocketTransport(1024).post(source.url("/"), "Bearer synthetic", byteArrayOf()).code)
            }
            assertEquals(5, source.requestCount)
            assertEquals(0, sink.requestCount)
        } }
    }

    @Test fun chunkedRepliesAreDecoded() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody("{\"ok\":true}", 3))
            assertEquals("{\"ok\":true}", LanSocketTransport(1024).post(server.url("/"), null, byteArrayOf()).body.toString(Charsets.UTF_8))
        }
    }

    @Test fun declaredAndChunkedBodiesAreCapped() {
        MockWebServer().use { server ->
            server.start()
            for (response in listOf(MockResponse().setBody("x".repeat(2000)), MockResponse().setChunkedBody("x".repeat(2000), 128))) {
                server.enqueue(response)
                assertThrows(ReplyTooLarge::class.java) { LanSocketTransport(1024).post(server.url("/"), null, byteArrayOf()) }
            }
        }
    }

    @Test fun ambiguousFramingIsRejected() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setChunkedBody("{}", 2).addHeader("Content-Length", "2"))
            assertThrows(IOException::class.java) { LanSocketTransport(1024).post(server.url("/"), null, byteArrayOf()) }
        }
    }

    @Test fun duplicateContentLengthsAreRejected() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}").addHeader("Content-Length", "2"))
            assertThrows(IOException::class.java) { LanSocketTransport(1024).post(server.url("/"), null, byteArrayOf()) }
        }
    }

    @Test fun cancellationClosesBlockedSocketPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch { LanSocketTransport(1024).postCancellable(server.url("/"), null, byteArrayOf()) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                }
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally {
                job.cancelAndJoin()
            }
        }
    }

    @Test fun publicIpIsRejectedBeforeConnection() {
        assertThrows(UnknownHostException::class.java) {
            LanSocketTransport(1024).post("http://8.8.8.8/v1".toHttpUrl(), null, byteArrayOf())
        }
    }
}
