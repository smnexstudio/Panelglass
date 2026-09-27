package com.smnexstudio.panelglass.core.engine.mt

import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.PlannedEngine
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** DeepL v2. Free keys (suffix `:fx`) route to the free host. */
@Singleton
@PlannedEngine("Temporarily hidden: verify free vs pro host and glossary context on a device")
class DeepLEngine @Inject constructor(private val support: HttpEngineSupport, private val keys: SecureKeyStore) : TranslationEngine {
    override val id = EngineId.DEEPL
    override val acceptsImages = false

    private fun host(key: String) = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"

    private fun code(lang: Lang, target: Boolean) = when (lang) {
        Lang.EN -> if (target) "EN-US" else "EN"
        Lang.PT -> if (target) "PT-BR" else "PT"
        Lang.ZH -> if (target) "ZH-HANS" else "ZH"
        Lang.ZH_TW -> if (target) "ZH-HANT" else "ZH"
        else -> lang.code.uppercase()
    }

    override suspend fun warmUp(src: Lang, tgt: Lang) { keys.get(id)?.let { support.warm(host(it) + "/") } }

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> {
        if (req.items.isEmpty()) return emptyList()
        val key = keys.get(id) ?: throw EngineException(EngineFailure.MissingKey(id))
        val body = buildJsonObject {
            put("text", buildJsonArray { for (it in req.items) add(JsonPrimitive(it.text)) })
            put("source_lang", JsonPrimitive(code(req.src, false)))
            put("target_lang", JsonPrimitive(code(req.tgt, true)))
            if (req.glossary.isNotEmpty()) put("context", JsonPrimitive("Comic dialogue. Terms: " + req.glossary.entries.joinToString("; ") { it.key + "=" + it.value }))
        }
        val resp = support.post(id, host(key) + "/v2/translate", mapOf("Authorization" to "DeepL-Auth-Key $key", "Content-Type" to "application/json"), body)
        val arr = resp.jsonObject["translations"]?.jsonArray ?: throw EngineException(EngineFailure.Malformed(id))
        if (arr.size != req.items.size) throw EngineException(EngineFailure.Malformed(id))
        return req.items.mapIndexed { idx, item -> TranslatedItem(item.i, arr[idx].jsonObject["text"]?.jsonPrimitive?.content ?: "") }
    }
}

/** Naver Cloud Papago NMT. The stored key is `clientId:clientSecret`; one text per call, four in flight. */
@Singleton
@PlannedEngine("Temporarily hidden: verify NCP endpoint and key pair on a device")
class PapagoEngine @Inject constructor(private val support: HttpEngineSupport, private val keys: SecureKeyStore) : TranslationEngine {
    override val id = EngineId.PAPAGO
    override val acceptsImages = false
    private val gate = Semaphore(4)

    private fun code(lang: Lang) = when (lang) {
        Lang.ZH -> "zh-CN"; Lang.ZH_TW -> "zh-TW"; else -> lang.code
    }

    override suspend fun warmUp(src: Lang, tgt: Lang) = support.warm("https://naveropenapi.apigw.ntruss.com/")

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> {
        if (req.items.isEmpty()) return emptyList()
        val key = keys.get(id) ?: throw EngineException(EngineFailure.MissingKey(id))
        val clientId = key.substringBefore(':'); val secret = key.substringAfter(':', "")
        if (secret.isEmpty()) throw EngineException(EngineFailure.MissingKey(id))
        val headers = mapOf("X-NCP-APIGW-API-KEY-ID" to clientId, "X-NCP-APIGW-API-KEY" to secret, "Content-Type" to "application/json")
        return coroutineScope {
            req.items.map { item ->
                async {
                    gate.withPermit {
                        val body = buildJsonObject {
                            put("source", JsonPrimitive(code(req.src))); put("target", JsonPrimitive(code(req.tgt))); put("text", JsonPrimitive(item.text))
                        }
                        val resp = support.post(id, "https://naveropenapi.apigw.ntruss.com/nmt/v1/translation", headers, body)
                        val text = resp.jsonObject["message"]?.jsonObject?.get("result")?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
                            ?: throw EngineException(EngineFailure.Malformed(id))
                        TranslatedItem(item.i, text)
                    }
                }
            }.map { it.await() }
        }
    }
}
