package com.smnexstudio.panelglass.core.engine.llm

/**
 * Which Gemini models to put in front of the user, without spending the user's quota to find out.
 *
 * `models.list` is metadata, not a generation call, so it costs nothing, but it does not prove a model can answer
 * (a listed model can still be overloaded or closed to the key). So the advice rests on two free things: a short
 * curated order, filtered by what the key's own list holds, and what the app's real calls just did ([ModelHealth]).
 * No request is ever sent to probe a model.
 */
object GeminiModelAdvice {
    /**
     * Best first for translating bubbles: the `-latest` aliases follow Google's current Flash and Flash-Lite without
     * an app update, then dated ids. Ids the key's list does not hold are skipped, so a retired one costs nothing.
     */
    val CURATED = listOf(
        "gemini-flash-latest",
        "gemini-flash-lite-latest",
        "gemini-3.5-flash-lite",
        "gemini-3.5-flash",
        "gemini-3.6-flash",
        "gemini-2.5-flash-lite",
    )

    /** The curated ids the key can call, in curated order. [listed] null (no list fetched) trusts the aliases only. */
    fun recommended(listed: Collection<String>?): List<String> =
        if (listed == null) CURATED.filter { it.endsWith("-latest") } else CURATED.filter { it in listed }

    /** [models] with the recommended ones first (in curated order), the rest as they came. */
    fun ordered(models: List<GeminiEngine.Model>): List<GeminiEngine.Model> {
        val rec = recommended(models.map { it.id })
        val byId = models.associateBy { it.id }
        return rec.mapNotNull { byId[it] } + models.filter { it.id !in rec }
    }

    /**
     * A model to suggest instead of [current], which just failed: the first recommended one that is not [current] and
     * did not fail lately. Null when there is none, so the caller shows no button.
     */
    fun alternative(current: String, listed: Collection<String>?, health: ModelHealth): String? =
        recommended(listed).firstOrNull { it != current && !health.failedLately(it) }
}

/**
 * What the app's own calls last saw of each model, in memory: a failed model is not suggested again for [QUIET_MS]
 * (an overload is usually over within minutes, and suggesting the model that just failed is no help).
 */
class ModelHealth(private val now: () -> Long = System::currentTimeMillis) {
    private class Seen(val ok: Boolean, val at: Long)
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Seen>()

    fun succeeded(model: String) { seen[model] = Seen(true, now()) }
    fun failed(model: String) { seen[model] = Seen(false, now()) }

    /** The last call to [model] failed less than [QUIET_MS] ago. */
    fun failedLately(model: String): Boolean = seen[model]?.let { !it.ok && now() - it.at < QUIET_MS } ?: false

    /** The last call to [model] worked less than [QUIET_MS] ago. */
    fun workedLately(model: String): Boolean = seen[model]?.let { it.ok && now() - it.at < QUIET_MS } ?: false

    companion object { const val QUIET_MS = 10 * 60_000L }
}
