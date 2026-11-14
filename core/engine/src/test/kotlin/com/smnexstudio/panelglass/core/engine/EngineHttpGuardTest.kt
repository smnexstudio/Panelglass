package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The guards on every engine call (`HttpEngineSupport`): redirects are not followed, plain HTTP is refused, and a reply
 * past the cap is cut. Robolectric (JUnit 4 via vintage): the failure path logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
class EngineHttpGuardTest {
    private lateinit var server: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val body = buildJsonObject { }

    @Before fun start() {
        server = MockWebServer().apply { start() }
        elsewhere = MockWebServer().apply { start() }
    }

    @After fun stop() {
        server.shutdown()
        elsewhere.shutdown()
    }

    private fun support(httpsOnly: Boolean = false, cap: Long = HttpEngineSupport.MAX_REPLY_BYTES) =
        HttpEngineSupport(OkHttpClient(), json, httpsOnly, cap)

    private fun failureOf(block: suspend () -> Unit): EngineFailure = runBlocking {
        try {
            block(); fail("expected a failure"); error("unreachable")
        } catch (e: EngineException) {
            e.failure
        }
    }

    @Test fun aRedirectIsNotFollowedAndTheRequestNeverReachesTheOtherHost() {
        for (code in listOf(301, 302, 307, 308)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/v1/chat/completions")))
            val f = failureOf { support().post(EngineId.CUSTOM, server.url("/v1/chat/completions").toString(), mapOf("Authorization" to "Bearer k"), body) }
            assertTrue("$code: $f", f is EngineFailure.Unavailable && f.reason.startsWith("HTTP $code"))
        }
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun plainHttpIsRefusedBeforeAnythingIsSent() {
        server.enqueue(MockResponse().setBody("{}"))
        val f = failureOf { support(httpsOnly = true).post(EngineId.CUSTOM, server.url("/v1/chat/completions").toString(), emptyMap(), body) }
        assertTrue(f is EngineFailure.Network)
        assertEquals(0, server.requestCount)
    }

    @Test fun aReplyDeclaredLargerThanTheCapIsMalformed() {
        server.enqueue(MockResponse().setBody("{\"x\":\"" + "a".repeat(4_000) + "\"}"))
        val f = failureOf { support(cap = 1_024).post(EngineId.CUSTOM, server.url("/x").toString(), emptyMap(), body) }
        assertTrue(f is EngineFailure.Malformed)
    }

    @Test fun aChunkedReplyIsCutOnceItPassesTheCap() {
        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8("{\"x\":\"" + "a".repeat(4_000) + "\"}"), 256))
        val f = failureOf { support(cap = 1_024).post(EngineId.CUSTOM, server.url("/x").toString(), emptyMap(), body) }
        assertTrue(f is EngineFailure.Malformed)
    }

    @Test fun anOversizedErrorPageIsCutToo() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html>" + "a".repeat(4_000) + "</html>"))
        val f = failureOf { support(cap = 1_024).post(EngineId.CUSTOM, server.url("/x").toString(), emptyMap(), body) }
        assertTrue(f is EngineFailure.Malformed)
    }

    @Test fun aReplyUnderTheCapIsReadAsBefore() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        val reply = support(cap = 1_024).post(EngineId.CUSTOM, server.url("/x").toString(), emptyMap(), body)
        assertEquals("{\"ok\":true}", reply.toString())
    }
}
