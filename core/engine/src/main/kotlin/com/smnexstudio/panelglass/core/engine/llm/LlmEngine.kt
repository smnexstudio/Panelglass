package com.smnexstudio.panelglass.core.engine.llm

import android.util.Base64
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
/**
 * A conversation turn. [image] is JPEG bytes attached to a user turn (the SFX montage); [images] are labelled JPEG
 * crops ("Item 3") the engine reads itself, sent only by providers that take several images per turn (Gemini).
 */
class Turn(val role: String, val text: String, val image: ByteArray? = null, val images: List<Pair<String, ByteArray>> = emptyList())

/**
 * Base for chat-completion style providers. Owns the contract handling: build prompt, call,
 * strict-parse, and on a malformed reply retry exactly once with the repair prompt.
 */
abstract class LlmEngine(
    protected val support: HttpEngineSupport,
    protected val keys: SecureKeyStore,
) : TranslationEngine {
    override val acceptsImages: Boolean get() = true
    override val usesContext: Boolean get() = true

    /** Provider host used for the pre-warm TLS handshake. */
    protected abstract val warmUrl: String

    /** One call. [maxOutputTokens] caps the reply ([LlmContract.outputBudget]): the provider bills what it writes. */
    protected abstract suspend fun complete(apiKey: String, system: String, turns: List<Turn>, maxOutputTokens: Int): String

    /**
     * Whether the retry must stand on its own.
     *
     * A server at home may rewrite a multi-turn history — dropping the original input, reversing the order — so a
     * retry that relies on the conversation cannot work there. Such an engine gets one fresh request that carries
     * the input with it. The hosted providers keep the conversation: they follow it correctly, and their own tests
     * depend on it.
     */
    protected open val selfContainedRetry: Boolean get() = false

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> {
        if (req.items.isEmpty()) return emptyList()
        val key = keys.get(id) ?: throw EngineException(EngineFailure.MissingKey(id))
        val system = LlmContract.systemPrompt(req)
        val montage = if (acceptsImages) req.sfxMontage else null
        val crops = if (acceptsImages) req.readImages.entries.sortedBy { it.key }.map { "Item ${it.key}" to it.value } else emptyList()
        val payload = LlmContract.userPayload(req.items, req.readImages.keys)
        val turns = mutableListOf(Turn("user", payload, montage, crops))
        val budget = LlmContract.outputBudget(req.items.size)
        val first = complete(key, system, turns, budget)
        return try {
            LlmContract.parse(id, first, req.items)
        } catch (e: EngineException) {
            if (e.failure !is EngineFailure.Malformed) throw e
            if (selfContainedRetry) {
                // Keep what the reply got right and ask only for the rest: one fresh request carrying just those
                // items (no assistant turn), so a partly bad reply costs a small second call, not the page again.
                val kept = LlmContract.salvage(first, req.items)
                val missing = req.items.filter { it.i !in kept }
                // Every item was there, only wrapped in junk the strict parse refuses: no second call at all.
                if (missing.isEmpty()) return req.items.map { kept.getValue(it.i) }
                val missingIds = missing.map { it.i }.toSet()
                val read = req.readImages.keys.filter { it in missingIds }.toSet()
                val again = listOf(Turn(
                    "user",
                    LlmContract.retryUserMessage(LlmContract.userPayload(missing, read), read.isNotEmpty()),
                    montage, crops.filter { (label, _) -> label.removePrefix("Item ").toIntOrNull() in missingIds },
                ))
                val rest = LlmContract.parse(id, complete(key, system, again, LlmContract.outputBudget(missing.size)), missing)
                    .associateBy { it.i }
                req.items.map { kept[it.i] ?: rest.getValue(it.i) }
            } else {
                turns += Turn("assistant", first)
                turns += Turn("user", LlmContract.repairPrompt())
                LlmContract.parse(id, complete(key, system, turns, budget), req.items)
            }
        }
    }

    override suspend fun warmUp(src: Lang, tgt: Lang) = support.warm(warmUrl)

    protected fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    protected fun JsonElement.str(vararg path: String): String? {
        var cur: JsonElement = this
        for (p in path) {
            cur = when (cur) {
                is JsonObject -> cur[p] ?: return null
                is JsonArray -> cur.getOrNull(p.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return (cur as? JsonPrimitive)?.content
    }

    protected fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { for ((k, v) in pairs) put(k, v) }
    protected fun arr(vararg items: JsonElement): JsonArray = buildJsonArray { for (i in items) add(i) }
    protected fun s(v: String) = JsonPrimitive(v)
    protected fun n(v: Number) = JsonPrimitive(v)
    protected fun b(v: Boolean) = JsonPrimitive(v)
}
