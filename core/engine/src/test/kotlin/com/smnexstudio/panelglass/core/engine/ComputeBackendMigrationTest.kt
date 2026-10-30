package com.smnexstudio.panelglass.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.model.ComputeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class ComputeBackendMigrationTest {
    @Test fun aFreshInstallUsesAutomatic() = runBlocking {
        assertEquals(ComputeBackend.AUTO, SettingsRepository(MemoryDataStore()).current().computeBackend)
    }

    @Test fun everyLegacyChoiceMigrates() = runBlocking {
        for (choice in ComputeBackend.entries) {
            val ds = MemoryDataStore()
            ds.edit { it[stringPreferencesKey("qwenBackend")] = choice.name }
            assertEquals(choice, SettingsRepository(ds).current().computeBackend)
        }
    }

    @Test fun theNewChoiceWinsOverTheLegacyChoice() = runBlocking {
        val ds = MemoryDataStore()
        ds.edit {
            it[stringPreferencesKey("qwenBackend")] = "GPU"
            it[stringPreferencesKey("computeBackend")] = "CPU"
        }
        assertEquals(ComputeBackend.CPU, SettingsRepository(ds).current().computeBackend)
    }

    @Test fun theSettingRoundTripsAndKeepsTheLegacyFieldCompatible() = runBlocking {
        val repo = SettingsRepository(MemoryDataStore())
        for (choice in ComputeBackend.entries) {
            repo.setComputeBackend(choice)
            assertEquals(choice, repo.current().computeBackend)
            assertEquals(choice.name, repo.current().qwenBackend.name)
        }
    }

    @Test fun bothBuiltInModelsFollowTheChoiceAtTheirNextLoad() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val repo = SettingsRepository(MemoryDataStore())
        val locals = LocalEngines(context, ModelStores(context, SystemDownloads(context)))
        locals.bindSettings(repo)
        repo.setComputeBackend(ComputeBackend.CPU)
        assertTrue(locals.all.all { it.cpuOnly() })
        repo.setComputeBackend(ComputeBackend.GPU)
        assertTrue(locals.all.none { it.cpuOnly() })
    }
}
