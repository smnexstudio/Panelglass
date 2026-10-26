package com.smnexstudio.panelglass.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.model.RetentionLimits
import com.smnexstudio.panelglass.core.model.SavedTranslations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** An in-memory DataStore, so the repository runs under plain JUnit. */
private class RetentionDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        state.updateAndGet { transform(it) }
}

/** The retention settings: what the reader keeps in memory, and what it keeps on disk. */
class RetentionSettingsTest {
    private val ds = RetentionDataStore()
    private val repo = SettingsRepository(ds)

    @Test
    fun theDefaultsAreTheAppsOwn() = runBlocking {
        val s = repo.current()
        assertEquals(0, s.overlayScreens)
        assertEquals(0, s.overlayMemoryMb)
        assertEquals(SavedTranslations.CHAPTER, s.savedTranslations)
        assertEquals(200, s.savedTranslationsMb)
    }

    @Test
    fun theScreenCountIsSavedAndReadBack() = runBlocking {
        repo.setOverlayScreens(5)
        assertEquals(5, repo.current().overlayScreens)
        repo.setOverlayScreens(0)
        assertEquals(0, repo.current().overlayScreens)
    }

    @Test
    fun theMemoryCapIsSavedAndReadBack() = runBlocking {
        repo.setOverlayMemoryMb(128)
        assertEquals(128, repo.current().overlayMemoryMb)
    }

    @Test
    fun theSavedTranslationModeIsSavedAndReadBack() = runBlocking {
        repo.setSavedTranslations(SavedTranslations.WEEK)
        assertEquals(SavedTranslations.WEEK, repo.current().savedTranslations)
        repo.setSavedTranslations(SavedTranslations.OFF)
        assertEquals(SavedTranslations.OFF, repo.current().savedTranslations)
    }

    @Test
    fun theStorageCapIsSavedAndReadBack() = runBlocking {
        repo.setSavedTranslationsMb(500)
        assertEquals(500, repo.current().savedTranslationsMb)
    }

    @Test
    fun anOutOfRangeStoredValueIsClamped() = runBlocking {
        ds.updateData {
            it.toMutablePreferences().apply {
                set(intPreferencesKey("overlayScreens"), 7)
                set(intPreferencesKey("overlayMemoryMb"), 999)
                set(intPreferencesKey("savedTranslationsMb"), 3)
            }
        }
        val s = repo.current()
        assertEquals(RetentionLimits.DEFAULT_SCREENS, s.overlayScreens)
        assertEquals(RetentionLimits.DEFAULT_OVERLAY_MB, s.overlayMemoryMb)
        assertEquals(RetentionLimits.DEFAULT_STORAGE_MB, s.savedTranslationsMb)
    }

    @Test
    fun anUnknownStoredModeFallsBackToTheDefault() = runBlocking {
        ds.updateData { it.toMutablePreferences().apply { set(stringPreferencesKey("savedTranslations"), "NONSENSE") } }
        assertEquals(SavedTranslations.CHAPTER, repo.current().savedTranslations)
    }

    @Test
    fun aValueSetOutOfRangeIsClampedBeforeItIsStored() = runBlocking {
        repo.setOverlayScreens(7)
        assertEquals(RetentionLimits.DEFAULT_SCREENS, repo.current().overlayScreens)
        repo.setSavedTranslationsMb(3)
        assertEquals(RetentionLimits.DEFAULT_STORAGE_MB, repo.current().savedTranslationsMb)
    }

    @Test
    fun theOtherSettingsAreNotDisturbed() = runBlocking {
        repo.setOverlayScreens(5)
        repo.setSavedTranslations(SavedTranslations.WEEK)
        val s = repo.current()
        assertEquals(5, s.overlayScreens)
        assertEquals(SavedTranslations.WEEK, s.savedTranslations)
    }
}
