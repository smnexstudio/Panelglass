package com.smnexstudio.panelglass

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What every start settles: nobody is switched off Qwen by the new default, and the first-start packs are fetched once. */
class StartupTasksTest {
    private val settings = SettingsRepository(object : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> get() = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = state.updateAndGet { transform(it) }
    })
    private var fetches = 0
    private var modelFetches = 0

    private fun tasks(qwen: Boolean, online: Boolean = true, queued: Boolean = true) =
        StartupTasks(settings, { qwen }, { fetches++; online }, { modelFetches++; queued })

    @Test fun someoneWhoNeverChoseAndHasQwenStaysOnQwen() = runBlocking {
        tasks(qwen = true).run()
        assertEquals(EngineId.QWEN15_LOCAL, settings.current().engineId)
    }

    @Test fun anExplicitChoiceIsNeverChanged() = runBlocking {
        settings.setEngine(EngineId.GOOGLE)
        tasks(qwen = true).run()
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
    }

    @Test fun withoutQwenTheDefaultAppliesAndNothingIsWritten() = runBlocking {
        tasks(qwen = false).run()
        assertEquals(EngineId.GOOGLE, settings.current().engineId)
        assertFalse(settings.hasEngineChoice())
    }

    /** Before the first-open setup is answered nothing is downloaded: the user sees the list and its size first. */
    @Test fun nothingIsFetchedBeforeTheSetupIsAnswered() = runBlocking {
        tasks(qwen = false).run()
        assertEquals(0, fetches)
        assertEquals(0, modelFetches)
        settings.setFirstDownloadsChosen()
        tasks(qwen = false).run()
        assertEquals(1, fetches)
        assertEquals(1, modelFetches)
    }

    /** Offline at first start: nothing is marked done, so the next start fetches again. */
    @Test fun thePacksAreFetchedUntilTheyAreAllThereOnce() = runBlocking {
        settings.setFirstDownloadsChosen()
        tasks(qwen = false, online = false).run()
        assertFalse(settings.automaticPacksDone())
        tasks(qwen = false, online = true).run()
        assertTrue(settings.automaticPacksDone())
        tasks(qwen = false, online = true).run()
        assertEquals(2, fetches, "a pack the user deleted later is not fetched again")
    }

    /** manga-ocr and LaMa: queued at first start; when they could not be queued the next start tries again, then never. */
    @Test fun theModelsAreQueuedUntilQueuedOnce() = runBlocking {
        settings.setFirstDownloadsChosen()
        tasks(qwen = false, queued = false).run()
        assertFalse(settings.automaticModelsDone())
        tasks(qwen = false, queued = true).run()
        assertTrue(settings.automaticModelsDone())
        tasks(qwen = false, queued = true).run()
        assertEquals(2, modelFetches, "a model the user deleted later is not fetched again")
    }

    /** The pack fetch waits for its downloads; the models are queued before it, never after. */
    @Test fun theModelsAreQueuedBeforeThePacksAreAwaited() = runBlocking {
        settings.setFirstDownloadsChosen()
        val order = mutableListOf<String>()
        StartupTasks(settings, { false }, { order += "packs"; true }, { order += "models"; true }).run()
        assertEquals(listOf("models", "packs"), order)
    }
}
