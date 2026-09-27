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

    @Test
    fun coversEveryAppLanguageOnceWithChineseVariantsSharingAPack() {
        assertEquals(Lang.entries.size - 1, LanguagePackStore.ALL_TAGS.size)
        assertTrue("zh" in LanguagePackStore.ALL_TAGS && "ja" in LanguagePackStore.ALL_TAGS && "en" in LanguagePackStore.ALL_TAGS)
    }

    @Test
    fun downloadAllSkipsPacksAlreadyPresentAndEndsComplete() = runBlocking {
        val m = Scripted(setOf("en", "ja"))
        val store = LanguagePackStore().apply { manager = m }
        store.downloadAll()
        val done = withTimeout(5_000) { store.state.first { !it.downloading && it.downloaded.size == it.total } }
        assertTrue(done.complete)
        assertFalse("en" in m.requested); assertFalse("ja" in m.requested)
        assertEquals(LanguagePackStore.ALL_TAGS - setOf("en", "ja"), m.requested.toSet())
    }

    @Test
    fun failureKeepsProgressAndReportsTyped() = runBlocking {
        val m = Scripted(emptySet(), failOn = "ko")
        val store = LanguagePackStore().apply { manager = m }
        store.downloadAll()
        val s = withTimeout(5_000) { store.state.first { it.failed != null } }
        assertFalse(s.downloading)
        assertTrue(s.downloaded.isNotEmpty() && s.downloaded.size < s.total)
    }
}
