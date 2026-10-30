package com.smnexstudio.panelglass.core.engine.llm

import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.CustomEndpoint
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.Settings
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own OpenAI-compatible server (the "Custom AI" engine).
 *
 * The base address and the model id come from Settings, the key from the key store. The request is the plain Chat
 * Completions shape with text only: the on-device OCR does the reading, so no image, crop or SFX montage is ever
 * attached. The address is used exactly as the user wrote it — no version segment is appended — and the completions
 * path is added by this engine, so both `https://host/v1` and a pasted `https://host/v1/chat/completions` work.
 *
 * Some compatible servers refuse the optional `temperature` field with a 400. The request is then sent once more
 * without it; that is the only request shape this engine changes.
 */
@Singleton
class CustomOpenAiEngine @Inject constructor(
    support: HttpEngineSupport,
    keys: SecureKeyStore,
    private val settings: SettingsRepository,
) : LlmEngine(support, keys) {
    override val id = EngineId.CUSTOM
    /** Text only: the crops and the SFX montage stay on the phone. */
    override val acceptsImages: Boolean get() = false

    /** The address of the last call, so the connection warm-up reaches the same host. */
    @Volatile private var lastBase: String = ""
    override val warmUrl: String get() = lastBase.trimEnd('/') + "/"

    /**
     * Lets a test point the engine at a plain HTTP MockWebServer. Production never sets it: the address must be
     * HTTPS, and the switch is off unless a test turns it on.
     */
    var allowHttpForTests: Boolean = false

    /** The normalized base address from Settings, or null when the user has not set a usable one. */
    private fun baseUrl(s: Settings): String? = CustomEndpoint.normalize(s.customBaseUrl, allowHttpForTests)

    override suspend fun warmUp(src: Lang, tgt: Lang) {
        val base = baseUrl(settings.current()) ?: return
        lastBase = base
        support.warm(warmUrl)
    }

    override suspend fun complete(apiKey: String, system: String, turns: List<Turn>, maxOutputTokens: Int): String {
        val s = settings.current()
        val model = s.modelFor(id)
        if (model.isBlank()) throw EngineException(EngineFailure.Unavailable(id, "No model configured"))
        val base = baseUrl(s) ?: throw EngineException(EngineFailure.Unavailable(id, "No server address configured"))
        lastBase = base
        val url = CustomEndpoint.completionsUrl(base)

        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
        )
        val reply = try {
            support.post(id, url, headers, body(model, system, turns, maxOutputTokens, withTemperature = true))
        } catch (e: EngineException) {
            // A 400 that names the request (not the key) is the server refusing a field of ours: send it once more
            // without `temperature`, the one optional field some compatible servers refuse.
            val refused = e.failure as? EngineFailure.Unavailable
            if (refused == null || !refused.reason.startsWith("HTTP 400")) throw e
            support.post(id, url, headers, body(model, system, turns, maxOutputTokens, withTemperature = false))
        }
        return reply.str("choices", "0", "message", "content") ?: throw EngineException(EngineFailure.Malformed(id))
    }

    /** The Chat Completions request: the model, the system prompt, the turns as plain text messages. */
    private fun body(model: String, system: String, turns: List<Turn>, maxOutputTokens: Int, withTemperature: Boolean) = buildJsonObject {
        put("model", s(model))
        put("max_tokens", n(maxOutputTokens))
        if (withTemperature) put("temperature", n(0.2))
        put("messages", buildJsonArray {
            add(obj("role" to s("system"), "content" to s(system)))
            for (t in turns) add(obj("role" to s(t.role), "content" to s(t.text)))
        })
    }
}
