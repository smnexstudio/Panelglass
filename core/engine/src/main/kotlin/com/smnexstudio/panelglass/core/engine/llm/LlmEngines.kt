package com.smnexstudio.panelglass.core.engine.llm

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.PlannedEngine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini API (Generative Language). Model id from Settings; Gemini models get thinking budget zero and JSON response
 * mode. The same endpoint serves Gemma, which accepts neither those options nor a system instruction: for a `gemma-`
 * model the system prompt rides at the top of the first user turn and the reply's JSON is found by [LlmContract.parse].
 *
 * The model field often holds a display name ("Gemma 4 26B") rather than an id; such a name is resolved against the
 * key's own `models.list` once per run instead of being sent (the API rejects it as "unexpected model name format").
 */
@Singleton
class GeminiEngine @Inject constructor(support: HttpEngineSupport, keys: SecureKeyStore, private val settings: SettingsRepository) : LlmEngine(support, keys) {
    override val id = EngineId.GEMINI
    override val warmUrl = "https://generativelanguage.googleapis.com/"
    /** API root; a `var` so tests can point it at a MockWebServer. */
    var baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * How much of the Gemini-only request surface a model accepts, most to least. Models differ (a Gemma takes none of
     * it; some newer Gemini ids refuse `thinkingBudget` with a bare "invalid argument"), and the API does not say which
     * field it refused, so a 400 steps one level down, once, and the level that worked is remembered per model, across
     * runs ([SettingsRepository.requestProfile]): otherwise the first screen after every launch pays a refused call.
     */
    enum class Options { THINKING_BUDGET_0, THINKING_LEVEL_MINIMAL, JSON_ONLY, PLAIN }
    private val optionsFor = java.util.concurrent.ConcurrentHashMap<String, Options>()

    override suspend fun complete(apiKey: String, system: String, turns: List<Turn>): String {
        val typed = settings.current().modelFor(id)
        if (typed.isBlank()) throw EngineException(EngineFailure.Unavailable(id, "No model configured"))
        val model = resolveModel(apiKey, typed)
        var level = optionsFor[model]
            ?: settings.requestProfile(id, model)?.let { saved -> Options.entries.firstOrNull { it.name == saved } }?.also { optionsFor[model] = it }
            ?: if (model.startsWith("gemma")) Options.PLAIN else Options.THINKING_BUDGET_0
        val images = turns.sumOf { t -> t.images.size + (if (t.image != null) 1 else 0) }
        while (true) {
            val t0 = System.currentTimeMillis()
            try {
                val resp = try {
                    support.post(
                        id, "$baseUrl/models/$model:generateContent",
                        mapOf("x-goog-api-key" to apiKey, "Content-Type" to "application/json"), body(level, system, turns),
                    )
                } finally {
                    // Durations and shapes only: no key, no URL, no page text.
                    runCatching { android.util.Log.i(TAG, "call $model ${level.name} images=$images ${System.currentTimeMillis() - t0}ms") }
                }
                if (optionsFor[model] != level) {
                    optionsFor[model] = level
                    settings.setRequestProfile(id, model, level.name)
                    // Model id and option level only; runCatching because plain JVM tests have no Log.
                    runCatching { android.util.Log.i(TAG, "model $model accepts ${level.name}") }
                }
                return replyText(resp) ?: throw EngineException(EngineFailure.Malformed(id))
            } catch (e: EngineException) {
                val f = e.failure
                val refusedRequest = f is EngineFailure.Unavailable && f.reason.startsWith("HTTP 400")
                if (!refusedRequest || level == Options.PLAIN) throw e
                level = Options.entries[level.ordinal + 1]
            }
        }
    }

    private fun body(level: Options, system: String, turns: List<Turn>) = buildJsonObject {
        val plain = level == Options.PLAIN
        if (!plain) put("system_instruction", obj("parts" to arr(obj("text" to s(system)))))
        put("contents", buildJsonArray {
            for ((k, t) in turns.withIndex()) add(obj(
                "role" to s(if (t.role == "assistant") "model" else "user"),
                "parts" to buildJsonArray {
                    // Without a system instruction the prompt rides at the top of the first user turn.
                    add(obj("text" to s(if (plain && k == 0) system + "\n\n" + t.text else t.text)))
                    t.image?.let { add(obj("inline_data" to obj("mime_type" to s("image/jpeg"), "data" to s(b64(it))))) }
                    // Each crop right after its label, so "Item 3" in the prompt names exactly one image.
                    for ((label, jpeg) in t.images) {
                        add(obj("text" to s(label)))
                        add(obj("inline_data" to obj("mime_type" to s("image/jpeg"), "data" to s(b64(jpeg)))))
                    }
                },
            ))
        })
        put("generationConfig", when (level) {
            Options.THINKING_BUDGET_0 -> obj("temperature" to n(0.2), "responseMimeType" to s("application/json"), "thinkingConfig" to obj("thinkingBudget" to n(0)))
            Options.THINKING_LEVEL_MINIMAL -> obj("temperature" to n(0.2), "responseMimeType" to s("application/json"), "thinkingConfig" to obj("thinkingLevel" to s("minimal")))
            Options.JSON_ONLY -> obj("temperature" to n(0.2), "responseMimeType" to s("application/json"))
            Options.PLAIN -> obj("temperature" to n(0.2))
        })
    }

