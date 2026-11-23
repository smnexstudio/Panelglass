package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine.Options
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiModelAdvice
import com.smnexstudio.panelglass.core.engine.llm.GeminiRequestRules
import com.smnexstudio.panelglass.core.engine.llm.ModelHealth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The family table that picks where a Gemini request starts, and the advice that suggests another model. */
class GeminiRequestRulesTest {
    @Test fun gemini25UsesABudgetAndProCannotGoToZero() {
        assertEquals(Options.THINKING_BUDGET_0, GeminiRequestRules.start("gemini-2.5-flash"))
        assertEquals(Options.THINKING_BUDGET_0, GeminiRequestRules.start("gemini-2.5-flash-lite"))
        assertEquals(Options.THINKING_BUDGET_128, GeminiRequestRules.start("gemini-2.5-pro"))
    }

    @Test fun gemini3FlashFamiliesStartAtMinimal() {
        for (id in listOf("gemini-3-flash-preview", "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash"))
            assertEquals(Options.THINKING_LEVEL_MINIMAL, GeminiRequestRules.start(id), id)
    }

    @Test fun proAndNewerFamiliesStartAtLowBecauseMinimalIsRefused() {
        for (id in listOf("gemini-3-pro-preview", "gemini-3.1-pro", "gemini-3.7-flash", "gemini-3.8-flash", "gemini-4-carbon"))
            assertEquals(Options.THINKING_LEVEL_LOW, GeminiRequestRules.start(id), id)
    }

    @Test fun aliasesAndGemmaAndUnknownIds() {
        assertEquals(Options.THINKING_LEVEL_MINIMAL, GeminiRequestRules.start("gemini-flash-latest"))
        assertEquals(Options.THINKING_LEVEL_MINIMAL, GeminiRequestRules.start("gemini-flash-lite-latest"))
        assertEquals(Options.THINKING_LEVEL_LOW, GeminiRequestRules.start("gemini-pro-latest"))
        assertEquals(Options.PLAIN, GeminiRequestRules.start("gemma-4-26b-a4b-it"))
    }

    @Test fun theLadderOnlyGoesDownAndEnds() {
        var level: Options? = Options.THINKING_BUDGET_0
        val seen = mutableListOf<Options>()
        while (level != null) { seen += level; level = GeminiRequestRules.stepDown(level) }
        assertEquals(
            listOf(Options.THINKING_BUDGET_0, Options.THINKING_BUDGET_128, Options.THINKING_LEVEL_LOW, Options.JSON_ONLY, Options.PLAIN),
            seen,
        )
        // A level refusal never goes back to a budget (Gemini 3+ refuses it).
        assertEquals(Options.THINKING_LEVEL_LOW, GeminiRequestRules.stepDown(Options.THINKING_LEVEL_MINIMAL))
    }

    @Test fun temperatureIsLeftOutWhereGoogleIgnoresIt() {
        assertTrue(GeminiRequestRules.sendsTemperature("gemini-2.5-flash"))
        assertTrue(GeminiRequestRules.sendsTemperature("gemini-3.5-flash"))
        assertTrue(GeminiRequestRules.sendsTemperature("gemini-flash-latest"))
        assertFalse(GeminiRequestRules.sendsTemperature("gemini-3.6-flash"))
        assertFalse(GeminiRequestRules.sendsTemperature("gemini-4-carbon"))
    }

    // ---- advice ------------------------------------------------------------------------------------

    @Test fun recommendedKeepsCuratedOrderAndOnlyWhatTheKeyHolds() {
        val listed = listOf("gemma-4-26b-a4b-it", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-9-unknown")
        assertEquals(listOf("gemini-flash-latest", "gemini-3.5-flash-lite"), GeminiModelAdvice.recommended(listed))
        // No list fetched yet: only the aliases, which always exist.
        assertEquals(listOf("gemini-flash-latest", "gemini-flash-lite-latest"), GeminiModelAdvice.recommended(null))
    }

    @Test fun listPutsRecommendedFirstWithoutDroppingTheRest() {
        val models = listOf("gemma-4-26b-a4b-it", "gemini-9-unknown", "gemini-3.5-flash-lite").map { GeminiEngine.Model(it, it) }
        assertEquals(listOf("gemini-3.5-flash-lite", "gemma-4-26b-a4b-it", "gemini-9-unknown"), GeminiModelAdvice.ordered(models).map { it.id })
    }

    @Test fun anAlternativeSkipsTheFailedModelAndOnesThatFailedLately() {
        var clock = 1_000_000L
        val health = ModelHealth { clock }
        val listed = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-3.5-flash-lite")
        assertEquals("gemini-flash-lite-latest", GeminiModelAdvice.alternative("gemini-flash-latest", listed, health))

        health.failed("gemini-flash-lite-latest")
        assertEquals("gemini-3.5-flash-lite", GeminiModelAdvice.alternative("gemini-flash-latest", listed, health))

        // An overload is usually over within minutes: the model is suggested again after the quiet time.
        clock += ModelHealth.QUIET_MS + 1
        assertEquals("gemini-flash-lite-latest", GeminiModelAdvice.alternative("gemini-flash-latest", listed, health))
    }

    @Test fun noAlternativeWhenEveryOtherModelFailedLately() {
        val health = ModelHealth { 5L }
        health.failed("gemini-flash-lite-latest")
        assertNull(GeminiModelAdvice.alternative("gemini-flash-latest", null, health))
    }

    @Test fun successIsRemembered() {
        val health = ModelHealth { 5L }
        health.succeeded("gemini-flash-latest")
        assertTrue(health.workedLately("gemini-flash-latest"))
        assertFalse(health.failedLately("gemini-flash-latest"))
    }
}
