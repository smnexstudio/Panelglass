package com.smnexstudio.panelglass.feature.settings

import android.content.Context
import androidx.room.Room
import com.smnexstudio.panelglass.core.data.db.PanelglassDb
import com.smnexstudio.panelglass.core.data.download.SystemDownloads
import com.smnexstudio.panelglass.core.data.prefs.SettingsRepository
import com.smnexstudio.panelglass.core.data.repo.HistoryRepository
import com.smnexstudio.panelglass.core.data.repo.StudioRepository
import com.smnexstudio.panelglass.core.data.secure.BlobCrypto
import com.smnexstudio.panelglass.core.data.secure.SecureKeyStore
import com.smnexstudio.panelglass.core.data.studio.StudioFiles
import com.smnexstudio.panelglass.core.data.update.AppUpdates
import com.smnexstudio.panelglass.core.engine.EngineRegistry
import com.smnexstudio.panelglass.core.engine.http.HttpEngineSupport
import com.smnexstudio.panelglass.core.engine.llm.ClaudeEngine
import com.smnexstudio.panelglass.core.engine.llm.DeepSeekEngine
import com.smnexstudio.panelglass.core.engine.llm.GeminiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenAiEngine
import com.smnexstudio.panelglass.core.engine.llm.OpenRouterEngine
import com.smnexstudio.panelglass.core.engine.local.LocalEngines
import com.smnexstudio.panelglass.core.engine.local.ModelStores
import com.smnexstudio.panelglass.core.engine.mt.DeepLEngine
import com.smnexstudio.panelglass.core.engine.mt.GoogleTranslateEngine
import com.smnexstudio.panelglass.core.engine.mt.LanguagePackStore
import com.smnexstudio.panelglass.core.engine.mt.PackManager
import com.smnexstudio.panelglass.core.engine.mt.PairTranslator
import com.smnexstudio.panelglass.core.engine.mt.PapagoEngine
import com.smnexstudio.panelglass.core.ocr.LamaStore
import com.smnexstudio.panelglass.core.ocr.MangaOcrStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/**
 * A real [SettingsViewModel] over an in-memory database and DataStore. Every HTTP request fails at once (and is
 * recorded in [attempted]), Google Translate answers `EN(<text>)` on the device, and language packs never download, so
 * nothing here reaches the internet.
 */
internal class SettingsRig(private val context: Context) {
    val root = File(context.filesDir, "settings-rig").apply { deleteRecursively(); mkdirs() }
    val db: PanelglassDb = Room.inMemoryDatabaseBuilder(context, PanelglassDb::class.java).allowMainThreadQueries().build()
    val settings = SettingsRepository(MemoryDataStore())
    val history = HistoryRepository(db)
    val studio = StudioRepository(db, StudioFiles(File(root, "studio")))
    val attempted = mutableListOf<String>()
    lateinit var registry: EngineRegistry
        private set
    private val models = TestViewModels()

    fun vm(): SettingsViewModel {
        val keys = SecureKeyStore(MemoryDataStore(), Plain)
        val offline = HttpEngineSupport(
            OkHttpClient.Builder().addInterceptor { synchronized(attempted) { attempted += it.request().url.host }; throw IOException("offline") }.build(),
            Json { ignoreUnknownKeys = true },
        )
        val downloads = SystemDownloads(context)
        val gemini = GeminiEngine(offline, keys, settings)
        val google = GoogleTranslateEngine().apply {
            translatorFactory = { _, _ -> object : PairTranslator {
                override suspend fun prepare() = Unit
                override suspend fun translate(text: String) = "EN($text)"
            } }
        }
        registry = EngineRegistry(
            LocalEngines(context, ModelStores(context, downloads)), gemini, com.smnexstudio.panelglass.core.engine.llm.CustomOpenAiEngine(offline, keys, settings), ClaudeEngine(offline, keys, settings),
            OpenAiEngine(offline, keys, settings), DeepLEngine(offline, keys), PapagoEngine(offline, keys), DeepSeekEngine(offline, keys),
            google, OpenRouterEngine(offline, keys, settings), keys, settings,
        )
        val packs = LanguagePackStore().apply { manager = NoPacks }
        return models.keep(SettingsViewModel(
            settings, history, registry, gemini, context, ModelStores(context, downloads), packs,
            MangaOcrStore(context, downloads), LamaStore(context, downloads), studio, AppUpdates(context, downloads, settings),
        ))
    }

    /** Clears the view models first: their scopes must not outlive the database. */
    fun close() {
        models.clear()
        db.close()
        root.deleteRecursively()
    }

    private object NoPacks : PackManager {
        override suspend fun downloaded() = setOf("en", "ja")
        override suspend fun download(tag: String) = throw IOException("offline")
        override suspend fun delete(tag: String) = Unit
    }

    private object Plain : BlobCrypto {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(blob: String) = blob.reversed()
    }
}
