package com.smnexstudio.panelglass.core.data.prefs

import com.smnexstudio.panelglass.core.data.MemoryDataStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanEndpointStorageTest {
    @Test
    fun cloudEndpointIsNeverInheritedByLan() = runBlocking {
        val repo = SettingsRepository(MemoryDataStore())
        repo.setCustomBaseUrl("https://cloud.example/v1")
        assertEquals("", repo.current().lanBaseUrl)
        assertEquals("https://cloud.example/v1", repo.current().customBaseUrl)
    }

    @Test
    fun endpointsRemainIndependentAndPersistAcrossRepositoryInstances() = runBlocking {
        val store = MemoryDataStore()
        val repo = SettingsRepository(store)
        repo.setCustomBaseUrl("https://cloud.example/v1")
        repo.setLanBaseUrl("  http://192.168.1.5:1234/v1  ")
        val restored = SettingsRepository(store).current()
        assertEquals("http://192.168.1.5:1234/v1", restored.lanBaseUrl)
        assertEquals("https://cloud.example/v1", restored.customBaseUrl)
        repo.setCustomBaseUrl("https://other.example/v1")
        assertEquals("http://192.168.1.5:1234/v1", repo.current().lanBaseUrl)
    }

    @Test
    fun clearingEitherEndpointPreservesTheOther() = runBlocking {
        val repo = SettingsRepository(MemoryDataStore())
        repo.setCustomBaseUrl("https://cloud.example/v1")
        repo.setLanBaseUrl("http://192.168.1.5:1234/v1")
        repo.setLanBaseUrl("   ")
        assertEquals("", repo.current().lanBaseUrl)
        assertEquals("https://cloud.example/v1", repo.current().customBaseUrl)
        repo.setLanBaseUrl("http://192.168.1.5:1234/v1")
        repo.setCustomBaseUrl("")
        assertEquals("", repo.current().customBaseUrl)
        assertEquals("http://192.168.1.5:1234/v1", repo.current().lanBaseUrl)
    }
}
