package com.smnexstudio.panelglass.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.smnexstudio.panelglass.core.data.secure.KeyStoreBlobCrypto
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Keys survive a "process restart" (a fresh store over the same file) and never sit in plaintext on disk. */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keyRoundTripsAcrossInstancesAndIsEncryptedAtRest() { runBlocking {
        val file: File = ctx.preferencesDataStoreFile("keystore-test-" + System.nanoTime())
        val ds = PreferenceDataStoreFactory.create { file }
        val secret = "sk-test-" + System.nanoTime()

        val first = SecureKeyStore(ds, KeyStoreBlobCrypto())
        first.set(EngineId.GEMINI, secret)
        assertEquals(secret, first.get(EngineId.GEMINI))
        assertTrue(first.observeKeyPresence().first().contains(EngineId.GEMINI))

        // A new instance with an empty in-memory cache must decrypt from the blob.
        val second = SecureKeyStore(ds, KeyStoreBlobCrypto())
        assertEquals(secret, second.get(EngineId.GEMINI))

        // The plaintext must not be recoverable from the file.
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(secret))

        second.set(EngineId.GEMINI, null)
        assertNull(SecureKeyStore(ds, KeyStoreBlobCrypto()).get(EngineId.GEMINI))
        file.delete()
    } }
}
