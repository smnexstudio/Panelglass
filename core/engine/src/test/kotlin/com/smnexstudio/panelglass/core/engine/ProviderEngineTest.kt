package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Wire-level checks for the HTTP providers against a MockWebServer. */
class ProviderEngineTest {
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

    @Test
    fun openRouterSendsUsersModelAndBearerKey() = runBlocking {
        keys.set(EngineId.OPENROUTER, "or-secret")
        settings.setModel(EngineId.OPENROUTER, "anthropic/claude-3.5-haiku")
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"[{\"i\":0,\"text\":\"Hello\"},{\"i\":1,\"text\":\"Thanks\"}]"}}]}"""))
        val engine = OpenRouterEngine(testSupport(), keys, settings).apply { endpoint = server.url("/api/v1/chat/completions").toString() }

        val out = engine.translate(req)

        assertEquals(listOf("Hello", "Thanks"), out.sortedBy { it.i }.map { it.text })
        val sent = server.takeRequest()
        assertEquals("Bearer or-secret", sent.getHeader("Authorization"))
        assertEquals("Panelglass", sent.getHeader("X-Title"))
        val body = Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("anthropic/claude-3.5-haiku", body["model"]!!.jsonPrimitive.content)
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun openRouterWithoutModelIsUnavailable() = runBlocking {
        keys.set(EngineId.OPENROUTER, "or-secret")
        settings.setModel(EngineId.OPENROUTER, "")
        val engine = OpenRouterEngine(testSupport(), keys, settings).apply { endpoint = server.url("/x").toString() }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun openAiSendsTheUsersModelChoice() = runBlocking {
        keys.set(EngineId.OPENAI, "sk-test")
        settings.setModel(EngineId.OPENAI, "gpt-4.1")
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"[{\"i\":0,\"text\":\"Hello\"},{\"i\":1,\"text\":\"Thanks\"}]"}}]}"""))
        val engine = OpenAiEngine(testSupport(), keys, settings).apply { endpoint = server.url("/v1/chat/completions").toString() }

        engine.translate(req)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gpt-4.1", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingKeyNeverHitsTheNetwork() = runBlocking {
        settings.setModel(EngineId.OPENROUTER, "openai/gpt-4o-mini")
        val engine = OpenRouterEngine(testSupport(), keys, settings).apply { endpoint = server.url("/x").toString() }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.MissingKey)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun geminiResolvesADisplayNameAndSendsGemmaWithoutGeminiOnlyOptions() = runBlocking {
        keys.set(EngineId.GEMINI, "g-key")
        settings.setModel(EngineId.GEMINI, "Gemma 4 26B")
        server.enqueue(MockResponse().setBody("""{"models":[
            {"name":"models/gemini-flash-latest","displayName":"Gemini Flash Latest","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemma-4-26b-a4b-it","displayName":"Gemma 4 26B A4B IT","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","displayName":"Text Embedding 004","supportedGenerationMethods":["embedContent"]}]}"""))
        val reply = """{"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"```json\n[{\"i\":0,\"text\":\"Hello\"},{\"i\":1,\"text\":\"Thanks\"}]\n```"}]}}]}"""
        server.enqueue(MockResponse().setBody(reply))
        server.enqueue(MockResponse().setBody(reply))
        val engine = GeminiEngine(testSupport(), keys, settings).apply { baseUrl = server.url("/v1beta").toString() }

        val out = engine.translate(req)

        assertEquals(listOf("Hello", "Thanks"), out.sortedBy { it.i }.map { it.text })
        val list = server.takeRequest()
        assertTrue(list.path!!.startsWith("/v1beta/models?"))
        assertEquals("g-key", list.getHeader("x-goog-api-key"))
        val gen = server.takeRequest()
        assertEquals("/v1beta/models/gemma-4-26b-a4b-it:generateContent", gen.path)
        val body = Json.parseToJsonElement(gen.body.readUtf8()).jsonObject
        assertTrue("system_instruction" !in body)
        val cfg = body["generationConfig"]!!.jsonObject
        assertTrue("responseMimeType" !in cfg && "thinkingConfig" !in cfg)
        val firstText = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(firstText.contains("こんにちは") && firstText.length > 200) // the system prompt rides in the first turn

        // Resolved once per run: the next call goes straight to generateContent.
        engine.translate(req)
        assertEquals("/v1beta/models/gemma-4-26b-a4b-it:generateContent", server.takeRequest().path)
    }

    @Test
    fun geminiStepsDownPastAnOptionTheModelRefusesAndRemembersIt() = runBlocking {
        keys.set(EngineId.GEMINI, "g-key")
        settings.setModel(EngineId.GEMINI, "gemini-3.5-flash-lite")
        val invalid = """{"error":{"code":400,"message":"Request contains an invalid argument.","status":"INVALID_ARGUMENT"}}"""
        val ok = """{"candidates":[{"content":{"parts":[{"text":"[{\"i\":0,\"text\":\"Hello\"},{\"i\":1,\"text\":\"Thanks\"}]"}]}}]}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(invalid))   // thinkingBudget refused
        server.enqueue(MockResponse().setResponseCode(400).setBody(invalid))   // thinkingLevel refused
        server.enqueue(MockResponse().setBody(ok))                             // JSON mode, no thinking config
        server.enqueue(MockResponse().setBody(ok))
        val engine = GeminiEngine(testSupport(), keys, settings).apply { baseUrl = server.url("/v1beta").toString() }

        assertEquals(listOf("Hello", "Thanks"), engine.translate(req).sortedBy { it.i }.map { it.text })
        val cfgs = List(3) { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject }
        assertEquals("0", cfgs[0]["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.content)
        assertEquals("minimal", cfgs[1]["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertTrue("thinkingConfig" !in cfgs[2] && "responseMimeType" in cfgs[2])

        // The level that worked is reused: one request, straight to it.
        engine.translate(req)
        assertTrue("thinkingConfig" !in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject)
        assertEquals(4, server.requestCount)

        // A new run (fresh engine, same settings store) starts from the saved level: no refused call after launch.
        assertEquals("JSON_ONLY", settings.requestProfile(EngineId.GEMINI, "gemini-3.5-flash-lite"))
        server.enqueue(MockResponse().setBody(ok))
        GeminiEngine(testSupport(), keys, settings).apply { baseUrl = server.url("/v1beta").toString() }.translate(req)
        val cfg = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertTrue("thinkingConfig" !in cfg && "responseMimeType" in cfg)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun geminiModelListKeepsTextModelsGeminiFirst() = runBlocking {
        keys.set(EngineId.GEMINI, "g-key")
        server.enqueue(MockResponse().setBody("""{"models":[
            {"name":"models/gemma-4-26b-a4b-it","displayName":"Gemma 4 26B","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-3.5-flash-lite","displayName":"Gemini 3.5 Flash-Lite","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash-image","displayName":"Nano Banana","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-2.5-flash-preview-tts","displayName":"TTS","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}]}"""))
        val engine = GeminiEngine(testSupport(), keys, settings).apply { baseUrl = server.url("/v1beta").toString() }
        assertEquals(listOf("gemini-3.5-flash-lite", "gemma-4-26b-a4b-it"), engine.listModels().map { it.id })
    }

    @Test
    fun geminiIdsPassThroughAndUnknownNamesSaySo() = runBlocking {
        assertEquals("gemma-4-26b-a4b-it", GeminiEngine.matchModel("gemma 4 26b a4b it", listOf("gemma-4-26b-a4b-it" to "Gemma 4 26B A4B IT")))
        assertEquals("gemma-4-26b-a4b-it", GeminiEngine.matchModel("Gemma 4 26B", listOf("gemma-4-26b-a4b-it" to "x", "gemma-4-26b-a4b-it-extended" to "y")))
        assertEquals(null, GeminiEngine.matchModel("Gemma 9", listOf("gemma-4-26b-a4b-it" to "Gemma 4 26B")))

        keys.set(EngineId.GEMINI, "g-key")
        settings.setModel(EngineId.GEMINI, "Gemma 9 Ultra")
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/gemma-4-26b-a4b-it","displayName":"Gemma 4 26B A4B IT"}]}"""))
        val engine = GeminiEngine(testSupport(), keys, settings).apply { baseUrl = server.url("/v1beta").toString() }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        val f = e.failure as EngineFailure.Unavailable
        assertTrue(f.reason.contains("Gemma 9 Ultra") && f.reason.contains("gemma-4-26b-a4b-it"))
    }

    @Test
    fun unauthorizedMapsToMissingKey() = runBlocking {
        keys.set(EngineId.OPENAI, "bad")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        val engine = OpenAiEngine(testSupport(), keys, settings).apply { endpoint = server.url("/x").toString() }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(req) } }
        assertTrue(e.failure is EngineFailure.MissingKey)
    }
}
