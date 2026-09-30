package com.smnexstudio.panelglass.core.engine

import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.engine.mt.PackManager
import com.smnexstudio.panelglass.core.model.Lang
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguagePackStoreTest {
    private class Scripted(initial: Set<String>, private val failOn: String? = null) : PackManager {
        val have = initial.toMutableSet()
        val requested = ArrayList<String>()
        override suspend fun downloaded() = have.toSet()
        override suspend fun download(tag: String) { requested += tag; if (tag == failOn) throw IllegalStateException("offline"); have += tag }
        override suspend fun delete(tag: String) { have -= tag }
    }

    private fun store(m: PackManager) = LanguagePackStore().apply { manager = m }

    @Test
    fun coversEveryAppLanguageOnceWithChineseVariantsSharingAPack() {
        assertEquals(Lang.entries.size - 1, LanguagePackStore.ALL_TAGS.size)
        assertTrue("zh" in LanguagePackStore.ALL_TAGS && "ja" in LanguagePackStore.ALL_TAGS && "en" in LanguagePackStore.ALL_TAGS)
    }

    @Test
    fun automaticPacksAreJapaneseKoreanAndChineseOnly() = runBlocking {
        assertEquals(setOf("ja", "ko", "zh"), LanguagePackStore.AUTOMATIC)
        val m = Scripted(setOf("ja"))
        assertTrue(store(m).ensureAutomatic())
        // English is built into ML Kit; Japanese was already there: only Korean and Chinese are fetched.
        assertEquals(setOf("ko", "zh"), m.requested.toSet())
        assertFalse(m.requested.any { it !in LanguagePackStore.AUTOMATIC })
    }

    @Test
    fun otherLanguagesAreMissingUntilTheUserDownloadsThem() = runBlocking {
        val m = Scripted(emptySet())
        val s = store(m)
        // Japanese → English may run (fetched on the fly); Japanese → Hindi needs Hindi first.
        assertEquals(emptySet<String>(), s.missing(setOf("ja", "en")))
        assertEquals(setOf("hi"), s.missing(setOf("ja", "hi")))
        assertEquals(setOf("ar", "es"), s.missing(setOf("es", "ar")))
        assertTrue(m.requested.isEmpty()) // asking never downloads
        assertTrue(s.downloadNow(listOf("hi")))
        assertEquals(emptySet<String>(), s.missing(setOf("ja", "hi")))
    }

    @Test
    fun downloadSkipsPacksAlreadyPresentAndNeverFetchesEnglish() = runBlocking {
        val m = Scripted(setOf("es"))
        val s = store(m)
        s.download(listOf("en", "es", "fr"))
        withTimeout(5_000) { s.state.first { "fr" in it.downloaded && it.pending.isEmpty() } }
        assertEquals(listOf("fr"), m.requested)
    }

    @Test
    fun failureIsReportedForThatLanguageAndLeavesTheOthers() = runBlocking {
        val m = Scripted(emptySet(), failOn = "th")
        val s = store(m)
        assertFalse(s.downloadNow(listOf("th", "vi")))
        val st = s.state.value
        assertEquals("th", st.failed)
        assertTrue(st.has("vi") && !st.has("th") && st.pending.isEmpty() && st.current == null)
    }

    @Test
    fun deleteRemovesOnePackAndNeverEnglish() = runBlocking {
        val m = Scripted(setOf("hi", "ar"))
        val s = store(m)
        s.delete("hi"); s.delete("en")
        withTimeout(5_000) { s.state.first { "hi" !in it.downloaded && "ar" in it.downloaded } }
        assertEquals(setOf("ar"), m.have)
    }
}
