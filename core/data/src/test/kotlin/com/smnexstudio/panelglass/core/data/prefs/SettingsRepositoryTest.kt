package com.smnexstudio.panelglass.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.data.MemoryDataStore
import com.smnexstudio.panelglass.core.model.BubbleFont
import com.smnexstudio.panelglass.core.model.EngineId
import com.smnexstudio.panelglass.core.model.Lang
import com.smnexstudio.panelglass.core.model.QwenBackend
import com.smnexstudio.panelglass.core.model.Settings
import com.smnexstudio.panelglass.core.model.SfxMode
import com.smnexstudio.panelglass.core.model.TranslationNotes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Settings as stored on a phone that updated across releases: values written by older versions (engines, models and
 * sound-effect modes since removed) read back as their successors, never as a crash or a silent engine switch.
 */
class SettingsRepositoryTest {
    private val store: DataStore<Preferences> = MemoryDataStore()
    private val repo = SettingsRepository(store)

    private fun raw(key: String, value: String) = runBlocking { store.edit { it[stringPreferencesKey(key)] = value } }

    @Test fun aFreshInstallReadsTheDefaults() = runBlocking {
        assertEquals(Settings(), repo.current())
        assertFalse(repo.hasEngineChoice())
        assertFalse(repo.automaticPacksDone())
        assertEquals(0L, repo.updateCheckedAt())
        assertEquals("en", repo.pageTranslateTarget())
        assertEquals(EngineId.GOOGLE, repo.current().engineId)
        assertTrue(repo.current().autoUpdateCheck)
    }

    /** Qwen 2.5 0.5B and Gemma 3 1B were removed on 2026-09-25: a saved choice reads as the default engine. */
    @Test fun aRemovedEngineReadsBackAsTheDefault() = runBlocking {
        for (gone in listOf("QWEN_LOCAL", "GEMMA3_LOCAL", "SOMETHING_NEW")) {
            raw("engineId", gone)
            assertEquals(EngineId.GOOGLE, repo.current().engineId, gone)
            assertTrue(repo.hasEngineChoice(), "a choice was made, even if it no longer exists")
        }
        repo.setEngine(EngineId.QWEN15_LOCAL)
        assertEquals(EngineId.QWEN15_LOCAL, repo.current().engineId)
    }

    /** Gloss and Overlay were removed on 2026-10-18: a saved one reads back as Replace. */
    @Test fun aRemovedSoundEffectModeReadsBackAsReplace() = runBlocking {
        for (gone in listOf("GLOSS", "OVERLAY")) {
            raw("sfxMode", gone)
            assertEquals(SfxMode.REPLACE, repo.current().sfxMode, gone)
        }
        repo.setSfxMode(SfxMode.SKIP)
        assertEquals(SfxMode.SKIP, repo.current().sfxMode)
    }

    /** A Gemini model the provider now refuses (an old default saved as-is) resolves to the current default. */
    @Test fun aRetiredModelResolvesToTheProvidersDefault() = runBlocking {
        for (gone in Settings.RETIRED_MODELS) {
            repo.setModel(EngineId.GEMINI, gone)
            assertEquals(EngineId.GEMINI.defaultModel, repo.current().modelFor(EngineId.GEMINI), gone)
        }
        repo.setModel(EngineId.GEMINI, "  gemma-3-27b-it ")
        assertEquals("gemma-3-27b-it", repo.current().modelFor(EngineId.GEMINI))
        repo.setModel(EngineId.GEMINI, "   ")
        assertNull(repo.current().models[EngineId.GEMINI], "blank clears the choice")
        assertEquals(EngineId.GEMINI.defaultModel, repo.current().modelFor(EngineId.GEMINI))
    }

