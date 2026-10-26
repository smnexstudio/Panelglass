package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.engine.local.LocalPrompt
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LlmContractTest {
    private val items = listOf(
        RegionItem(0, "img1", RegionKind.ENCLOSED, "こんにちは"),
        RegionItem(1, "img1", RegionKind.SFX, "ドン"),
        RegionItem(2, "img2", RegionKind.FREE, "ありがとう"),
    )

    @Test
    fun strictParseAcceptsExactContract() {
        val out = LlmContract.parse(EngineId.GEMINI, """[{"i":0,"text":"Hello"},{"i":2,"text":"Thanks"},{"i":1,"text":"BOOM"}]""", items)
        assertEquals(listOf("Hello", "BOOM", "Thanks"), out.map { it.text })
    }

    @Test
    fun parseToleratesProseAndFences() {
        val raw = "Sure! ```json\n[{\"i\":0,\"text\":\"Hi\"},{\"i\":1,\"text\":\"BAM\"},{\"i\":2,\"text\":\"Ty\"}]\n```"
        assertEquals(3, LlmContract.parse(EngineId.CLAUDE, raw, items).size)
    }

    @Test
    fun lengthMismatchIsMalformed() {
        val e = assertThrows(EngineException::class.java) { LlmContract.parse(EngineId.OPENAI, """[{"i":0,"text":"Hi"}]""", items) }
        assertTrue(e.failure is EngineFailure.Malformed)
    }

    @Test
    fun garbageIsMalformed() {
        val e = assertThrows(EngineException::class.java) { LlmContract.parse(EngineId.OPENAI, "not json at all", items) }
        assertTrue(e.failure is EngineFailure.Malformed)
    }

    @Test
    fun promptCarriesGlossaryAndMontageMapping() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN, glossary = mapOf("ルフィ" to "Luffy"), sfxMontage = ByteArray(1), sfxMontageIndices = listOf(1))
        val p = LlmContract.systemPrompt(req)
        assertTrue(p.contains("ルフィ => Luffy"))
        assertTrue(p.contains("i=1"))
        assertTrue(p.contains("SLAM"))
    }

    @Test
    fun thePromptShowsWhatASingleItemReplyLooksLike() {
        val plain = LlmContract.systemPrompt(TranslateRequest(items, Lang.JA, Lang.EN))
        assertTrue(plain.contains("Example with one item"), plain)
        assertTrue(plain.contains("A single item is still an array"), plain)
        assertTrue(plain.contains("the first character of your reply must be ["), plain)
        // The same example is in the variant that reads the crops.
        val withCrops = LlmContract.systemPrompt(
            TranslateRequest(items, Lang.JA, Lang.EN, readImages = mapOf(0 to ByteArray(1))),
        )
        assertTrue(withCrops.contains("Example with one item"), withCrops)
        assertTrue(withCrops.contains("the first character of your reply must be ["), withCrops)
    }

    @Test
    fun theRetryMessageCarriesTheInputWithIt() {
        val payload = LlmContract.userPayload(items, emptySet())
        val msg = LlmContract.retryUserMessage(payload)
        assertTrue(msg.startsWith(payload), msg)
        assertTrue(msg.contains("Your previous reply could not be used"), msg)
        assertTrue(msg.contains("The first character of your reply must be ["), msg)
        // The variant that reads the crops asks for the source as well.
        assertTrue(LlmContract.retryUserMessage(payload, withSource = true).contains("{i, source, text}"))
    }

    @Test
    fun promptsCarryTheReadersNotes() {
        val notes = "Characters:\n- Yuki (ユキ): female, she/her"
        val req = TranslateRequest(items, Lang.JA, Lang.EN, notes = notes)
        assertTrue(LlmContract.systemPrompt(req).contains(notes))
        assertTrue(LocalPrompt.system(req).contains(notes))
        assertTrue(LocalPrompt.plain(items[0], req).system.contains("Yuki (ユキ): female"))
        // A subset request (a retry) keeps them.
        assertTrue(LlmContract.systemPrompt(req.withItems(items.take(1))).contains(notes))
    }

    /** Seen on a phone (Gemma 4, Korean webtoon): the source came back in front of the translation and was lettered. */
    @Test
    fun aRepeatedSourceIsCutFromTheReply() {
        val item = RegionItem(0, "img", RegionKind.CAPTION, "예를 들자면")
        val parsed = LocalPrompt.parse("0: 예를 들자면: For example.", listOf(item), Lang.EN)
        assertEquals("For example.", parsed[0])
        assertEquals("BOOM", LocalPrompt.withoutSource("ドン => BOOM", "ドーン", Lang.EN))
        assertEquals("Fine.", LocalPrompt.withoutSource("元気 → Fine.", "元気だ", Lang.EN))
    }

    /** Seen on a phone (Gemma 4, Korean → English): 물론 came back as もちろん at the end of an English line. */
    @Test
    fun aLineThatSwitchesScriptIsRetriedThenCleaned() {
        val item = RegionItem(0, "img", RegionKind.CAPTION, "그 가게는 물론 컸다")
        assertTrue(LocalPrompt.parse("0: The shop was big, ofもちろん", listOf(item), Lang.EN).isEmpty())
        assertEquals("It grew, of course.", LocalPrompt.withoutStrayScript("It grew, of course もちろん.", Lang.EN))
        // Nothing but CJK is not "cleaned" into nothing; a CJK target is never touched.
        assertEquals("もちろん", LocalPrompt.withoutStrayScript("もちろん", Lang.EN))
        assertEquals("물론이지", LocalPrompt.withoutStrayScript("물론이지", Lang.KO))
    }

    /** Seen on a phone (Gemma 4, Korean → English): "I was गहराईly impressed by it". Any foreign script counts. */
    @Test
    fun anyWritingSystemOtherThanTheTargetsIsALeak() {
        assertTrue(LocalPrompt.hasStrayScript("I was गहराईly impressed", Lang.EN))
        // The Latin glued onto the foreign word goes with it.
        assertEquals("I was impressed by it", LocalPrompt.withoutStrayScript("I was गहराईly impressed by it", Lang.EN))
        // The target's own script, Latin names and punctuation are never stray.
        assertFalse(LocalPrompt.hasStrayScript("मैं बहुत प्रभावित हुआ, Ren!", Lang.HI))
        assertFalse(LocalPrompt.hasStrayScript("Привет, Ren — 3:00", Lang.RU))
        assertFalse(LocalPrompt.hasStrayScript("お兄ちゃん、来て", Lang.JA))
        // Into Japanese, Hangul is a leak; into Korean, Hanja is allowed.
        assertTrue(LocalPrompt.hasStrayScript("お兄ちゃん、와", Lang.JA))
        assertFalse(LocalPrompt.hasStrayScript("학교 學校", Lang.KO))
    }

    @Test
    fun anOrdinaryTranslationWithAColonIsKept() {
        assertEquals("Wait: what?", LocalPrompt.withoutSource("Wait: what?", "待って、何？", Lang.EN))
        assertEquals("Note: it's 3:00.", LocalPrompt.withoutSource("Note: it's 3:00.", "メモ", Lang.EN))
        // A CJK target is never cut: the source script is the right one there.
        assertEquals("예: 예시", LocalPrompt.withoutSource("예: 예시", "例", Lang.KO))
    }

    @Test
    fun noNotesNoNotesSection() {
        val req = TranslateRequest(items, Lang.JA, Lang.EN)
        assertTrue("Notes from the reader" !in LlmContract.systemPrompt(req))
        assertTrue("Reader's notes" !in LocalPrompt.system(req))
    }

    @Test
    fun statusMapping() {
        assertTrue(HttpEngineSupport.mapStatus(EngineId.GEMINI, 401, "", null) is EngineFailure.MissingKey)
        assertTrue(HttpEngineSupport.mapStatus(EngineId.GEMINI, 429, "quota exceeded", null) is EngineFailure.QuotaExceeded)
        val rl = HttpEngineSupport.mapStatus(EngineId.GEMINI, 429, "slow down", "5")
        assertTrue(rl is EngineFailure.RateLimited && rl.retryAfterMs == 5_000L)
        assertTrue(HttpEngineSupport.mapStatus(EngineId.DEEPL, 456, "", null) is EngineFailure.QuotaExceeded)
        assertTrue(HttpEngineSupport.mapStatus(EngineId.CLAUDE, 529, "", null) is EngineFailure.Overloaded)
        assertTrue(HttpEngineSupport.mapStatus(EngineId.OPENAI, 501, "", null) is EngineFailure.Unavailable)
    }

    @Test
    fun geminiHighDemandIsOverloadedWithTheProvidersMessage() {
        val body = """{"error":{"code":503,"message":"This model is currently experiencing high demand. Please try again later.","status":"UNAVAILABLE"}}"""
        val f = HttpEngineSupport.mapStatus(EngineId.GEMINI, 503, body, null)
        assertTrue(f is EngineFailure.Overloaded)
        assertTrue((f as EngineFailure.Overloaded).reason.startsWith("HTTP 503: This model is currently experiencing high demand"))
    }

    @Test
    fun retryPolicyIsBoundedAndCapped() {
        val over = EngineFailure.Overloaded(EngineId.GEMINI)
        assertEquals(1_000L, EngineRetry.delayFor(over, 1))
        assertEquals(2_000L, EngineRetry.delayFor(over, 2))
        assertEquals(null, EngineRetry.delayFor(over, 3))
        // A long Retry-After is capped so one retry cannot eat the screen budget.
        assertEquals(EngineRetry.MAX_DELAY_MS, EngineRetry.delayFor(EngineFailure.RateLimited(EngineId.GEMINI, 30_000), 1))
        assertEquals(null, EngineRetry.delayFor(EngineFailure.RateLimited(EngineId.GEMINI), 4))
        assertEquals(null, EngineRetry.delayFor(EngineFailure.Unavailable(EngineId.GEMINI), 1))
        assertEquals(null, EngineRetry.delayFor(EngineFailure.MissingKey(EngineId.GEMINI), 1))
    }

    @Test
    fun theReplyBudgetGrowsWithTheItemsUpToACap() {
        assertEquals(420, LlmContract.outputBudget(1))
        assertEquals(1500, LlmContract.outputBudget(10))
        // A full batch (60 items) fits under the cap; more would be capped.
        assertEquals(7500, LlmContract.outputBudget(60))
        assertEquals(LlmContract.MAX_OUTPUT_TOKENS, LlmContract.outputBudget(70))
        assertEquals(420, LlmContract.outputBudget(0))
    }
}
