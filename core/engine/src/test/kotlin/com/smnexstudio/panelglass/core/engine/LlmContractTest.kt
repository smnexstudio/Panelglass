package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.engine.llm.LlmContract
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import org.junit.jupiter.api.Assertions.assertEquals
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
}
