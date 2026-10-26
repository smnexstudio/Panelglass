package com.smnexstudio.panelglass.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.data.cache.PatchCache
import com.smnexstudio.panelglass.core.model.DialogueContext
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory DataStore, so the repository runs under plain JUnit. */
private class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        state.updateAndGet { transform(it) }
}

/** The dialogue-context level and the character sheets: saved, read back, and cleaned. */
class DialogueContextSettingTest {
    private val ds = MemoryDataStore()
    private val repo = SettingsRepository(ds)

    @Test
    fun theLevelIsSavedAndReadBack() = runBlocking {
        repo.setDialogueContext(DialogueContext.DEEP)
        assertEquals(DialogueContext.DEEP, repo.current().dialogueContext)
        repo.setDialogueContext(DialogueContext.OFF)
        assertEquals(DialogueContext.OFF, repo.current().dialogueContext)
    }

    @Test
    fun theLevelSurvivesANewRepositoryOverTheSameStore() = runBlocking {
        repo.setDialogueContext(DialogueContext.STANDARD)
        val again = SettingsRepository(ds)
        assertEquals(DialogueContext.STANDARD, again.current().dialogueContext)
    }

    @Test
    fun anUnknownLevelFallsBackToTheDefault() = runBlocking {
        ds.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey("dialogueContext"), "NONSENSE") } }
        assertEquals(DialogueContext.STANDARD, repo.current().dialogueContext)
    }

    @Test
    fun theLevelReachesTheResolvedConfig() = runBlocking {
        repo.setDialogueContext(DialogueContext.DEEP)
        assertEquals(DialogueContext.DEEP, repo.current().resolve(null).dialogueContext)
    }
}

/** The character sheets: one per website host, cleaned, and removed when cleared. */
class CastSheetSettingTest {
    private val ds = MemoryDataStore()
    private val repo = SettingsRepository(ds)

    @Test
    fun aSheetIsSavedAndReadBack() = runBlocking {
        repo.setCastSheet("example.com", "Mika: girl, polite")
        assertEquals("Mika: girl, polite", repo.castSheetFor("example.com"))
    }

    @Test
    fun theHostIsNotCaseSensitive() = runBlocking {
        repo.setCastSheet("Example.COM", "Mika: girl, polite")
        assertEquals("Mika: girl, polite", repo.castSheetFor("example.com"))
        assertEquals("Mika: girl, polite", repo.castSheetFor("EXAMPLE.com"))
    }

    @Test
    fun aBlankSheetRemovesTheHost() = runBlocking {
        repo.setCastSheet("example.com", "Mika: girl")
        repo.setCastSheet("example.com", "   ")
        assertEquals("", repo.castSheetFor("example.com"))
    }

    @Test
    fun aSheetIsCleaned() = runBlocking {
        repo.setCastSheet("example.com", "  Mika: girl\u0007  ")
        assertEquals("Mika: girl", repo.castSheetFor("example.com"))
    }

    @Test
    fun aDamagedFileGivesNoSheet() = runBlocking {
        ds.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey("castSheets"), "{not json") } }
        assertEquals("", repo.castSheetFor("example.com"))
    }

    @Test
    fun twoWebsitesKeepTheirOwnSheet() = runBlocking {
        repo.setCastSheet("a.com", "A: girl")
        repo.setCastSheet("b.com", "B: boy")
        assertEquals("A: girl", repo.castSheetFor("a.com"))
        assertEquals("B: boy", repo.castSheetFor("b.com"))
    }

    @Test
    fun theSheetFlowFollowsTheSheet() = runBlocking {
        assertEquals("", repo.castSheetFlow("example.com").first())
        repo.setCastSheet("example.com", "Mika: girl")
        assertEquals("Mika: girl", repo.castSheetFlow("example.com").first())
    }
}

/** The patch cache key: a variant changes it, an empty one leaves it exactly as it was. */
class PatchCacheKeyTest {
    // The key does not touch the disk, so a cache in a temp folder is enough.
    private val cache = PatchCache(java.nio.file.Files.createTempDirectory("pg-cache").toFile())

    @Test
    fun anEmptyVariantLeavesTheKeyUnchanged() {
        val without = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3)
        val withEmpty = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3, variant = "")
        assertEquals(without, withEmpty)
    }

    @Test
    fun aVariantChangesTheKey() {
        val plain = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3)
        val varied = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3, variant = "ctx6")
        assertNotEquals(plain, varied)
        assertTrue(varied.endsWith("|ctx6"))
    }

    @Test
    fun twoVariantsGiveTwoKeys() {
        val a = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3, variant = "ctx6")
        val b = cache.key("h", Lang.JA, Lang.EN, EngineId.GEMINI, 3, variant = "ctx12")
        assertNotEquals(a, b)
    }
}
