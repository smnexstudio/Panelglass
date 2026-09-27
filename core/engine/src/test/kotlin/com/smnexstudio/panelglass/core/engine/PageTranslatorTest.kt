package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.mt.LanguageDetector
import com.smnexstudio.panelglass.core.engine.mt.PageTranslator
import com.smnexstudio.panelglass.core.engine.mt.PairTranslator
import com.smnexstudio.panelglass.core.model.EngineException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The reader's "Translate page", driven through the [PairTranslator] and detector seams. */
class PageTranslatorTest {
    private class Scripted(private val map: Map<String, String>) : PairTranslator {
        var prepared = 0
        var closed = false
        override suspend fun prepare() { prepared++ }
        override suspend fun translate(text: String) = map[text] ?: throw IllegalStateException("no translation")
        override fun close() { closed = true }
    }

    @Test
    fun keepsOrderAndLeavesAFailedTextAsItWas() = runBlocking {
        val pt = PageTranslator().apply { translatorFactory = { _, _ -> Scripted(mapOf("第1話" to "Chapter 1", "次へ" to "Next")) } }
        assertEquals(listOf("Chapter 1", "???", "Next"), pt.translate("ja", "en", listOf("第1話", "???", "次へ")))
    }

    @Test
    fun everyTextFailingIsAnError() {
        val pt = PageTranslator().apply { translatorFactory = { _, _ -> Scripted(emptyMap()) } }
        assertThrows(EngineException::class.java) { runBlocking { pt.translate("ja", "en", listOf("a", "b")) } }
    }

    @Test
    fun samePairIsPreparedOnceAndOldPairsAreClosed() = runBlocking {
        val made = LinkedHashMap<String, Scripted>()
        val pt = PageTranslator().apply { translatorFactory = { s, t -> Scripted(mapOf("x" to "y")).also { made["$s>$t"] = it } } }
        pt.translate("ja", "en", listOf("x"))
        pt.translate("ja", "en", listOf("x"))
        assertEquals(1, made.getValue("ja>en").prepared)
        for (t in listOf("fr", "de", "es")) pt.translate("ja", t, listOf("x"))
        assertTrue(made.getValue("ja>en").closed)
    }

    @Test
    fun sameLanguageIsReturnedUnchanged() = runBlocking {
        val pt = PageTranslator().apply { translatorFactory = { _, _ -> error("not needed") } }
        assertEquals(listOf("hello"), pt.translate("en", "en", listOf("hello")))
    }

    @Test
    fun detectionFallsBackToThePageLanguageAndRejectsRomanisedText() = runBlocking {
        val pt = PageTranslator()
        pt.detector = LanguageDetector { "ko" }
        assertEquals("ko", pt.detect("안녕하세요", hint = "ja"))
        pt.detector = LanguageDetector { null }
        assertEquals("ja", pt.detect("…", hint = "ja-JP"))
        pt.detector = LanguageDetector { "ja-Latn" }
        assertNull(pt.detect("konnichiwa", hint = null))
    }
}
