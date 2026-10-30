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
import com.smnexstudio.panelglass.core.engine.local.ChatPrompt
import com.smnexstudio.panelglass.core.engine.local.LlmRunner
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.engine.mt.DeepLEngine
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.PackGate
import com.smnexstudio.panelglass.core.engine.mt.PairTranslator
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Airplane mode: the on-device engines (Google Translate = ML Kit with its packs on the phone, Qwen, Gemma) translate
 * with every HTTP request failing, and none of them even tries one. The cloud engine fails typed, on itself.
 * Robolectric (JUnit 4 via vintage) for the model stores' Context.
 */
@RunWith(RobolectricTestRunner::class)
class OfflineEnginesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ds = MemoryDataStore()
    private val keys = testKeyStore(ds)
    private val settings = testSettings(ds)
    /** Every request the app's HTTP client was asked to make. */
    private val attempted = mutableListOf<String>()
    private val offline = HttpEngineSupport(
        OkHttpClient.Builder().addInterceptor { chain -> synchronized(attempted) { attempted += chain.request().url.host }; throw IOException("airplane mode") }.build(),
        Json { ignoreUnknownKeys = true },
    )
    private val locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
    private val google = GoogleTranslateEngine().apply {
        translatorFactory = { _, _ -> object : PairTranslator {
            override suspend fun prepare() = Unit
            override suspend fun translate(text: String) = "EN($text)"
        } }
        packGate = PackGate.OPEN
    }
    private val registry = EngineRegistry(
        locals, GeminiEngine(offline, keys, settings), com.smnexstudio.panelglass.core.engine.llm.CustomOpenAiEngine(offline, keys, settings), ClaudeEngine(offline, keys, settings), OpenAiEngine(offline, keys, settings),
        DeepLEngine(offline, keys), PapagoEngine(offline, keys), DeepSeekEngine(offline, keys),
        google, OpenRouterEngine(offline, keys, settings), keys, settings,
    )
    private val req = TranslateRequest(listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "kou")), Lang.JA, Lang.EN)

    private val echo = object : LlmRunner {
        override suspend fun complete(prompt: ChatPrompt): String =
            Regex("(?m)^(\\d+): (.*)$").findAll(prompt.user).joinToString("\n") { "${it.groupValues[1]}: T:${it.groupValues[2]}" }.ifEmpty { "OK" }
    }

    @Test fun googleTranslateWorksOfflineWithItsPacksOnThePhone() = runBlocking<Unit> {
        settings.setEngine(EngineId.GOOGLE)
        registry[EngineId.GOOGLE].warmUp(Lang.JA, Lang.EN)
        assertEquals(listOf("EN(kou)"), registry.translate(req).items.map { it.text })
        assertEquals("no request was even tried", emptyList<String>(), attempted)
    }

    @Test fun theOnDeviceModelsWorkOffline() = runBlocking<Unit> {
        for (model in LocalModel.entries) {
            locals[model].runnerFactory = { echo }
            settings.setEngine(model.engineId)
            registry[model.engineId].warmUp(Lang.JA, Lang.EN)
            assertEquals(model.name, listOf("T:kou"), registry.translate(req).items.map { it.text })
        }
        assertEquals(emptyList<String>(), attempted)
    }

    /** The cloud engine fails as a network failure of its own; nothing switches to an on-device engine. */
    @Test fun aCloudEngineOfflineFailsTypedWithoutFallingBack() = runBlocking<Unit> {
        keys.set(EngineId.GEMINI, "g-key")
        settings.setEngine(EngineId.GEMINI)
        settings.setModel(EngineId.GEMINI, "gemini-flash-latest")
        try { registry.translate(req); fail("expected a failure") } catch (e: EngineException) {
            assertEquals(EngineFailure.Network(EngineId.GEMINI), e.failure)
        }
        assertTrue(attempted.all { it == "generativelanguage.googleapis.com" })
        assertTrue(attempted.isNotEmpty())
    }

    /** A warm-up for the cloud engine offline is silent: the reader opens without an error or a hang. */
    @Test fun aCloudWarmUpOfflineIsSilent() = runBlocking<Unit> {
        keys.set(EngineId.GEMINI, "g-key")
        registry[EngineId.GEMINI].warmUp(Lang.JA, Lang.EN)
        assertTrue(attempted.size <= 1)
    }
}
