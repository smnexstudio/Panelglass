package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.PackGate
import com.smnexstudio.panelglass.core.engine.mt.PairTranslator
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.EngineException
import com.smnexstudio.panelglass.core.model.EngineFailure
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.RegionItem
import com.smnexstudio.panelglass.core.model.RegionKind
import com.smnexstudio.panelglass.core.model.TranslateRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The "Google Translate" engine is ML Kit on-device; these tests drive it through the [PairTranslator] seam. */
class GoogleTranslateEngineTest {
    private class Scripted(private val map: Map<String, String>, private val failPrepare: Boolean = false) : PairTranslator {
        var prepared = 0
        override suspend fun prepare() { if (failPrepare) throw IllegalStateException("no network"); prepared++ }
        override suspend fun translate(text: String) = map.getValue(text)
    }

    private val items = listOf(
        RegionItem(0, "img", RegionKind.ENCLOSED, "こんにちは"),
        RegionItem(1, "img", RegionKind.ENCLOSED, "ありがとう"),
    )

    @Test
    fun translatesEveryItemAndDownloadsThePackOnce() = runBlocking {
        val scripted = Scripted(mapOf("こんにちは" to "Hello", "ありがとう" to "Thanks"))
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> scripted } }

        val a = engine.translate(TranslateRequest(items, Lang.JA, Lang.EN))
        val b = engine.translate(TranslateRequest(items.take(1), Lang.JA, Lang.EN))

        assertEquals(listOf("Hello", "Thanks"), a.sortedBy { it.i }.map { it.text })
        assertEquals("Hello", b.single().text)
        assertEquals(1, scripted.prepared)
    }

    @Test
    fun aPackTheUserHasNotDownloadedIsATypedFailureAndNothingIsFetched() = runBlocking {
        val scripted = Scripted(mapOf("こんにちは" to "नमस्ते", "ありがとう" to "धन्यवाद"))
        val engine = GoogleTranslateEngine().apply {
            translatorFactory = { _, _ -> scripted }
            packGate = PackGate { tags -> tags.filter { it == "hi" }.toSet() }
        }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(TranslateRequest(items, Lang.JA, Lang.HI)) } }
        assertEquals(EngineFailure.PackMissing(EngineId.GOOGLE, listOf("hi")), e.failure)
        assertEquals(0, scripted.prepared) // no silent download
        // Japanese → English is allowed through the same gate.
        engine.translate(TranslateRequest(items, Lang.JA, Lang.EN))
        assertEquals(1, scripted.prepared)
    }

    @Test
    fun glossaryTermsAreSubstitutedAfterTranslation() = runBlocking {
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> Scripted(mapOf("x" to "the Demon King said")) } }
        val out = engine.translate(TranslateRequest(listOf(RegionItem(0, "img", RegionKind.ENCLOSED, "x")), Lang.JA, Lang.EN, glossary = mapOf("Demon King" to "Maou")))
        assertEquals("the Maou said", out.single().text)
    }

    private fun item(i: Int, text: String) = RegionItem(i, "img", RegionKind.ENCLOSED, text)

    /** The translator sees names romanised the same way in every bubble, and casual negatives made standard. */
    @Test
    fun japaneseInputIsPreparedBeforeTranslation() = runBlocking {
        val seen = ArrayList<String>()
        val recording = object : PairTranslator {
            override suspend fun prepare() {}
            override suspend fun translate(text: String): String { synchronized(seen) { seen += text }; return "ok" }
        }
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> recording } }
        engine.translate(TranslateRequest(listOf(item(0, "レントさんは"), item(1, "そういやレントの姿が見えねぇな")), Lang.JA, Lang.ES))
        assertEquals(setOf("Rento-sanは", "そういやRentoの姿が見えないな"), seen.toSet())
    }

    /** One viewport shows レントさん, a later one only レントの: the name learned from the first is used in the second. */
    @Test
    fun namesLearnedInOneRequestApplyToTheNext() = runBlocking {
        val seen = ArrayList<String>()
        val recording = object : PairTranslator {
            override suspend fun prepare() {}
            override suspend fun translate(text: String): String { synchronized(seen) { seen += text }; return "ok" }
        }
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> recording } }
        engine.translate(TranslateRequest(listOf(item(0, "レントさんは")), Lang.JA, Lang.EN))
        engine.translate(TranslateRequest(listOf(item(0, "レントの姿")), Lang.JA, Lang.EN))
        assertEquals("Rentoの姿", seen.last())
    }

    @Test
    fun anEchoedSoundWordIsRomanisedForLatinTargetsOnly() = runBlocking {
        val echo = object : PairTranslator {
            override suspend fun prepare() {}
            override suspend fun translate(text: String) = text
        }
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> echo } }
        assertEquals("Hah", engine.translate(TranslateRequest(listOf(item(0, "はっ")), Lang.JA, Lang.EN)).single().text)
        assertEquals("はっ", engine.translate(TranslateRequest(listOf(item(0, "はっ")), Lang.JA, Lang.RU)).single().text)
    }

    @Test
    fun sourceGlossaryTermsGoInBeforeTranslation() = runBlocking {
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> Scripted(mapOf("Luffyだ" to "It's Luffy")) } }
        val out = engine.translate(TranslateRequest(listOf(item(0, "ルフィだ")), Lang.JA, Lang.EN, glossary = mapOf("ルフィ" to "Luffy")))
        assertEquals("It's Luffy", out.single().text)
    }

    @Test
    fun koreanPassesThroughUnprepared() = runBlocking {
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> Scripted(mapOf("레온 씨" to "Mr. Leon")) } }
        assertEquals("Mr. Leon", engine.translate(TranslateRequest(listOf(item(0, "레온 씨")), Lang.KO, Lang.EN)).single().text)
    }

    @Test
    fun failedPackDownloadIsATypedUnavailable() = runBlocking {
        val engine = GoogleTranslateEngine().apply { translatorFactory = { _, _ -> Scripted(emptyMap(), failPrepare = true) } }
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.translate(TranslateRequest(items, Lang.JA, Lang.EN)) } }
        assertTrue(e.failure is EngineFailure.Unavailable)
    }
}
