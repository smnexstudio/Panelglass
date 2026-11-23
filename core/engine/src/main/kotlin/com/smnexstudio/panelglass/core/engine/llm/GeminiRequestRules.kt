package com.smnexstudio.panelglass.core.engine.llm

/**
 * Which request options a Gemini model id accepts, by family. Translating a speech bubble needs no reasoning, so the
 * request asks for the least thinking each family allows (Google's thinking guide, checked 2026-11-23):
 *
 * - Gemini 2.5 takes `thinkingBudget`: 0 turns thinking off on Flash and Flash-Lite, Pro cannot go below 128.
 * - Gemini 3 and later take `thinkingLevel` (`thinkingBudget` is deprecated there, and a 400 on Gemini 4). The lowest is
 *   MINIMAL on 3 Flash, 3.5 and 3.6 Flash and the Flash-Lites, LOW on every Pro, on 3.7 / 3.8 Flash and on Gemini 4.
 * - Gemini 4 and 3.6+ ignore `temperature` (a later release will refuse it).
 *
 * This only picks where the ladder starts. A model the table gets wrong answers 400 and [stepDown] moves it on, and
 * the rung that worked is remembered per model ([GeminiEngine.Options]), so the table can be out of date safely.
 */
internal object GeminiRequestRules {
    private val VERSION = Regex("""gemini-(\d+)(?:\.(\d+))?""")

    private class Version(val major: Int, val minor: Int)

    private fun versionOf(model: String): Version? = VERSION.find(model.lowercase())?.let {
        Version(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0)
    }

    /** The first rung to try for [model]. */
    fun start(model: String): GeminiEngine.Options {
        val id = model.lowercase()
        if (id.startsWith("gemma")) return GeminiEngine.Options.PLAIN
        val v = versionOf(id)
        val pro = "pro" in id
        return when {
            // `gemini-flash-latest` and friends carry no number: a Pro alias is LOW, anything else the Flash minimum.
            v == null -> if (pro) GeminiEngine.Options.THINKING_LEVEL_LOW else GeminiEngine.Options.THINKING_LEVEL_MINIMAL
            v.major <= 2 -> if (pro) GeminiEngine.Options.THINKING_BUDGET_128 else GeminiEngine.Options.THINKING_BUDGET_0
            pro || v.major >= 4 || (v.major == 3 && v.minor >= 7) -> GeminiEngine.Options.THINKING_LEVEL_LOW
            else -> GeminiEngine.Options.THINKING_LEVEL_MINIMAL
        }
    }

    /**
     * The rung after [level] when the model refused it with a 400. Budgets and levels are different parameters, so a
     * budget that failed is followed by the levels (and never the other way round: a level refusal means a Pro or a
     * newer model, which no budget would suit). Past the levels thinking is left at the model's default.
     */
    fun stepDown(level: GeminiEngine.Options): GeminiEngine.Options? = when (level) {
        GeminiEngine.Options.THINKING_BUDGET_0 -> GeminiEngine.Options.THINKING_BUDGET_128
        GeminiEngine.Options.THINKING_BUDGET_128 -> GeminiEngine.Options.THINKING_LEVEL_LOW
        GeminiEngine.Options.THINKING_LEVEL_MINIMAL -> GeminiEngine.Options.THINKING_LEVEL_LOW
        GeminiEngine.Options.THINKING_LEVEL_LOW -> GeminiEngine.Options.JSON_ONLY
        GeminiEngine.Options.JSON_ONLY -> GeminiEngine.Options.PLAIN
        GeminiEngine.Options.PLAIN -> null
    }

    /** Whether to send `temperature`: Gemini 4 and 3.6+ ignore it and will refuse it. */
    fun sendsTemperature(model: String): Boolean {
        val v = versionOf(model) ?: return true
        return !(v.major >= 4 || (v.major == 3 && v.minor >= 6))
    }
}
