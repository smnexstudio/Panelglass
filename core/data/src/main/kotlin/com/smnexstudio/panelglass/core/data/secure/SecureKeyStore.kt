package com.smnexstudio.panelglass.core.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.smnexstudio.panelglass.core.model.EngineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider API keys, encrypted with an AndroidKeyStore AES/GCM key and stored as opaque
 * base64 blobs in DataStore. Keys are never logged and never leave the device.
 */
@Singleton
class SecureKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val crypto: BlobCrypto,
) {
    private val cache = HashMap<EngineId, String?>()

    fun observeKeyPresence(): Flow<Set<EngineId>> =
        dataStore.data.map { prefs -> EngineId.entries.filter { prefs[prefKey(it)] != null }.toSet() }

    suspend fun get(engine: EngineId): String? {
        synchronized(cache) { if (cache.containsKey(engine)) return cache[engine] }
        val blob = dataStore.data.first()[prefKey(engine)]
        val value = blob?.let { runCatching { crypto.decrypt(it) }.getOrNull() }
        synchronized(cache) { cache[engine] = value }
        return value
    }

    suspend fun set(engine: EngineId, apiKey: String?) {
        val trimmed = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        dataStore.edit { prefs ->
            if (trimmed == null) prefs.remove(prefKey(engine)) else prefs[prefKey(engine)] = crypto.encrypt(trimmed)
        }
        synchronized(cache) { cache[engine] = trimmed }
    }

    private fun prefKey(engine: EngineId) = stringPreferencesKey("key_blob_" + engine.name)
}

/** Encrypts opaque strings. Abstracted so the repository can be unit-tested without a hardware keystore. */
interface BlobCrypto {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String
}

class KeyStoreBlobCrypto : BlobCrypto {
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "panelglass_api_keys_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
