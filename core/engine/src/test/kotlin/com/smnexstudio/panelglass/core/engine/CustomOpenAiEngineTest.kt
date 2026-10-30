package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.CustomOpenAiEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Wire-level checks for the "Custom AI" engine against a MockWebServer. */
class CustomOpenAiEngineTest {
    private lateinit var server: MockWebServer
    private val ds = MemoryDataStore()
    private val keys = testKeyStore(ds)
    private val settings = testSettings(ds)
    private val items = listOf(
        RegionItem(0, "img", RegionKind.ENCLOSED, "こんにちは"),
        RegionItem(1, "img", RegionKind.ENCLOSED, "ありがとう"),
    )
    private val req = TranslateRequest(items, Lang.JA, Lang.EN)

    @BeforeEach fun start() { server = MockWebServer(); server.start() }
    @AfterEach fun stop() { server.shutdown() }

    /** The engine pointed at the test server; the address is what the user would type. */
    private suspend fun engine(base: String = server.url("/v1").toString()): CustomOpenAiEngine {
        settings.setCustomBaseUrl(base)
        settings.setModel(EngineId.CUSTOM, "my-model")
        return CustomOpenAiEngine(testSupport(), keys, settings).apply { allowHttpForTests = true }
    }

    private val ok = """{"choices":[{"message":{"content":"[{\"i\":0,\"text\":\"Hello\"},{\"i\":1,\"text\":\"Thanks\"}]"}}]}"""

    @Test
    fun theUsersModelAndKeyAreSentToTheConfiguredServer() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setBody(ok))
        val engine = engine()

        val out = engine.translate(req)

        assertEquals(listOf("Hello", "Thanks"), out.sortedBy { it.i }.map { it.text })
        val sent = server.takeRequest()
        assertEquals("Bearer sk-custom", sent.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", sent.path)
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("my-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun theRequestCarriesTextOnlyAndNoImage() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setBody(ok))
        val engine = engine()

        engine.translate(req)

        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("image_url"), raw)
        assertFalse(raw.contains("base64"), raw)
        // Every message is a plain string, never a content array with an image part.
        for (m in Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray) {
            assertTrue(m.jsonObject["content"] is kotlinx.serialization.json.JsonPrimitive)
        }
    }

    @Test
    fun thePathIsNeverAppendedTwiceWhenTheUserPastesTheFullAddress() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setBody(ok))
        val engine = engine(server.url("/v1/chat/completions").toString())

        engine.translate(req)

        assertEquals("/v1/chat/completions", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aVersionPathTheUserWroteIsKeptAsItIs() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setBody(ok))
        val engine = engine(server.url("/api/openai/v2").toString())

        engine.translate(req)

        assertEquals("/api/openai/v2/chat/completions", server.takeRequest().path)
    }

    @Test
    fun withoutAModelTheEngineIsUnavailableAndHitsNoNetwork() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        settings.setCustomBaseUrl(server.url("/v1").toString())
        settings.setModel(EngineId.CUSTOM, "")
        val engine = CustomOpenAiEngine(testSupport(), keys, settings).apply { allowHttpForTests = true }

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withoutAKeyTheEngineIsMissingKeyAndHitsNoNetwork() = runBlocking {
        val engine = engine()

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.MissingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun withoutAServerAddressTheEngineIsUnavailableAndHitsNoNetwork() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        settings.setModel(EngineId.CUSTOM, "my-model")
        settings.setCustomBaseUrl("")
        val engine = CustomOpenAiEngine(testSupport(), keys, settings).apply { allowHttpForTests = true }

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPlainHttpAddressIsRefusedInProduction() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        settings.setCustomBaseUrl("http://192.168.1.5:1234/v1")
        settings.setModel(EngineId.CUSTOM, "my-model")
        // The test switch is off: production refuses a cleartext address.
        val engine = CustomOpenAiEngine(testSupport(), keys, settings)

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aServerThatRefusesTheTemperatureIsAskedOnceMoreWithoutIt() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: 'temperature'"}}"""))
        server.enqueue(MockResponse().setBody(ok))
        val engine = engine()

        val out = engine.translate(req)

        assertEquals(listOf("Hello", "Thanks"), out.sortedBy { it.i }.map { it.text })
        assertEquals(2, server.requestCount)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("temperature" in first)
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("temperature" in second)
        // The rest of the request is the same.
        assertEquals("my-model", second["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun aServerThatRefusesTheRequestTwiceIsUnavailable() = runBlocking {
        keys.set(EngineId.CUSTOM, "sk-custom")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: 'temperature'"}}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unsupported parameter: 'temperature'"}}"""))
        val engine = engine()

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anUnauthorizedAnswerIsMissingKey() = runBlocking {
        keys.set(EngineId.CUSTOM, "bad")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        val engine = engine()

        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.MissingKey)
    }

    @Test
    fun theEngineIsTextOnlyAndDoesNotReadCrops() = runBlocking {
        assertFalse(EngineId.CUSTOM.readsCrops)
        assertFalse(engine().acceptsImages)
        assertTrue(EngineId.CUSTOM.needsKey)
        assertTrue(EngineId.CUSTOM.hasModel)
    }
}
