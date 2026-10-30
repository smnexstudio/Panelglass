package com.smnexstudio.panelglass.core.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.GlossaryRepository
import com.smnexstudio.panelglass.core.data.repo.SfxCacheRepository
import com.smnexstudio.panelglass.core.data.secure.BlobCrypto
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.BatchingTranslator
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.SfxDictionary
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
import com.smnexstudio.panelglass.core.engine.mt.PapagoEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.ImageSource
import com.smnexstudio.panelglass.core.model.IntRect
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Patch
import com.smnexstudio.panelglass.core.model.SkipReason
import com.smnexstudio.panelglass.core.model.TextLine
import com.smnexstudio.panelglass.core.model.TranslateConfig
import com.smnexstudio.panelglass.core.ocr.BoxKind
import com.smnexstudio.panelglass.core.ocr.PageDetection
import com.smnexstudio.panelglass.core.ocr.TextBox
import com.smnexstudio.panelglass.core.ocr.TextDetector
import com.smnexstudio.panelglass.core.render.PatchEncoder
import com.smnexstudio.panelglass.core.render.RegionRenderer
import com.smnexstudio.panelglass.core.render.TextEraser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * The reader's pipeline end to end on the JVM: a scripted detector and a scripted on-device model, with the real
 * detection stage, batching translator, renderer, patch cache and glossary. What the stages do together: cache hits
 * skip the engine, a hung engine fails its image typed, a glossary or notes change misses the cache.
 * Robolectric with native graphics (JUnit 4 via vintage), as the renderer draws real text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranslationPipelineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: PanelglassDb
    private lateinit var root: File
    private lateinit var cache: PatchCache
    private lateinit var glossary: GlossaryRepository
    private lateinit var pipeline: TranslationPipeline
    private lateinit var locals: LocalEngines
    private val detector = ScriptedDetector()
    private val cfg = TranslateConfig(Lang.JA, Lang.EN, EngineId.QWEN15_LOCAL)

    @Before fun setUp() {
        root = File(context.filesDir, "pipeline-test").apply { deleteRecursively(); mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(MemoryDataStore())
        val keys = SecureKeyStore(MemoryDataStore(), Plain)
        val http = HttpEngineSupport(OkHttpClient(), Json { ignoreUnknownKeys = true })
        locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
        val registry = EngineRegistry(
            locals, GeminiEngine(http, keys, settings), com.smnexstudio.panelglass.core.engine.llm.CustomOpenAiEngine(http, keys, settings), ClaudeEngine(http, keys, settings), OpenAiEngine(http, keys, settings),
            DeepLEngine(http, keys), PapagoEngine(http, keys), DeepSeekEngine(http, keys),
            GoogleTranslateEngine(), OpenRouterEngine(http, keys, settings), keys, settings,
        )
        cache = PatchCache(File(root, "patches"))
        glossary = GlossaryRepository(db)
        pipeline = TranslationPipeline(
            context, detector, RegionRenderer(TextEraser(), PatchEncoder()),
            BatchingTranslator(registry, SfxDictionary(context), SfxCacheRepository(db)),
            cache, glossary, registry, StageGates(),
        )
        useRunner(EchoRunner())
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
        root.deleteRecursively()
    }

    private fun useRunner(r: LlmRunner) { locals[LocalModel.QWEN15].runnerFactory = { r } }

    // ---- fixtures --------------------------------------------------------------------------------------------

    /** A grey page with one white balloon holding dark "lettering" (blocks: the detector is scripted). */
    private fun page(w: Int = 800, h: Int = 1200): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(170, 170, 170))
        val c = Canvas(this)
        c.drawOval(200f, 380f, 600f, 720f, Paint().apply { color = Color.WHITE; isAntiAlias = true })
        val ink = Paint().apply { color = Color.BLACK }
        for (row in 0 until 3) for (k in 0 until 8) c.drawRect(270f + k * 32, 500f + row * 34, 292f + k * 32, 524f + row * 34, ink)
    }

    private val textBox = IntRect(262, 492, 534, 602)
    private val balloon = IntRect(200, 380, 600, 720)

    private class ScriptedDetector : TextDetector {
        var calls = 0
        var text = "kou otsu hei"
        override suspend fun detect(bitmap: Bitmap, lang: Lang): List<TextLine> = error("the pipeline asks for whole pages")
        override suspend fun detectPage(bitmap: Bitmap, lang: Lang): PageDetection {
            calls++
            // Boxes in the detector's own (possibly scaled) pixels: scale from the 800 px page.
            val s = bitmap.width / 800f
            fun sc(r: IntRect) = IntRect((r.left * s).toInt(), (r.top * s).toInt(), (r.right * s).toInt(), (r.bottom * s).toInt())
            return PageDetection(
                lines = listOf(TextLine(sc(IntRect(262, 492, 534, 602)), text)),
                boxes = listOf(TextBox(sc(IntRect(200, 380, 600, 720)), BoxKind.BUBBLE, 0.9f), TextBox(sc(IntRect(262, 492, 534, 602)), BoxKind.TEXT_BUBBLE, 0.9f)),
            )
        }
        override suspend fun warmUp(lang: Lang) = Unit
        override fun close() = Unit
    }

    /** Answers every numbered line with `n: T:<text>`; [calls] counts translations, not the model's warm-up. */
    private class EchoRunner : LlmRunner {
        var calls = 0
        private val item = Regex("(?m)^(\\d+): (?:\\(SFX\\) )?(.*)$")
        override suspend fun complete(prompt: ChatPrompt): String {
            if (prompt.user == WARM_UP) return "OK"
            calls++
            return item.findAll(prompt.user).joinToString("\n") { m -> "${m.groupValues[1]}: T:${m.groupValues[2]}" }
        }
    }

    private class HangingRunner : LlmRunner {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        override suspend fun complete(prompt: ChatPrompt): String {
            if (prompt.user == WARM_UP) return "OK"
            started.complete(Unit)
            try { awaitCancellation() } catch (e: CancellationException) { cancelled.complete(Unit); throw e }
        }
    }

    private suspend fun run(bmp: Bitmap = page(), c: TranslateConfig = cfg, roi: RegionOfInterest? = null, bypass: Boolean = false): Pair<Result<com.smnexstudio.panelglass.core.model.PageResult>, List<Patch>> {
        val patches = mutableListOf<Patch>()
        val r = pipeline.translate(ImageSource.Native(bmp), c, imageId = "img", roi = roi, bypassCache = bypass) { patches += it }
        return r to patches
    }

    /**
     * The patch cache is written in the background after an image (WEBP encoding): wait for it, a condition and not a
     * sleep, with room for a machine busy running every module's tests.
     */
    private suspend fun awaitCached(bmp: Bitmap) = withTimeout(30_000) {
        val key = cache.key(TranslationPipeline.nativeHash(bmp), cfg.src, cfg.tgt, cfg.engineId, TranslationPipeline.PIPELINE_VERSION, cfg.fontId, "")
        while (cache.get(key) == null) delay(20)
    }

    // ---- tests -----------------------------------------------------------------------------------------------

    @Test fun aPageIsDetectedTranslatedAndRenderedToPatches() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val (r, patches) = run()
        val page = r.getOrThrow()
        assertEquals(1, page.regionCount)
        assertFalse(page.fromCache)
        assertTrue("a patch was drawn", patches.isNotEmpty())
        assertEquals(page.patchCount, patches.size)
        patches.forEach { p -> assertTrue(p.xPct >= 0f && p.yPct >= 0f && p.xPct + p.wPct <= 100.01f && p.yPct + p.hPct <= 100.01f) }
        assertEquals(listOf("kou otsu hei" to "T:kou otsu hei"), page.contextPairs.map { it.source to it.translation })
        assertEquals(1, runner.calls)
        assertEquals(1, detector.calls)
    }

    /** Back-navigation is free: the same pixels again come from the patch cache, with no detection and no engine call. */
    @Test fun theSamePageAgainIsServedFromTheCacheWithoutTheEngine() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val bmp = page()
        val (first, firstPatches) = run(bmp)
        awaitCached(bmp)
        val (second, patches) = run(bmp)
        assertTrue(second.getOrThrow().fromCache)
        assertEquals(firstPatches.size, patches.size)
        assertEquals(first.getOrThrow().regionCount, second.getOrThrow().regionCount)
        assertEquals("no second engine call", 1, runner.calls)
        assertEquals("no second detection", 1, detector.calls)
    }

    @Test fun reTranslateBypassesTheCache() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val bmp = page()
        run(bmp); awaitCached(bmp)
        val (again, _) = run(bmp, bypass = true)
        assertFalse(again.getOrThrow().fromCache)
        assertEquals(2, runner.calls)
    }

    /** An edited glossary or note must translate the page again, not serve the old patches. */
    @Test fun aGlossaryOrNotesChangeMissesTheCache() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val bmp = page()
        run(bmp); awaitCached(bmp)
        assertFalse(run(bmp, c = cfg.copy(notes = "Keep honorifics.")).first.getOrThrow().fromCache)
        assertFalse(run(bmp, c = cfg.copy(glossary = mapOf("kou" to "Kou"))).first.getOrThrow().fromCache)
        assertEquals(3, runner.calls)
    }

    @Test fun aHungEngineFailsItsImageWithTheWatchdog() = runBlocking<Unit> {
        val runner = HangingRunner().also { useRunner(it) }
        pipeline.stageTimeoutMs = 300
        val (r, patches) = run()
        val e = r.exceptionOrNull()
        assertTrue("got $e", e is EngineException)
        val f = (e as EngineException).failure
        assertTrue("got $f", f is EngineFailure.Unavailable && f.reason.startsWith("Watchdog: translate"))
        assertTrue(patches.isEmpty())
        withTimeout(5_000) { runner.cancelled.await() }
    }

    @Test fun aBrokenEngineIsATypedFailureNotACrash() = runBlocking<Unit> {
        useRunner(object : LlmRunner {
            override suspend fun complete(prompt: ChatPrompt): String = if (prompt.user == WARM_UP) "OK" else throw IllegalStateException("runtime died")
        })
        val e = run().first.exceptionOrNull()
        assertTrue("got $e", e is EngineException)
    }

    /** Stop: cancelling the caller cancels the generation and returns nothing. */
    @Test fun cancellingTheCallerCancelsTheGeneration() = runBlocking<Unit> {
        val runner = HangingRunner().also { useRunner(it) }
        val job = async(Dispatchers.Default) { run() }
        withTimeout(10_000) { runner.started.await() }
        job.cancel()
        withTimeout(5_000) { runner.cancelled.await() }
        assertTrue(job.isCancelled)
    }

    @Test fun aSnapshotTooSmallIsSkippedBeforeDetection() = runBlocking<Unit> {
        val (r, _) = run(Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888))
        assertEquals(SkipReason.TOO_SMALL, r.getOrThrow().skippedReason)
        assertEquals(0, detector.calls)
    }

    /** Text under a page control (or outside the page's images) is not translated: no engine call. */
    @Test fun nothingInTheRegionOfInterestMeansNoEngineCall() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val (r, patches) = run(roi = RegionOfInterest(exclude = listOf(balloon)))
        assertEquals(SkipReason.NO_REGIONS, r.getOrThrow().skippedReason)
        assertTrue(patches.isEmpty())
        assertEquals(0, runner.calls)
    }

    /** An area an earlier capture already translated is not read or translated again. */
    @Test fun anAreaAlreadyTranslatedIsNotTranslatedAgain() = runBlocking<Unit> {
        val runner = EchoRunner().also { useRunner(it) }
        val (r, _) = run(roi = RegionOfInterest(done = listOf(balloon)))
        assertEquals(SkipReason.NO_REGIONS, r.getOrThrow().skippedReason)
        assertEquals(0, runner.calls)
        assertTrue(textBox.area > 0)
    }

    private companion object {
        /** The user turn of LocalLlmEngine.warmUp's one-time prompt. */
        const val WARM_UP = "OK"
    }

    private object Plain : BlobCrypto {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(blob: String) = blob.reversed()
    }
}
