package com.smnexstudio.panelglass.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.secure.BlobCrypto
import com.smnexstudio.panelglass.core.data.secure.KeyStoreBlobCrypto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides @Singleton
    fun db(@ApplicationContext ctx: Context): PanelglassDb =
        Room.databaseBuilder(ctx, PanelglassDb::class.java, "panelglass.db")
            .addMigrations(*PanelglassDb.MIGRATIONS)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { ctx.preferencesDataStoreFile("settings") }

    /** Hardware-backed AES/GCM for API keys; the interface exists so tests can substitute a plain cipher. */
    @Provides @Singleton
    fun blobCrypto(): BlobCrypto = KeyStoreBlobCrypto()

    @Provides @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    /** Single shared client for block list fetching and web requests. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun studioFiles(@ApplicationContext ctx: Context): com.smnexstudio.panelglass.core.data.studio.StudioFiles =
        com.smnexstudio.panelglass.core.data.studio.StudioFiles(java.io.File(ctx.filesDir, "studio"))

    @Provides @Singleton
    fun patchCache(@ApplicationContext ctx: Context): com.smnexstudio.panelglass.core.data.cache.PatchCache =
        com.smnexstudio.panelglass.core.data.cache.PatchCache(java.io.File(ctx.cacheDir, "patches"))
}
