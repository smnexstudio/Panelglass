package com.smnexstudio.panelglass.core.engine.mt

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.smnexstudio.panelglass.core.engine.TranslationEngine
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.TextNorm
import com.smnexstudio.panelglass.core.model.TranslateRequest
import com.smnexstudio.panelglass.core.model.TranslatedItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** One ML Kit translator for a language pair, behind an interface so JVM tests never load ML Kit. */
interface PairTranslator {
    /** Downloads the language packs on first use; the interface hides ML Kit's Task API. */
    suspend fun prepare()
    suspend fun translate(text: String): String
    /** Frees the native translator; the instance is not used again. */
    fun close() {}
}

/**
 * "Google Translate" in the UI: Google's ML Kit on-device translation. Free, no key, works offline once the
 * ~30 MB language packs for the pair are downloaded (on first use, any network — same rule as the Qwen model).
 * Text only; ML Kit has a single Chinese model, so Traditional Chinese is translated as Chinese.
 */
@Singleton
class GoogleTranslateEngine @Inject constructor() : TranslationEngine {
    override val id = EngineId.GOOGLE
    override val acceptsImages = false

    private val translators = HashMap<String, PairTranslator>()
    private val ready = HashSet<String>()
    private val lock = Mutex()

    /** Production builds ML Kit clients; tests swap in a scripted translator. */
    var translatorFactory: (src: Lang, tgt: Lang) -> PairTranslator = { src, tgt -> MlKitPairTranslator(tag(src), tag(tgt)) }

    private suspend fun translator(src: Lang, tgt: Lang): PairTranslator {
        val key = src.code + ">" + tgt.code
        return lock.withLock {
            val t = translators.getOrPut(key) { translatorFactory(src, tgt) }
            if (key !in ready) {
                try { t.prepare() } catch (e: Exception) {
                    throw EngineException(EngineFailure.Unavailable(id, "Language pack download failed"), e)
                }
                ready += key
            }
            t
        }
    }

    override suspend fun warmUp(src: Lang, tgt: Lang) { runCatching { translator(src, tgt) } }

    override suspend fun translate(req: TranslateRequest): List<TranslatedItem> {
        if (req.items.isEmpty()) return emptyList()
        val t = translator(req.src, req.tgt)
        val ja = req.src == Lang.JA
        // Names are found across the whole request and remembered for the session: a viewport is one request, and the
        // bubble that shows a word is a name (レントさん) is often not in the same one as the bubble that only uses it.
        val names = if (ja) learnNames(JaPrep.names(req.items.map { it.text })) else emptyMap()
        return coroutineScope {
            req.items.map { item ->
                async {
                    val input = sourceGlossary(if (ja) JaPrep.prepare(item.text, names) else item.text, req.glossary)
                    val out = try { t.translate(input) } catch (e: Exception) {
                        throw EngineException(EngineFailure.Unavailable(id, "Translation failed"), e)
                    }
                    TranslatedItem(item.i, applyGlossary(echoFallback(item.text, out, req), req.glossary))
                }
            }.map { it.await() }
        }
    }

    /** Names seen this session (katakana → romanisation), most recent last; bounded so a long session cannot grow it. */
    private val knownNames = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_NAMES
    }

    private fun learnNames(found: Map<String, String>): Map<String, String> = synchronized(knownNames) {
        knownNames.putAll(found)
        LinkedHashMap(knownNames)
    }

    /**
     * ML Kit hands a short Japanese sound word (はっ, へっ) back unchanged. Into a Latin-script language its
     * romanisation reads better than Japanese script left in the bubble.
     */
    private fun echoFallback(source: String, out: String, req: TranslateRequest): String {
        if (req.src != Lang.JA || req.tgt !in LATIN_TARGETS || !JaPrep.isKanaOnly(source)) return out
        val letters = out.filter { it.isLetter() }
        val echoed = letters.isNotEmpty() && letters.count(TextNorm::isCjk) * 2 > letters.length
        return if (echoed) Romaji.of(JaPrep.normalize(source).trim(), capitalize = true) else out
    }

    /** Glossary entries keyed by source text (ルフィ → Luffy) go in before translation, so the model never sees them. */
    private fun sourceGlossary(text: String, glossary: Map<String, String>): String {
        var s = text
        for ((k, v) in glossary) if (k.isNotBlank() && k.any(TextNorm::isCjk)) s = s.replace(k, v)
        return s
    }

    /** Entries keyed by target-language text (Demon King → Maou) can only be substituted after the fact. */
    private fun applyGlossary(text: String, glossary: Map<String, String>): String {
        var s = text
        for ((k, v) in glossary) if (k.isNotBlank()) s = s.replace(k, v, ignoreCase = true)
        return s
    }

    companion object {
        private const val MAX_NAMES = 200
        private val LATIN_TARGETS = setOf(Lang.EN, Lang.ES, Lang.FR, Lang.DE, Lang.IT, Lang.PT, Lang.ID, Lang.VI)

        /** ML Kit language tag for [lang]; both Chinese variants map to ML Kit's single Chinese model. */
        fun tag(lang: Lang): String = when (lang) {
            Lang.ZH, Lang.ZH_TW -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.fromLanguageTag(lang.code) ?: TranslateLanguage.ENGLISH
        }
    }
}

/** ML Kit translator between two ML Kit language tags ([TranslateLanguage]). */
internal class MlKitPairTranslator(srcTag: String, tgtTag: String) : PairTranslator {
    private val client: Translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(srcTag).setTargetLanguage(tgtTag).build(),
    )

    override suspend fun prepare() { client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
    override suspend fun translate(text: String): String = client.translate(text).await()
    override fun close() = client.close()
}
