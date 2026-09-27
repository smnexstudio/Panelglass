package com.smnexstudio.panelglass.core.engine.mt

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** Names the language of a text, behind an interface so JVM tests never load ML Kit. */
fun interface LanguageDetector {
    /** A BCP-47 tag ("ja", "zh-Latn"), or null when the language cannot be told. */
    suspend fun identify(text: String): String?
}

/**
 * The reader's "Translate page": the page's own text (titles, chapter lists, comments), not the bubbles in its
 * images. ML Kit on-device translation between any two of ML Kit's languages, keyed by ML Kit tags
 * ([TranslateLanguage]) rather than [com.smnexstudio.panelglass.core.model.Lang], since a web page can be in a
 * language the bubble pipeline does not offer. The source can be detected with ML Kit language identification.
 */
@Singleton
class PageTranslator @Inject constructor() {
    /** Production builds ML Kit clients; tests swap in scripted ones. */
    var translatorFactory: (srcTag: String, tgtTag: String) -> PairTranslator = { s, t -> MlKitPairTranslator(s, t) }
    var detector: LanguageDetector = MlKitLanguageDetector()

    private val translators = LinkedHashMap<String, PairTranslator>()
    private val ready = HashSet<String>()
    private val lock = Mutex()

    /**
     * The ML Kit tag of [sample]'s language, or null. [hint] is the page's `<html lang>`, used only when the text
     * itself is inconclusive (too short, mixed scripts).
     */
    suspend fun detect(sample: String, hint: String?): String? {
        val found = if (sample.isBlank()) null else runCatching { detector.identify(sample) }.getOrNull()
        return translatable(found) ?: translatable(hint)
    }

    /** Downloads the pair's language packs if needed (~30 MB each, on first use of a language). */
    suspend fun prepare(srcTag: String, tgtTag: String) { translator(srcTag, tgtTag) }

    /**
     * Translates [texts] in order. A text that fails alone keeps its original; a failure of every text (or of the
     * language pack download) is an [EngineException], so the caller can say so instead of showing nothing.
     */
    suspend fun translate(srcTag: String, tgtTag: String, texts: List<String>): List<String> {
        if (texts.isEmpty() || srcTag == tgtTag) return texts
        val t = translator(srcTag, tgtTag)
        val out = coroutineScope {
            texts.map { text -> async { runCatching { t.translate(text) }.getOrNull() } }.map { it.await() }
        }
        if (out.all { it == null }) throw EngineException(EngineFailure.Unavailable(EngineId.GOOGLE, "Translation failed"))
        return out.mapIndexed { i, s -> s ?: texts[i] }
    }

    private suspend fun translator(srcTag: String, tgtTag: String): PairTranslator {
        val key = "$srcTag>$tgtTag"
        return lock.withLock {
            val t = translators.remove(key) ?: translatorFactory(srcTag, tgtTag)
            translators[key] = t // most recently used last
            if (key !in ready) {
                try { t.prepare() } catch (e: Exception) {
                    throw EngineException(EngineFailure.Unavailable(EngineId.GOOGLE, "Language pack download failed"), e)
                }
                ready += key
            }
            // A few pairs at most: each holds a native translator.
            while (translators.size > MAX_PAIRS) {
                val eldest = translators.keys.first()
                translators.remove(eldest)?.close()
                ready -= eldest
            }
            t
        }
    }

    companion object {
        private const val MAX_PAIRS = 3

        /** Every language ML Kit translates, as ML Kit tags. */
        fun languages(): List<String> = TranslateLanguage.getAllLanguages()

        /** [tag] as an ML Kit translation language, or null ("und", romanised text such as "ja-Latn", unsupported). */
        fun translatable(tag: String?): String? {
            if (tag.isNullOrBlank() || tag == "und") return null
            val parts = tag.lowercase().split('-', '_')
            if ("latn" in parts.drop(1) && parts[0] != "en") return null
            return TranslateLanguage.fromLanguageTag(parts[0])
        }
    }
}

private class MlKitLanguageDetector : LanguageDetector {
    private val client by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build())
    }

    override suspend fun identify(text: String): String? =
        client.identifyLanguage(text.take(2_000)).await().takeIf { it != "und" }
}
