package com.smnexstudio.panelglass.core.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.repo.SfxCacheRepository
import com.smnexstudio.panelglass.core.engine.llm.ClaudeEngine
import com.smnexstudio.panelglass.core.engine.llm.DeepSeekEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
import com.smnexstudio.panelglass.core.engine.local.LlmRunner
import com.smnexstudio.panelglass.core.engine.local.ModelStore
import com.smnexstudio.panelglass.core.engine.local.ChatPrompt
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.LocalLlmEngine
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.engine.mt.DeepLEngine
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.PapagoEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TextRegion
import com.smnexstudio.panelglass.core.model.TranslateConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric (JUnit 4 via vintage): the SFX tiers need a Context (dictionary asset) and a Room DB (cache). */
@RunWith(RobolectricTestRunner::class)
class BatchingTranslatorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ds = MemoryDataStore()
    private val keys = testKeyStore(ds)
    private val settings = testSettings(ds)
    private val support = testSupport()
    private val db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
    private val locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
    private val qwen = locals[LocalModel.QWEN15]
    private val gemini = GeminiEngine(support, keys, settings)
    private val registry = EngineRegistry(
        locals, gemini, ClaudeEngine(support, keys, settings), OpenAiEngine(support, keys, settings),
        DeepLEngine(support, keys), PapagoEngine(support, keys), DeepSeekEngine(support, keys),
        GoogleTranslateEngine(), OpenRouterEngine(support, keys, settings), keys, settings,
    )
    private val translator = BatchingTranslator(registry, SfxDictionary(context), SfxCacheRepository(db))

    @After fun tearDown() = db.close()

    private fun region(text: String) = TextRegion(bbox = IntRect(0, 0, 100, 40), kind = RegionKind.ENCLOSED, text = text)
    private fun job(imageId: String, vararg texts: String) =
        TranslateJob(imageId, TranslateConfig(Lang.JA, Lang.EN, EngineId.QWEN15_LOCAL), texts.map { region(it) })

    /** Answers every item in the prompt with `T:<text>`, whatever the batch shape, so batches may run in any order. */
    private class EchoRunner : LlmRunner {
        val prompts = ArrayList<String>()
        private val item = Regex("(?m)^(\\d+): (?:\\(SFX\\) )?(.*)$")
        override suspend fun complete(chat: ChatPrompt): String {
            synchronized(prompts) { prompts += chat.system + "\n" + chat.user }
            return item.findAll(chat.user).joinToString("\n") { m -> "${m.groupValues[1]}: T:${m.groupValues[2]}" }
        }
    }

    private class HangingRunner : LlmRunner {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun complete(chat: ChatPrompt): String {
            started.complete(Unit)
            try { awaitCancellation() } catch (e: CancellationException) { cancelled.complete(Unit); throw e }
        }
    }

    private class BrokenRunner : LlmRunner {
        override suspend fun complete(prompt: ChatPrompt): String = throw IllegalStateException("runtime died")
    }

    @Test
    fun onDeviceModelGetsOneImagePerCall() = runBlocking {
        val runner = EchoRunner()
        qwen.runnerFactory = { runner }
        val a = async { translator.translate(job("A", "甲", "乙")) }
        val b = async { translator.translate(job("B", "丙")) }
        assertEquals(listOf("T:甲", "T:乙"), a.await())
        assertEquals(listOf("T:丙"), b.await())
        assertEquals(2, runner.prompts.size)
        assertTrue(runner.prompts.none { it.contains("甲") && it.contains("丙") })
    }

    @Test
    fun engineFailureSurfacesTypedInsteadOfSwitchingProvider() = runBlocking {
        qwen.runnerFactory = { BrokenRunner() }
        val e = try { translator.translate(job("A", "甲")); null } catch (e: EngineException) { e }
        val f = e?.failure
        assertTrue(f is EngineFailure.Unavailable && f.engine == EngineId.QWEN15_LOCAL)
    }

    @Test
    fun cancelledCallerCancelsTheGenerationItWasWaitingFor() = runBlocking {
        val runner = HangingRunner()
        qwen.runnerFactory = { runner }
        val caller = launch { translator.translate(job("A", "甲")) }
        withTimeout(5_000) { runner.started.await() }
        caller.cancelAndJoin()
        // The batch ran in the translator's own scope; withdrawing the only waiter must cancel it, releasing the engine.
        withTimeout(5_000) { runner.cancelled.await() }
        assertTrue(runner.cancelled.isCompleted)
    }

    @Test
    fun geminiReadsUnreadRegionsFromTheirCropsAndReturnsWhatItRead() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            keys.set(EngineId.GEMINI, "g-key")
            gemini.baseUrl = server.url("/v1beta").toString()
            server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"[{\"i\":0,\"source\":\"こんにちは\",\"text\":\"Hello\"},{\"i\":1,\"source\":\"ドン\",\"text\":\"BOOM\"},{\"i\":2,\"text\":\"Thanks\"}]"}]}}]}"""))
            val crop = { android.graphics.Bitmap.createBitmap(120, 200, android.graphics.Bitmap.Config.ARGB_8888) }
            val unread = TextRegion(bbox = IntRect(0, 0, 120, 200), kind = RegionKind.ENCLOSED, text = TextLine.UNREAD)
            val job = TranslateJob(
                "screen", TranslateConfig(Lang.JA, Lang.EN, EngineId.GEMINI),
                listOf(unread, unread.copy(kind = RegionKind.SFX), region("ありがとう")),
                crops = mapOf(0 to crop(), 1 to crop()),
            )

            val out = translator.translateReading(job)

            // Two identical placeholders stay two items (no de-duplication), and what Gemini read comes back.
            assertEquals(listOf("Hello", "BOOM", "Thanks"), out.map { it?.text })
            assertEquals(listOf("こんにちは", "ドン", null), out.map { it?.source })
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
            val payload = Json.parseToJsonElement(parts[0].jsonObject["text"]!!.jsonPrimitive.content).jsonArray
            assertEquals(listOf(true, true, false), payload.map { it.jsonObject["read"]?.jsonPrimitive?.content == "true" })
            assertEquals(listOf("", "", "ありがとう"), payload.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            // Each crop follows its label; the dialogue item has none.
            assertEquals("Item 0", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue("inline_data" in parts[2].jsonObject)
            assertEquals("Item 1", parts[3].jsonObject["text"]!!.jsonPrimitive.content)
            assertTrue("inline_data" in parts[4].jsonObject)
            assertEquals(5, parts.size)
            val system = body["system_instruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(system.contains("\"read\": true") && system.contains("source"))
            // The output rule itself must ask for source: a bare "{i, text}, no extra keys" made Gemini drop it.
            assertTrue(system.contains("{i, source, text} for items with \"read\": true"))
            assertTrue(!system.contains("no extra keys"))
        } finally { server.shutdown() }
    }
}
