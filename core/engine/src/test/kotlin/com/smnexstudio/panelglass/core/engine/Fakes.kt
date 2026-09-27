package com.smnexstudio.panelglass.core.engine

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.secure.BlobCrypto
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** In-memory DataStore so SettingsRepository and SecureKeyStore run under plain JUnit. */
class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        state.updateAndGet { transform(it) }
}

/** Reversible "encryption" for tests: the real store uses AndroidKeyStore, which the JVM lacks. */
object PlainCrypto : BlobCrypto {
    override fun encrypt(plain: String) = plain.reversed()
    override fun decrypt(blob: String) = blob.reversed()
}

fun testKeyStore(ds: DataStore<Preferences> = MemoryDataStore()) = SecureKeyStore(ds, PlainCrypto)
fun testSettings(ds: DataStore<Preferences> = MemoryDataStore()) = SettingsRepository(ds)
fun testSupport() = HttpEngineSupport(OkHttpClient(), Json { ignoreUnknownKeys = true; isLenient = true })