    /** A text model the key can call: its API id and Google's display name. */
    data class Model(val id: String, val displayName: String)

    /**
     * The key's text-generation models (Gemini and Gemma), for the Settings dropdown. [apiKey] is a key typed but not
     * yet saved; null uses the saved one. Image, speech, embedding and live-audio models are left out.
     */
    suspend fun listModels(apiKey: String? = null): List<Model> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: keys.get(id) ?: throw EngineException(EngineFailure.MissingKey(id))
        return fetchModels(key)
            .filter { (mid, _) -> (mid.startsWith("gemini") || mid.startsWith("gemma")) && NON_TEXT.none { it in mid } }
            .map { (mid, display) -> Model(mid, display) }
            .sortedWith(compareBy<Model>({ !it.id.startsWith("gemini") }, { it.id }))
    }

    private suspend fun fetchModels(apiKey: String): List<Pair<String, String>> {
        val list = support.get(id, "$baseUrl/models?pageSize=1000", mapOf("x-goog-api-key" to apiKey))
        return runCatching { list.jsonObject["models"]!!.jsonArray }.getOrNull().orEmpty().mapNotNull { m ->
            val o = m.objOrEmpty()
            val name = o["name"]?.jsonPrimitive?.content?.removePrefix("models/") ?: return@mapNotNull null
            val methods = runCatching { o["supportedGenerationMethods"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
            if (methods != null && "generateContent" !in methods) return@mapNotNull null
            name to (o["displayName"]?.jsonPrimitive?.content ?: name)
        }
    }

    /** The answer's text parts joined; parts flagged `thought` (a thinking model's reasoning) are not the answer. */
    private fun replyText(resp: JsonElement): String? {
        val parts = runCatching { resp.jsonObject["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray }.getOrNull() ?: return null
        val text = parts.mapNotNull { p ->
            val o = p.objOrEmpty()
            if (o["thought"]?.jsonPrimitive?.content == "true") null else o["text"]?.jsonPrimitive?.content
        }.joinToString("")
        return text.ifBlank { null }
    }

    /** An API id as typed (`models/` prefix dropped), or the id whose display name / id matches what was typed. */
    private suspend fun resolveModel(apiKey: String, typed: String): String {
        val t = typed.trim().removePrefix("models/")
        if (MODEL_ID.matches(t)) return t
        resolved[t]?.let { return it }
        val models = fetchModels(apiKey)
        val match = matchModel(t, models)
            ?: throw EngineException(EngineFailure.Unavailable(id, "No model named \"$t\" for this key" +
                (suggestions(t, models)?.let { "; try $it" } ?: "")))
        resolved[t] = match
        return match
    }

    companion object {
        private const val TAG = "GeminiEngine"
        private val MODEL_ID = Regex("^[a-z0-9][a-z0-9.\\-]*$")
        /** Id fragments of models that do not translate text: image, speech, embedding, live audio, agents. */
        private val NON_TEXT = listOf("image", "tts", "embedding", "audio", "live", "robotics", "computer-use", "veo", "imagen")

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /**
         * [models] are (id, displayName). Exact match on either (ignoring case, spaces and punctuation) wins; otherwise
         * the shortest id that starts with what was typed ("Gemma 4 26B" → `gemma-4-26b-…-it`).
         */
        fun matchModel(typed: String, models: List<Pair<String, String>>): String? {
            val want = norm(typed)
            if (want.isEmpty()) return null
            models.firstOrNull { (id, display) -> norm(id) == want || norm(display) == want }?.let { return it.first }
            return models.filter { (id, display) -> norm(id).startsWith(want) || norm(display).startsWith(want) }
                .minByOrNull { it.first.length }?.first
        }

        /** A few ids sharing the typed name's first word, for the error line. */
        private fun suggestions(typed: String, models: List<Pair<String, String>>): String? {
            val head = norm(typed.trim().split(' ', '-').firstOrNull().orEmpty())
            if (head.isEmpty()) return null
            return models.map { it.first }.filter { norm(it).startsWith(head) }.sortedBy { it.length }.take(3)
                .takeIf { it.isNotEmpty() }?.joinToString(", ")
        }
    }
}

/** Claude Messages API over raw HTTP. Model id from Settings (Haiku 4.5 by default: the fast tier, thinking off). */
@Singleton
@PlannedEngine("Temporarily hidden: verify Messages API + model list on a device, add a model dropdown")
class ClaudeEngine @Inject constructor(support: HttpEngineSupport, keys: SecureKeyStore, private val settings: SettingsRepository) : LlmEngine(support, keys) {
    override val id = EngineId.CLAUDE
    override val warmUrl = "https://api.anthropic.com/"

    override suspend fun complete(apiKey: String, system: String, turns: List<Turn>): String {
        val model = settings.current().modelFor(id)
        if (model.isBlank()) throw EngineException(EngineFailure.Unavailable(id, "No model configured"))
        val body = buildJsonObject {
            put("model", s(model))
            put("max_tokens", n(4096))
            put("temperature", n(0.2))
            put("system", s(system))
            put("messages", buildJsonArray {
                for (t in turns) add(obj(
                    "role" to s(t.role),
                    "content" to buildJsonArray {
                        t.image?.let { add(obj("type" to s("image"), "source" to obj("type" to s("base64"), "media_type" to s("image/jpeg"), "data" to s(b64(it))))) }
                        add(obj("type" to s("text"), "text" to s(t.text)))
                    },
                ))
            })
        }
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01", "Content-Type" to "application/json")
        val resp = support.post(id, "https://api.anthropic.com/v1/messages", headers, body)
        val content = resp.jsonObject["content"]?.jsonArray ?: throw EngineException(EngineFailure.Malformed(id))
        return content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.content ?: throw EngineException(EngineFailure.Malformed(id))
    }
}

/**
 * Chat Completions shape shared by OpenAI, DeepSeek and OpenRouter; only the host, model, extra headers
 * and vision support differ. [endpoint] is a `var` so tests can point it at a MockWebServer.
 */
abstract class OpenAiCompatibleEngine(
    support: HttpEngineSupport, keys: SecureKeyStore,
    var endpoint: String,
) : LlmEngine(support, keys) {
    override val warmUrl: String get() = endpoint.substringBefore("/v1/") + "/"

    /** Model id for this call; OpenAI and OpenRouter read it from settings, DeepSeek is fixed. */
    protected abstract suspend fun model(): String

    /** Provider-specific headers beyond Authorization / Content-Type. */
    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    override suspend fun complete(apiKey: String, system: String, turns: List<Turn>): String {
        val model = model()
        if (model.isBlank()) throw EngineException(EngineFailure.Unavailable(id, "No model configured"))
        val body = buildJsonObject {
            put("model", s(model))
            put("temperature", n(0.2))
            put("response_format", obj("type" to s("json_object")).takeIf { !acceptsImages } ?: obj("type" to s("text")))
            put("messages", buildJsonArray {
                add(obj("role" to s("system"), "content" to s(system + "\nWrap the array in an object as {\"items\": [...]} if you must return an object.")))
                for (t in turns) {
                    if (t.image != null && acceptsImages) add(obj(
                        "role" to s(t.role),
                        "content" to arr(
                            obj("type" to s("text"), "text" to s(t.text)),
                            obj("type" to s("image_url"), "image_url" to obj("url" to s("data:image/jpeg;base64," + b64(t.image)))),
                        ),
                    )) else add(obj("role" to s(t.role), "content" to s(t.text)))
                }
            })
        }
        val headers = HashMap<String, String>(extraHeaders())
        headers["Authorization"] = "Bearer $apiKey"
        headers["Content-Type"] = "application/json"
        val resp = support.post(id, endpoint, headers, body)
        return resp.str("choices", "0", "message", "content") ?: throw EngineException(EngineFailure.Malformed(id))
    }
}

@Singleton
@PlannedEngine("Temporarily hidden: verify on a device, add a model dropdown from /v1/models")
class OpenAiEngine @Inject constructor(support: HttpEngineSupport, keys: SecureKeyStore, private val settings: SettingsRepository) :
    OpenAiCompatibleEngine(support, keys, "https://api.openai.com/v1/chat/completions") {
    override val id = EngineId.OPENAI
    override suspend fun model() = settings.current().modelFor(id)
}

@Singleton
@PlannedEngine("Temporarily hidden: verify on a device")
class DeepSeekEngine @Inject constructor(support: HttpEngineSupport, keys: SecureKeyStore) :
    OpenAiCompatibleEngine(support, keys, "https://api.deepseek.com/v1/chat/completions") {
    override val id = EngineId.DEEPSEEK
    override val acceptsImages: Boolean get() = false
    override suspend fun model() = "deepseek-chat"
}

/**
 * OpenRouter: OpenAI-compatible gateway to whatever model id the user typed in Settings
 * (`openai/gpt-4o-mini`, `anthropic/claude-3.5-haiku`, `qwen/qwen-2.5-72b-instruct`, ...).
 */
@Singleton
@PlannedEngine("Temporarily hidden: verify on a device, model dropdown from /api/v1/models")
class OpenRouterEngine @Inject constructor(
    support: HttpEngineSupport, keys: SecureKeyStore,
    private val settings: SettingsRepository,
) : OpenAiCompatibleEngine(support, keys, "https://openrouter.ai/api/v1/chat/completions") {
    override val id = EngineId.OPENROUTER
    override suspend fun model() = settings.current().modelFor(id)
    // OpenRouter asks callers to identify the app; these are public strings, not secrets.
    override fun extraHeaders() = mapOf("HTTP-Referer" to "https://github.com/smnexstudio/panelglass", "X-Title" to "Panelglass")
}

internal fun JsonElement.objOrEmpty(): JsonObject = this as? JsonObject ?: buildJsonObject { }
