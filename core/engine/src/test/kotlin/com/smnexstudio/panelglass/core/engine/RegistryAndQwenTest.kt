package com.smnexstudio.panelglass.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.smnexstudio.panelglass.core.engine.llm.ClaudeEngine
import com.smnexstudio.panelglass.core.engine.llm.DeepSeekEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
import com.smnexstudio.panelglass.core.engine.local.ByteLevel
import com.smnexstudio.panelglass.core.engine.local.LlmRunner
import com.smnexstudio.panelglass.core.engine.local.ModelStore
import com.smnexstudio.panelglass.core.engine.local.ChatPrompt
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.LocalLlmEngine
import com.smnexstudio.panelglass.core.engine.local.LocalModel
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
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
import android.app.ActivityManager
import com.smnexstudio.panelglass.core.engine.local.DeviceMemory
import com.smnexstudio.panelglass.core.model.QwenBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A prompt as one string, for comparing what a runner was given. */
private fun ChatPrompt.flat() = system + "\n" + user

/** Robolectric (JUnit 4 via vintage) because the on-device engine and model store take a Context. */
@RunWith(RobolectricTestRunner::class)
class RegistryAndQwenTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ds = MemoryDataStore()
    private val keys = testKeyStore(ds)
    private val settings = testSettings(ds)
    private val support = testSupport()
    private val locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
    private val qwen = locals[LocalModel.QWEN15]

    private fun registry() = EngineRegistry(
        locals, GeminiEngine(support, keys, settings), ClaudeEngine(support, keys, settings), OpenAiEngine(support, keys, settings),
        DeepLEngine(support, keys), PapagoEngine(support, keys), DeepSeekEngine(support, keys),
        GoogleTranslateEngine(), OpenRouterEngine(support, keys, settings), keys, settings,
    )

    private fun items(n: Int) = List(n) { RegionItem(it, "img", RegionKind.ENCLOSED, "文$it") }

    // ---- selection rule ------------------------------------------------------------------------

    @Test
    fun defaultSelectionIsTheOnDeviceModel() = runBlocking {
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Ready && r.engine.id == EngineId.QWEN15_LOCAL)
    }

    @Test
    fun googleTranslateNeedsNoKey() = runBlocking {
        settings.setEngine(EngineId.GOOGLE)
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Ready && r.engine.id == EngineId.GOOGLE)
    }

    @Test
    fun selectedKeyedEngineResolvesWhenItsKeyExists() = runBlocking {
        keys.set(EngineId.GEMINI, "AIza")
        settings.setEngine(EngineId.GEMINI)
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Ready && r.engine.id == EngineId.GEMINI)
    }

    @Test
    fun selectedEngineWithoutKeyIsMissingKeyNotAFallback() = runBlocking {
        settings.setEngine(EngineId.GEMINI)
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Failed && (r.failure as? EngineFailure.MissingKey)?.engine == EngineId.GEMINI)
    }

    @Test
    fun onlyGeminiGoogleAndOnDeviceModelsAreOfferedAndAPlannedEngineNeverResolves() = runBlocking {
        assertEquals(
            setOf(EngineId.QWEN15_LOCAL, EngineId.GEMMA4_LOCAL, EngineId.GEMINI, EngineId.GOOGLE),
            EngineId.entries.filter { it.offered }.toSet(),
        )
        // Selected in an older version, key and all: it says so instead of quietly becoming another engine.
        keys.set(EngineId.CLAUDE, "sk-ant"); settings.setEngine(EngineId.CLAUDE)
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Failed && r.failure is EngineFailure.Unavailable && r.failure.engine == EngineId.CLAUDE)
    }

    @Test
    fun removingTheSelectedEnginesKeyLeavesSelectionAndReportsMissingKey() = runBlocking {
        keys.set(EngineId.GEMINI, "AIza"); settings.setEngine(EngineId.GEMINI)
        keys.set(EngineId.GEMINI, null)
        assertEquals(EngineId.GEMINI, settings.current().engineId)
        val r = registry().resolveSelected()
        assertTrue(r is EngineResolution.Failed && r.failure is EngineFailure.MissingKey)
    }

    @Test
    fun geminiResolvesWithItsDefaultModelUntilTheUserNamesOne() = runBlocking {
        keys.set(EngineId.GEMINI, "AIza")
        assertEquals("gemini-flash-latest", settings.current().modelFor(EngineId.GEMINI))
        assertTrue(registry().resolve(EngineId.GEMINI) is EngineResolution.Ready)
        settings.setModel(EngineId.GEMINI, " gemini-3.5-flash-lite ")
        assertEquals("gemini-3.5-flash-lite", settings.current().modelFor(EngineId.GEMINI))
        settings.setModel(EngineId.GEMINI, "")
        assertEquals("gemini-flash-latest", settings.current().modelFor(EngineId.GEMINI))
    }

    // ---- on-device engine ----------------------------------------------------------------------

    private class ScriptedRunner(private val replies: Iterator<String>, private val counts: Boolean = false) : LlmRunner {
        val prompts = ArrayList<String>()
        override suspend fun complete(chat: ChatPrompt): String { prompts += chat.flat(); return replies.next() }
        /** One token per character, like the engine's own fallback, but through the tokenizer seam. */
        override fun tokens(text: String): Int? = if (counts) text.length else null
    }

    @Test
    fun chunksByTokenBudgetNotJustByCount() = runBlocking {
        // Five 500-char items: each costs ~1508 tokens against a 4096 cache, so two fit per call, never three.
        val items = List(5) { RegionItem(it, "img", RegionKind.ENCLOSED, "あ".repeat(500)) }
        val replies = listOf("0: a\n1: b", "2: c\n3: d", "4: e")
        val runner = ScriptedRunner(replies.iterator(), counts = true)
        qwen.runnerFactory = { runner }
        val out = qwen.translate(TranslateRequest(items, Lang.JA, Lang.EN))
        assertEquals(listOf("a", "b", "c", "d", "e"), out.map { it.text })
        assertEquals(3, runner.prompts.size)
        // Prompt plus the reply allowance must fit the cache for every chunk, else the reply is cut mid-JSON.
        for (p in runner.prompts) assertTrue(p.length + 2 * 500 * 2 + 48 <= LocalModel.QWEN15.maxTokens)
    }

    @Test
    fun compactPayloadIsNumberedLinesWithSfxMarked() {
        val payload = LocalPrompt.payload(listOf(
            RegionItem(0, "img", RegionKind.ENCLOSED, "文\nです"),
            RegionItem(1, "img", RegionKind.SFX, "ドン"),
        ))
        // A bubble's own line breaks must not split it into two numbered lines.
        assertEquals("0: 文 です\n1: (SFX) ドン", payload)
        assertTrue(!payload.contains("imageId"))
    }

    @Test
    fun lineParserIsLenientButDropsEchoesAndStrayNumbers() {
        val items = items(4)
        val reply = "Sure!\n0: \"Hello.\"\n1) Where?\n2: まだ日本語だ\n7: not asked for\n3：(SFX) BAM"
        val got = LocalPrompt.parse(reply, items, Lang.EN)
        assertEquals(mapOf(0 to "Hello.", 1 to "Where?", 3 to "BAM"), got)
        // Into Japanese, CJK in the reply is the translation, not an echo.
        assertEquals("まだ日本語だ", LocalPrompt.parse(reply, items, Lang.JA)[2])
        // The old JSON shape still parses.
        assertEquals(mapOf(0 to "a", 1 to "b"), LocalPrompt.parse("""[{"i":0,"text":"a"},{"i":1,"text":"b"}]""", items(2), Lang.EN))
    }

    @Test
    fun replyIsCompleteOnceEveryLineIsFinishedOrTheModelInventsMore() {
        val items = listOf(RegionItem(4, "img", RegionKind.ENCLOSED, "文"), RegionItem(5, "img", RegionKind.ENCLOSED, "字"))
        assertTrue(!LocalPrompt.replyComplete("4: Hel", items))
        assertTrue(!LocalPrompt.replyComplete("4: Hello\n5: Wor", items))       // last line still being written
        assertTrue(LocalPrompt.replyComplete("4: Hello\n5: World\n", items))
        assertTrue(LocalPrompt.replyComplete("4: Hello\n5: World\n6", items))
        assertTrue(LocalPrompt.replyComplete("4: Hello\n6: made up", items))   // numbering beyond the request
        assertTrue(!LocalPrompt.replyComplete("Sure:\n", items))
        assertTrue(LocalPrompt.replyComplete("x".repeat(LocalPrompt.maxReplyChars(items) + 1), items))
    }

    @Test
    fun missingModelIsATypedFailure() = runBlocking {
        val e = try { qwen.translate(TranslateRequest(items(1), Lang.JA, Lang.EN)); null } catch (e: EngineException) { e }
        assertTrue(e?.failure is EngineFailure.ModelMissing)
    }

    @Test
    fun largeRequestsAreChunked() = runBlocking {
        val n = LocalLlmEngine.CHUNK * 2 + 3
        val replies = ArrayList<String>()
        var offset = 0
        // Items keep their global `i` inside each chunk, so the model's reply must echo those, not 0-based ones.
        for (size in listOf(LocalLlmEngine.CHUNK, LocalLlmEngine.CHUNK, 3)) {
            replies += (0 until size).joinToString("\n") { "${offset + it}: t${offset + it}" }
            offset += size
        }
        val runner = ScriptedRunner(replies.iterator())
        qwen.runnerFactory = { runner }
        val out = qwen.translate(TranslateRequest(items(n), Lang.JA, Lang.EN))
        assertEquals(3, runner.prompts.size)
        assertEquals(n, out.size)
        assertEquals("t${n - 1}", out.last().text)
        assertEquals(n - 1, out.last().i)
    }

    @Test
    fun unusableReplyFallsBackToPlainPerItemPromptsWithoutARepairRound() = runBlocking {
        val runner = ScriptedRunner(listOf("I cannot do that", "Hello there", "Thanks").iterator())
        qwen.runnerFactory = { runner }
        val out = qwen.translate(TranslateRequest(items(2), Lang.JA, Lang.EN))
        assertEquals(listOf("Hello there", "Thanks"), out.map { it.text })
        assertEquals(3, runner.prompts.size)
        assertEquals(LocalPrompt.plain(items(2)[0], TranslateRequest(items(2), Lang.JA, Lang.EN)).flat(), runner.prompts[1])
    }

    @Test
    fun partialReplyKeepsWhatParsedAndRetriesOnlyTheRest() = runBlocking {
        // Item 1 is skipped and item 2 echoed back in Japanese: only those two cost an extra generation.
        val runner = ScriptedRunner(listOf("0: One\n2: 日本語のまま\n3: Four", "Two", "Three").iterator())
        qwen.runnerFactory = { runner }
        val out = qwen.translate(TranslateRequest(items(4), Lang.JA, Lang.EN))
        assertEquals(listOf("One", "Two", "Three", "Four"), out.map { it.text })
        assertEquals(3, runner.prompts.size)
        assertTrue(runner.prompts[1].endsWith(LocalPrompt.plain(items(4)[1], TranslateRequest(items(4), Lang.JA, Lang.EN)).user))
    }

    /** The GPT-2 byte stand-ins a runtime can leave in place of a split UTF-8 character. */
    private fun standIns(s: String): String {
        val map = HashMap<Int, Char>()
        var n = 0
        for (b in 0..255) {
            val printable = b in 0x21..0x7E || b in 0xA1..0xAC || b in 0xAE..0xFF
            map[b] = if (printable) b.toChar() else (256 + n++).toChar()
        }
        return s.toByteArray(Charsets.UTF_8).joinToString("") { map.getValue(it.toInt() and 0xFF).toString() }
    }

    @Test
    fun byteLevelStandInsAreDecodedBack() {
        assertEquals("HIS NAME IS VANJé", ByteLevel.repair("HIS NAME IS VANJ" + standIns("é")))
        assertEquals("0: जी हाँ", ByteLevel.repair("0: ज" + standIns("ी हाँ")))
    }

    @Test
    fun realLatinTextIsLeftAlone() {
        assertEquals("Café © 2024", ByteLevel.repair("Café © 2024"))
        assertEquals("Good morning.", ByteLevel.repair("Good morning."))
    }

    @Test
    fun repliesAreRepairedBeforeParsing() = runBlocking {
        val runner = ScriptedRunner(listOf("0: " + standIns("नमस्ते")).iterator())
        qwen.runnerFactory = { runner }
        val out = qwen.translate(TranslateRequest(items(1), Lang.JA, Lang.HI))
        assertEquals("नमस्ते", out.single().text)
    }

    // ---- LiteRT-LM models ----------------------------------------------------------------------

    /** Records the prompt it was given (not rendered: LiteRT-LM applies the model's own template) and counts closes. */
    private class TemplateFreeRunner(private val reply: String) : LlmRunner {
        val prompts = ArrayList<ChatPrompt>()
        var closed = 0
        override suspend fun complete(prompt: ChatPrompt): String { prompts += prompt; return reply }
        override fun close() { closed++ }
    }

    @Test
    fun everyOnDeviceModelResolvesWithoutAKey() = runBlocking {
        for (id in listOf(EngineId.QWEN15_LOCAL, EngineId.GEMMA4_LOCAL)) {
            val r = registry().resolve(id)
            assertTrue("$id", r is EngineResolution.Ready && r.engine.id == id)
            assertTrue(id.isLocalLlm)
        }
    }

    @Test
    fun aLiteRtLmModelGetsTheSystemTextAndPayloadAsSeparateTurns() = runBlocking {
        val gemma = locals[LocalModel.GEMMA4]
        val runner = TemplateFreeRunner("0: Good morning.\n1: How do you know that?")
        gemma.runnerFactory = { runner }
        val req = TranslateRequest(listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "おはよう"), RegionItem(1, "img", RegionKind.ENCLOSED, "何で知ってんだ?")), Lang.JA, Lang.EN)
        val out = gemma.translate(req)
        assertEquals(listOf("Good morning.", "How do you know that?"), out.map { it.text })
        val p = runner.prompts.single()
        assertEquals(LocalPrompt.system(req), p.system)
        assertEquals("0: おはよう\n1: 何で知ってんだ?", p.user)
    }

    /** The phone holds one on-device LLM: loading another releases the one in memory. */
    @Test
    fun loadingOneModelReleasesTheOther() = runBlocking {
        val a = TemplateFreeRunner("0: a"); val b = TemplateFreeRunner("0: b")
        qwen.runnerFactory = { a }
        locals[LocalModel.GEMMA4].runnerFactory = { b }
        qwen.translate(TranslateRequest(items(1), Lang.JA, Lang.EN))
        assertEquals(0, a.closed)
        locals[LocalModel.GEMMA4].translate(TranslateRequest(items(1), Lang.JA, Lang.EN))
        assertEquals(1, a.closed)
        assertEquals(0, b.closed)
    }

    /** Settings › Models › Qwen runs on applies at the next call: a runner loaded on the other backend is reloaded once. */
    @Test
    fun changingQwensBackendReloadsTheModelOnItsNextCall() = runBlocking {
        locals.bindSettings(settings)
        settings.setQwenBackend(QwenBackend.GPU)
        val loaded = mutableListOf<TemplateFreeRunner>()
        qwen.runnerFactory = { TemplateFreeRunner("0: a").also { loaded += it } }
        val req = TranslateRequest(items(1), Lang.JA, Lang.EN)
        qwen.translate(req)
        qwen.translate(req)
        assertEquals(1, loaded.size)
        settings.setQwenBackend(QwenBackend.CPU)
        qwen.translate(req)
        assertEquals(2, loaded.size)
        assertEquals(1, loaded[0].closed)
        qwen.translate(req)
        assertEquals(2, loaded.size)
        assertEquals(QwenBackend.CPU, settings.current().qwenBackend)
    }

    /** Automatic takes the CPU below a 6 GB phone (≥ 5 GiB reported); an explicit choice wins whatever the RAM. */
    @Test
    fun automaticRunsQwenOnTheCpuBelowSixGb() {
        val am = context.getSystemService(ActivityManager::class.java)
        fun ram(gib: Double) = shadowOf(am).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = (gib * (1L shl 30)).toLong() })
        ram(3.6)
        assertTrue(DeviceMemory.qwenOnCpu(context, QwenBackend.AUTO))
        assertTrue(!DeviceMemory.qwenOnCpu(context, QwenBackend.GPU))
        ram(7.3)
        assertTrue(!DeviceMemory.qwenOnCpu(context, QwenBackend.AUTO))
        assertTrue(DeviceMemory.qwenOnCpu(context, QwenBackend.CPU))
    }

    /** Suspends inside the generation until [gate] opens, so a test can act while the model is busy. */
    private class GatedRunner : LlmRunner {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var closed = 0
        override suspend fun complete(prompt: ChatPrompt): String { started.complete(Unit); gate.await(); return "0: a" }
        override fun close() { closed++ }
    }

    /** Home mid-translation: the busy model is not released at once, but shortly after its last call. */
    @Test
    fun aModelBusyWhenTheUiIsHiddenIsReleasedAfterItsLastCall() = runBlocking {
        val r = GatedRunner()
        qwen.runnerFactory = { r }
        locals.releaseGraceMs = 50
        val call = launch(Dispatchers.Default) { qwen.translate(TranslateRequest(items(1), Lang.JA, Lang.EN)) }
        r.started.await()
        locals.onHidden()
        assertEquals(0, r.closed)
        r.gate.complete(Unit); call.join()
        delay(500)
        assertEquals(1, r.closed)
    }

    /** Back on screen before the call ends: the model is kept for the reader. */
    @Test
    fun comingBackBeforeTheCallEndsKeepsTheModel() = runBlocking {
        val r = GatedRunner()
        qwen.runnerFactory = { r }
        locals.releaseGraceMs = 50
        val call = launch(Dispatchers.Default) { qwen.translate(TranslateRequest(items(1), Lang.JA, Lang.EN)) }
        r.started.await()
        locals.onHidden()
        locals.onVisible()
        r.gate.complete(Unit); call.join()
        delay(500)
        assertEquals(0, r.closed)
    }

    @Test
    fun gemma4TranslatesTheOcrTextLikeQwen() {
        assertTrue(!EngineId.GEMMA4_LOCAL.readsCrops && !locals[LocalModel.GEMMA4].acceptsImages)
    }

    @Test
    fun escapedQuotesAndAddedSfxNotesAreCleanedFromReplies() {
        val reply = "0: people to see!\\\"\n1: I'm... (SFX: coughing)"
        assertEquals(mapOf(0 to "people to see!\"", 1 to "I'm..."), LocalPrompt.parse(reply, items(2), Lang.EN))
    }

    @Test
    fun onlyGemma4AsksForAnEightGbPhone() {
        assertEquals(listOf(LocalModel.GEMMA4), LocalModel.entries.filter { it.minRamGb >= 8 })
        assertTrue(LocalModel.entries.none { it.maxTokens > 4096 })
        assertEquals(LocalModel.GEMMA4, LocalModel.of(EngineId.GEMMA4_LOCAL))
    }
}
