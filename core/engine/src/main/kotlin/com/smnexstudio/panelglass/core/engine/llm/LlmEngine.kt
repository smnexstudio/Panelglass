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

    protected abstract suspend fun complete(apiKey: String, system: String, turns: List<Turn>): String

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> {
        if (req.items.isEmpty()) return emptyList()
        val key = keys.get(id) ?: throw EngineException(EngineFailure.MissingKey(id))
        val system = LlmContract.systemPrompt(req)
        val montage = if (acceptsImages) req.sfxMontage else null
        val crops = if (acceptsImages) req.readImages.entries.sortedBy { it.key }.map { "Item ${it.key}" to it.value } else emptyList()
        val turns = mutableListOf(Turn("user", LlmContract.userPayload(req.items, req.readImages.keys), montage, crops))
        val first = complete(key, system, turns)
        return try {
            LlmContract.parse(id, first, req.items)
        } catch (e: EngineException) {
            if (e.failure !is EngineFailure.Malformed) throw e
            turns += Turn("assistant", first)
            turns += Turn("user", LlmContract.repairPrompt())
            LlmContract.parse(id, complete(key, system, turns), req.items)
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