    /** The reader's font before the Studio catalogue was a [BubbleFont] name; it reads as that font's catalogue id. */
    @Test fun aFontSavedBeforeTheCatalogueReadsAsItsCatalogueId() = runBlocking {
        raw("bubbleFont", BubbleFont.COMING_SOON.name)
        assertEquals("cat:coming_soon", repo.current().readerFontId)
        repo.setReaderFont("user:abc")
        assertEquals("user:abc", repo.current().readerFontId)
        repo.setReaderFont(null)
        assertNull(repo.current().readerFontId, "Auto wins over the old font once chosen")
    }

    @Test fun unknownStoredValuesFallBackToDefaults() = runBlocking {
        raw("defaultSourceLang", "KLINGON")
        raw("qwenBackend", "NPU")
        raw("appTheme", "neon")
        val s = repo.current()
        assertEquals(Lang.JA, s.defaultSourceLang)
        assertEquals(QwenBackend.AUTO, s.qwenBackend)
        assertEquals(Settings().appTheme, s.appTheme)
    }

    /** Notes never saved read as the default; notes the user cleared stay empty and never come back. */
    @Test fun translationNotesStartWithTheDefaultAndClearedNotesStayEmpty() = runBlocking {
        assertEquals(TranslationNotes.DEFAULT, repo.current().translationNotes)
        assertTrue(TranslationNotes.DEFAULT.length <= TranslationNotes.MAX_CHARS)
        repo.setTranslationNotes("")
        assertEquals("", repo.current().translationNotes)
    }

    /** "Only while charging" is off until switched on; the running chapter is kept until the run clears it. */
    @Test fun studioRunSettings() = runBlocking {
        assertFalse(repo.current().studioOnlyWhileCharging)
        repo.setStudioOnlyWhileCharging(true)
        assertTrue(repo.current().studioOnlyWhileCharging)
        assertNull(repo.studioRunChapter())
        repo.setStudioRunChapter(42L)
        assertEquals(42L, repo.studioRunChapter())
        repo.setStudioRunChapter(null)
        assertNull(repo.studioRunChapter())
    }

    @Test fun translationNotesAreTrimmedAndCapped() = runBlocking {
        repo.setTranslationNotes("  " + "n".repeat(TranslationNotes.MAX_CHARS + 50) + "  ")
        assertEquals(TranslationNotes.MAX_CHARS, repo.current().translationNotes.length)
        repo.setTranslationNotes("  Keep honorifics.  ")
        assertEquals("Keep honorifics.", repo.current().translationNotes)
    }

    @Test fun updateSettingsRoundTrip() = runBlocking {
        repo.setAutoUpdateCheck(false)
        repo.setUpdateCheckedAt(42L)
        assertFalse(repo.current().autoUpdateCheck)
        assertEquals(42L, repo.updateCheckedAt())
    }

    /** Set once after the first-start packs: a pack the user deletes later is not fetched again. */
    @Test fun theAutomaticPacksFlagStaysSet() = runBlocking {
        repo.setAutomaticPacksDone()
        assertTrue(repo.automaticPacksDone())
        store.edit { it[booleanPreferencesKey("automaticPacksDone")] = true }
        assertTrue(repo.automaticPacksDone())
    }

    /** What Gemini learned about a model is kept per model, never shared with another. */
    @Test fun requestProfilesAreKeptPerModel() = runBlocking {
        repo.setRequestProfile(EngineId.GEMINI, "gemini-flash-latest", "JSON_ONLY")
        assertEquals("JSON_ONLY", repo.requestProfile(EngineId.GEMINI, "gemini-flash-latest"))
        assertNull(repo.requestProfile(EngineId.GEMINI, "gemma-3-27b-it"))
    }

    @Test fun fontFavouritesToggleInOrder() = runBlocking {
        repo.toggleFontFavourite("cat:a"); repo.toggleFontFavourite("user:b"); repo.toggleFontFavourite("cat:c")
        repo.toggleFontFavourite("user:b")
        assertEquals(listOf("cat:a", "cat:c"), repo.fontFavourites.first())
    }
}
