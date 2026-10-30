package com.smnexstudio.panelglass.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.engine.llm.ClaudeEngine
import com.smnexstudio.panelglass.core.engine.llm.DeepSeekEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.engine.mt.DeepLEngine
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.PapagoEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * How a cloud call fails on the wire, through Retrofit and OkHttp rather than by calling the status mapper directly:
 * every failure reaches the caller typed, and only transient ones are retried, on the same engine.
 * Robolectric (JUnit 4 via vintage): the network-failure path logs through android.util.Log.
 */
@RunWith(RobolectricTestRunner::class)
class HttpFailureWireTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer
    private val ds = MemoryDataStore()
    private val keys = testKeyStore(ds)
    private val settings = testSettings(ds)
    private val req = TranslateRequest(listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "invented line")), Lang.JA, Lang.EN)
    private val ok = """{"candidates":[{"content":{"parts":[{"text":"[{\"i\":0,\"text\":\"Hello\"}]"}]}}]}"""

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun support(readTimeoutMs: Long = 5_000) = HttpEngineSupport(
        OkHttpClient.Builder().readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build(),
        Json { ignoreUnknownKeys = true; isLenient = true },
    )

    private suspend fun gemini(support: HttpEngineSupport = support()): GeminiEngine {
        keys.set(EngineId.GEMINI, "g-key")
        settings.setModel(EngineId.GEMINI, "gemini-flash-latest")
        return GeminiEngine(support, keys, settings).apply { baseUrl = server.url("/v1beta").toString() }
    }

    private suspend fun failureOf(block: suspend () -> Unit): EngineFailure {
        try { block() } catch (e: EngineException) { return e.failure } catch (e: Exception) {
            fail("untyped failure reached the caller: ${e::class.qualifiedName}")
        }
        fail("expected a failure")
        error("unreachable")
    }

    @Test fun aServerThatNeverAnswersIsANetworkFailure() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(ok).setHeadersDelay(3, TimeUnit.SECONDS))
        val f = failureOf { gemini(support(readTimeoutMs = 200)).translate(req) }
        assertEquals(EngineFailure.Network(EngineId.GEMINI), f)
    }

    @Test fun aDroppedConnectionIsANetworkFailure() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(EngineFailure.Network(EngineId.GEMINI), failureOf { gemini().translate(req) })
        server.enqueue(MockResponse().setBody(ok).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertEquals(EngineFailure.Network(EngineId.GEMINI), failureOf { gemini().translate(req) })
    }

    @Test fun rateLimitsCarryTheServersRetryAfter() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("""{"error":{"message":"slow down"}}"""))
        val f = failureOf { gemini().translate(req) }
        assertTrue("got $f", f is EngineFailure.RateLimited)
        assertEquals(3_000L, (f as EngineFailure.RateLimited).retryAfterMs)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"You exceeded your current quota"}}"""))
        assertTrue(failureOf { gemini().translate(req) } is EngineFailure.QuotaExceeded)
    }

    @Test fun overloadAndServerErrorsAreOverloadedWithTheProvidersMessage() = runBlocking<Unit> {
        for (code in listOf(500, 502, 503, 504)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("""{"error":{"message":"invented outage"}}"""))
            val f = failureOf { gemini().translate(req) }
            assertTrue("HTTP $code: $f", f is EngineFailure.Overloaded)
            assertEquals("HTTP $code: invented outage", (f as EngineFailure.Overloaded).reason)
        }
    }

    /** A proxy's HTML page or a cut reply with status 200 is an unusable reply, never an untyped crash. */
    @Test fun aReplyThatIsNotJsonIsATypedFailure() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))
        val f = failureOf { gemini().translate(req) }
        assertEquals(EngineFailure.Malformed(EngineId.GEMINI), f)
    }

    // ---- retries through the registry ------------------------------------------------------------------------

    private suspend fun registry(gemini: GeminiEngine): EngineRegistry {
        val s = support()
        val locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
        settings.setEngine(EngineId.GEMINI)
        return EngineRegistry(
            locals, gemini, com.smnexstudio.panelglass.core.engine.llm.CustomOpenAiEngine(s, keys, settings), ClaudeEngine(s, keys, settings), OpenAiEngine(s, keys, settings),
            DeepLEngine(s, keys), PapagoEngine(s, keys), DeepSeekEngine(s, keys),
            GoogleTranslateEngine(), OpenRouterEngine(s, keys, settings), keys, settings,
        )
    }

    /**
     * An overloaded provider is retried on the same engine and model; the third answer is used. 502, not 503: OkHttp
     * itself silently repeats a 503 that says `Retry-After: 0`, which would hide how often the app retries.
     */
    @Test fun anOverloadedProviderIsRetriedOnTheSameEngine() = runBlocking<Unit> {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(502).setHeader("Retry-After", "0").setBody("""{"error":{"message":"high demand"}}""")) }
        server.enqueue(MockResponse().setBody(ok))
        val out = registry(gemini()).translate(req)
        assertEquals(EngineId.GEMINI, out.engine)
        assertEquals(listOf("Hello"), out.items.map { it.text })
        assertEquals(3, server.requestCount)
        repeat(3) { assertTrue(server.takeRequest().path!!.contains("gemini-flash-latest")) }
    }

    @Test fun retriesAreBoundedThenTheFailureSurfaces() = runBlocking<Unit> {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(502).setHeader("Retry-After", "0").setBody("{}")) }
        val f = failureOf { registry(gemini()).translate(req) }
        assertTrue("got $f", f is EngineFailure.Overloaded)
        assertEquals(1 + EngineRetry.MAX_OVERLOAD_RETRIES, server.requestCount)
    }

    /** A wrong key is not transient: one request, no retry, no other engine. */
    @Test fun aRejectedKeyIsNeverRetried() = runBlocking<Unit> {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"API key not valid"}}""")) }
        assertEquals(EngineFailure.MissingKey(EngineId.GEMINI), failureOf { registry(gemini()).translate(req) })
        assertEquals(1, server.requestCount)
    }
}
